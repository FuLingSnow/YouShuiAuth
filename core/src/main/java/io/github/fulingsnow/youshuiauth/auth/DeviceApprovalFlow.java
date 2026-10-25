package io.github.fulingsnow.youshuiauth.auth;

import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.model.QqBinding;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.qq.CrossDeploymentClock;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.qq.QqDeviceApprovals;
import io.github.fulingsnow.youshuiauth.qq.QqNotify;
import io.github.fulingsnow.youshuiauth.qq.QqResult;
import io.github.fulingsnow.youshuiauth.qq.QqStopRequests;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.util.PlatformFingerprints;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * 新设备确认状态机（平台无关）：免密指纹不匹配时不直接拒绝，而是向账号绑定的
 * QQ 群推送确认通知，群内回复 Y 放行并记住设备、N 拒绝并作废旧设备记录。
 * 发起与裁决都在这里；放行/踢人等平台动作由调用方根据 {@link DecisionOutcome} 执行。
 */
public final class DeviceApprovalFlow {

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private final QqSettings qqSettings;
    private final Settings coreSettings;
    private final DatabaseManager databaseManager;
    private final Supplier<QqDao> daoSupplier;
    private final QqDeviceApprovals approvals;
    private final QqStopRequests stopRequests;
    private final String platformTag;
    private final Logger logger;
    private final Queue<QqNotify> notifications = new ConcurrentLinkedQueue<>();

    public DeviceApprovalFlow(QqSettings qqSettings, Settings coreSettings,
                              DatabaseManager databaseManager, Supplier<QqDao> daoSupplier,
                              String platformTag, Logger logger) {
        this.qqSettings = qqSettings;
        this.coreSettings = coreSettings;
        this.databaseManager = databaseManager;
        this.daoSupplier = daoSupplier;
        this.platformTag = platformTag;
        this.approvals = new QqDeviceApprovals(qqSettings.getDeviceApprovalTimeoutSeconds());
        this.stopRequests = new QqStopRequests(qqSettings.getDeviceApprovalTimeoutSeconds());
        this.logger = logger;
    }

    public boolean isEnabled() {
        return qqSettings.isEnabled() && qqSettings.isDeviceApprovalEnabled()
                && coreSettings.isAutoLoginEnabled() && dao() != null;
    }

    /** 撤销免密（引用回复「阻止」）与新设备确认无关，只要求 QQ 与免密开启。 */
    private boolean isStopEnabled() {
        return qqSettings.isEnabled() && coreSettings.isAutoLoginEnabled() && dao() != null;
    }

    public boolean request(AuthPlayer player, ConnectionRef operation) {
        String fingerprint = PlatformFingerprints.fingerprint(
                player, coreSettings.isAutoLoginRequireSameSubnet(), platformTag);
        return request(player, operation, fingerprint,
                PlatformFingerprints.hash(player.uniqueId().toString(), fingerprint));
    }

    public boolean request(AuthPlayer player, ConnectionRef operation,
                           String fingerprint, String fingerprintHash) {
        if (!isEnabled() || player == null || player.onlineMode() || operation == null
                || isBlank(fingerprint) || isBlank(fingerprintHash)) {
            return false;
        }
        String uuid = player.uniqueId().toString();
        long now = System.currentTimeMillis();
        try {
            QqDao dao = dao();
            RegisteredPlayer account = databaseManager.findRegisteredPlayerByUuid(player.uniqueId());
            if (account == null) {
                return false;
            }

            if (coreSettings.getTwoFactorSettings().isEnabled()
                    && account.getTotpToken() != null && !account.getTotpToken().isBlank()) {
                return false;
            }
            Optional<QqBinding> binding = dao.findByUuid(QqOps.PLATFORM_QQ, uuid);
            if (binding.isEmpty()) {
                return false;
            }
            if (dao.findUsableRememberTokenByUuidAndPlatform(uuid, now, platformTag).isEmpty()) {
                return false;
            }
            Optional<QqRememberToken> matching = dao.findRememberToken(fingerprintHash);
            if (matching.isPresent() && matching.get().getUuid().equals(uuid)
                    && CrossDeploymentClock.isUsable(matching.get().getExpiresAt(), now)) {
                return false;
            }

            String qqId = binding.get().getQqId();
            int ttlSeconds = qqSettings.getDeviceApprovalTimeoutSeconds();
            String approvalId = UUID.randomUUID().toString().replace("-", "");
            QqDeviceApprovals.PendingDeviceApproval approval =
                    new QqDeviceApprovals.PendingDeviceApproval(
                            QqOps.PLATFORM_QQ, qqId, approvalId, player.uniqueId(), operation,
                            fingerprint, now + ttlSeconds * 1000L);
            approvals.put(approval);

            notifications.add(new QqNotify(qqId, approvalMessage(player, ttlSeconds),
                    QqNotify.TYPE_DEVICE_APPROVAL, approvalId));
            logger.info(SECURITY_MARKER, "New-device QQ approval requested for {} (ttl={}s)",
                    player.username(), ttlSeconds);
            return true;
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "New-device approval unavailable for {}", player.username());
            return false;
        }
    }

    public boolean isPending(UUID playerId) {
        return approvals.peekByUuid(playerId).isPresent();
    }

    public Optional<QqDeviceApprovals.PendingDeviceApproval> peekByUuid(UUID playerId) {
        return approvals.peekByUuid(playerId);
    }

    public Optional<QqDeviceApprovals.PendingDeviceApproval> consumeByApprovalId(String approvalId) {
        return approvals.consumeByApprovalId(approvalId);
    }

    public Optional<QqDeviceApprovals.PendingDeviceApproval> cancel(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return approvals.consumeByUuid(playerId);
    }

    public QqNotify pollNotification() {
        return notifications.poll();
    }

    public record DecisionOutcome(QqResult result,
                                  QqDeviceApprovals.PendingDeviceApproval pending,
                                  boolean approve) {
    }

    public DecisionOutcome decide(String qqId, String decision, String approvalId) {
        if (!isEnabled()) {
            return new DecisionOutcome(QqResult.DISABLED, null, false);
        }
        if (isBlank(qqId) || isBlank(decision) || isBlank(approvalId)) {
            return new DecisionOutcome(QqResult.INVALID_CODE, null, false);
        }
        Boolean approve = parseDecision(decision);
        if (approve == null) {
            return new DecisionOutcome(QqResult.INVALID_DECISION, null, false);
        }
        Optional<QqDeviceApprovals.PendingDeviceApproval> pending =
                approvals.peekByApprovalId(approvalId.trim());
        if (pending.isEmpty()) {
            return new DecisionOutcome(QqResult.NO_PENDING_APPROVAL, null, false);
        }
        if (!hasDao()) {
            return new DecisionOutcome(QqResult.ERROR, null, false);
        }
        try {
            Optional<QqBinding> binding = dao().findByUuid(
                    pending.get().platform(), pending.get().uuid().toString());
            if (binding.isEmpty() || !binding.get().getQqId().equals(qqId.trim())) {
                logger.warn(SECURITY_MARKER,
                        "Rejected device-approval reply from non-owner QQ for UUID {}",
                        pending.get().uuid());
                return new DecisionOutcome(QqResult.FORBIDDEN, null, false);
            }
        } catch (SQLException e) {
            return new DecisionOutcome(QqResult.ERROR, null, false);
        }
        if (approvals.consumeByApprovalId(approvalId.trim()).isEmpty()) {
            return new DecisionOutcome(QqResult.NO_PENDING_APPROVAL, null, false);
        }
        if (pending.get().isExpired(System.currentTimeMillis())) {
            return new DecisionOutcome(QqResult.CODE_EXPIRED, null, false);
        }
        return new DecisionOutcome(QqResult.OK, pending.get(), approve);
    }

    public record StopOutcome(QqResult result, UUID playerId) {
    }

    public void notifyAutoLogin(AuthPlayer player, String remainingTime) {
        if (!hasDao()) {
            logger.warn(SECURITY_MARKER, "QQ binding store unavailable, skip auto-login notice");
            return;
        }
        try {
            Optional<QqBinding> binding = dao().findByUuid(
                    QqOps.PLATFORM_QQ, player.uniqueId().toString());
            if (binding.isEmpty()) {
                return;
            }
            String stopId = UUID.randomUUID().toString().replace("-", "");
            long expiresAt = System.currentTimeMillis()
                    + qqSettings.getDeviceApprovalTimeoutSeconds() * 1000L;
            stopRequests.put(new QqStopRequests.StopRequest(
                    QqOps.PLATFORM_QQ, binding.get().getQqId(), player.uniqueId(),
                    stopId, expiresAt));
            String message = "[YouShuiAuth] 账号 " + player.username()
                    + " 刚刚通过 Cookie 自动登录，免密剩余 " + remainingTime
                    + "。若非本人操作，请引用本条消息回复「阻止」撤销免密（"
                    + qqSettings.getDeviceApprovalTimeoutSeconds() + " 秒内有效）。";
            notifications.add(new QqNotify(binding.get().getQqId(), message,
                    QqNotify.TYPE_AUTO_LOGIN, stopId));
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Failed to queue auto-login notify for {}",
                    player.username());
        }
    }

    public StopOutcome stopAutoLogin(String platform, String groupId, String qqId, String stopId) {
        return stopAutoLogin(platform, groupId, qqId, stopId, false);
    }

    /**
     * 归属 = 回复者 QQ 等于账号绑定的 QQ；forceOverride（机器人超管）跳过归属校验，
     * 其余约束（stopId 有效、一次性消费、过期）不变。
     */
    public StopOutcome stopAutoLogin(String platform, String groupId, String qqId,
                                     String stopId, boolean forceOverride) {
        if (!isStopEnabled()) {
            return new StopOutcome(QqResult.DISABLED, null);
        }
        if (isBlank(qqId) || isBlank(stopId)) {
            return new StopOutcome(QqResult.INVALID_CODE, null);
        }
        if (!hasDao()) {
            return new StopOutcome(QqResult.ERROR, null);
        }
        Optional<QqStopRequests.StopRequest> requestOpt = stopRequests.peek(stopId.trim());
        if (requestOpt.isEmpty()) {
            return new StopOutcome(QqResult.NO_PENDING_APPROVAL, null);
        }
        QqStopRequests.StopRequest request = requestOpt.get();
        if (!forceOverride) {
            try {
                Optional<QqBinding> binding =
                        dao().findByUuid(request.platform(), request.uuid().toString());
                if (binding.isEmpty() || !binding.get().getQqId().equals(qqId.trim())) {
                    logger.warn(SECURITY_MARKER,
                            "Rejected auto-login stop from non-owner QQ for UUID {}",
                            request.uuid());
                    return new StopOutcome(QqResult.FORBIDDEN, null);
                }
            } catch (SQLException e) {
                return new StopOutcome(QqResult.ERROR, null);
            }
        }
        if (stopRequests.consume(stopId.trim()).isEmpty()) {
            return new StopOutcome(QqResult.NO_PENDING_APPROVAL, null);
        }
        if (request.isExpired(System.currentTimeMillis())) {
            return new StopOutcome(QqResult.CODE_EXPIRED, null);
        }
        try {
            dao().deleteRememberTokensByUuid(request.uuid().toString());
            logger.info(SECURITY_MARKER, "Device records revoked via QQ stop reply for {}",
                    request.uuid());
            return new StopOutcome(QqResult.OK, request.uuid());
        } catch (SQLException e) {
            return new StopOutcome(QqResult.ERROR, null);
        }
    }

    private String approvalMessage(AuthPlayer player, int ttlSeconds) {
        String ip = player.address() == null ? "unknown"
                : PlatformFingerprints.subnetLabel(player.address().getHostAddress());
        String client = player.clientBrand() + " / 版本 " + player.protocolVersion();
        String message = "[YouShuiAuth] 账号 " + player.username() + " 新设备登录请求：" + client
                + " / IP 段 " + ip + "，有效期 " + ttlSeconds + " 秒。"
                + "确认本人请引用本条消息回复 Y，拒绝回复 N。";
        String joinUrl = qqSettings.getJoinUrl();
        return joinUrl == null || joinUrl.isBlank() ? message
                : message + "\n未入群请先加群：" + joinUrl;
    }

    private static Boolean parseDecision(String decision) {
        return switch (decision.trim().toLowerCase()) {
            case "y", "yes", "approve" -> Boolean.TRUE;
            case "n", "no", "deny" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private QqDao dao() {
        return daoSupplier.get();
    }

    /**
     * DatabaseManager.getQqDao 允许返回 null（QQ 关闭或库未就绪）。
     * 这些判定在 NPE 逃出 SQLException 的 catch 后会变成桥接 500，所以统一在这里拦掉。
     */
    private boolean hasDao() {
        return dao() != null;
    }
}
