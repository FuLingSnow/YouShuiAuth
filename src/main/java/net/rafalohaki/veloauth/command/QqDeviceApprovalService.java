package net.rafalohaki.veloauth.command;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rafalohaki.veloauth.VeloAuth;
import net.rafalohaki.veloauth.audit.AuditEventType;
import net.rafalohaki.veloauth.lifecycle.ConnectionLifecycleRegistry;
import net.rafalohaki.veloauth.model.QqBinding;
import net.rafalohaki.veloauth.model.QqRememberToken;
import net.rafalohaki.veloauth.model.RegisteredPlayer;
import net.rafalohaki.veloauth.qq.QqDeviceApprovals;
import net.rafalohaki.veloauth.qq.QqDeviceApprovals.PendingDeviceApproval;
import net.rafalohaki.veloauth.qq.QqDao;
import net.rafalohaki.veloauth.qq.QqNotify;
import net.rafalohaki.veloauth.qq.QqResult;
import net.rafalohaki.veloauth.qq.QqSettings;
import net.rafalohaki.veloauth.qq.QqStopRequests;
import net.rafalohaki.veloauth.util.DeviceFingerprints;
import net.rafalohaki.veloauth.util.PlayerAddressUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Owns the interactive "new device login confirmation" and "stop cookie auto-login" flows:
 * pending approvals/stop requests, their timeouts, group notifications and the QQ-side
 * decision handling. Pure state machine — authorization and persistence stay in
 * {@link QqAuthService}, reached through the parent reference.
 */
final class QqDeviceApprovalService {

    private static final Logger logger = LoggerFactory.getLogger(QqDeviceApprovalService.class);
    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private final VeloAuth plugin;
    private final CommandContext ctx;
    private final QqSettings qqSettings;
    private final QqAuthService parent;
    private final QqDeviceApprovals deviceApprovals;
    private final QqStopRequests stopRequests;
    private final ConcurrentMap<String, ScheduledTask> deviceApprovalTasks = new ConcurrentHashMap<>();

    QqDeviceApprovalService(VeloAuth plugin, CommandContext ctx, QqSettings qqSettings,
            QqAuthService parent) {
        this.plugin = plugin;
        this.ctx = ctx;
        this.qqSettings = qqSettings;
        this.parent = parent;
        this.deviceApprovals = new QqDeviceApprovals(qqSettings.getDeviceApprovalTimeoutSeconds());
        this.stopRequests = new QqStopRequests(qqSettings.getDeviceApprovalTimeoutSeconds());
    }

    /**
     * @return true when the new-device confirmation is usable (QQ bridge, feature switch and
     *         device auto-login all enabled)
     */
    boolean isDeviceApprovalEnabled() {
        return parent.isEnabled()
                && qqSettings.isDeviceApprovalEnabled()
                && ctx.settings().isAutoLoginEnabled();
    }

    /**
     * Asks the bound QQ to approve a login from an unregistered device fingerprint.
     *
     * @return true when the login is now waiting for the reply; false means "fall back to limbo"
     */
    boolean requestDeviceApproval(Player player,
                                  ConnectionLifecycleRegistry.Operation operation,
                                  String fingerprint,
                                  Runnable onTimeout) {
        if (!isDeviceApprovalEnabled() || player == null || operation == null
                || player.isOnlineMode() || fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        try {
            QqDao dao = parent.dao();
            String uuid = player.getUniqueId().toString();
            long now = System.currentTimeMillis();

            RegisteredPlayer account = parent.loadRegisteredPlayer(player);
            if (account == null || parent.requiresTotp(account)) {
                return false;
            }
            Optional<QqBinding> binding = dao.findByUuid(QqAuthService.PLATFORM_QQ, uuid);
            if (binding.isEmpty()) {
                return false;
            }
            // A first login has no device row at all: nothing to compare, no prompt.
            if (dao.findUsableRememberTokenByUuid(uuid, now).isEmpty()) {
                return false;
            }
            String hash = DeviceFingerprints.hash(uuid, fingerprint);
            Optional<QqRememberToken> matching = dao.findRememberToken(hash);
            if (matching.isPresent() && matching.get().isUsable(now)
                    && matching.get().getUuid().equals(uuid)) {
                return false; // the fingerprint matches; the caller should have been authorized
            }

            String qqId = binding.get().getQqId();
            int ttlSeconds = qqSettings.getDeviceApprovalTimeoutSeconds();
            String approvalId = UUID.randomUUID().toString().replace("-", "");
            PendingDeviceApproval approval = new PendingDeviceApproval(
                    QqAuthService.PLATFORM_QQ, qqId, approvalId, player.getUniqueId(), operation,
                    fingerprint, now + ttlSeconds * 1000L);
            deviceApprovals.put(approval);
            if (!scheduleDeviceApprovalTimeout(approval, onTimeout)) {
                deviceApprovals.consume(QqAuthService.PLATFORM_QQ, qqId);
                return false;
            }
            queueDeviceApprovalNotify(player, qqId, ttlSeconds, approvalId);
            player.sendMessage(ctx.messages().component(
                    "qq.device_approval.pending", NamedTextColor.YELLOW, ttlSeconds));
            if (logger.isInfoEnabled()) {
                logger.info(SECURITY_MARKER,
                        "New-device QQ approval requested for {} (ttl={}s)",
                        player.getUsername(), ttlSeconds);
            }
            return true;
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "New-device approval unavailable for {}",
                    player.getUsername());
            return false;
        }
    }

    /**
     * Applies the group reply to a pending new-device approval. The bot forwards the replier's
     * own QQ id together with the approvalId embedded in the notification; the pending entry is
     * consumed exactly once and only by the bound QQ, and the binding, the live connection
     * generation and the limbo location are re-validated.
     */
    QqResult decideDeviceApproval(String platform, String groupId, String qqId,
                                  String decision, String approvalId) {
        if (!isDeviceApprovalEnabled()) {
            return QqResult.DISABLED;
        }
        if (isBlank(qqId) || isBlank(decision) || isBlank(approvalId)) {
            return QqResult.INVALID_CODE;
        }
        Boolean approve = parseApprovalDecision(decision);
        if (approve == null) {
            return QqResult.INVALID_DECISION;
        }

        // Ownership gate before consuming: a stranger quoting the notification must not be able
        // to deny (or approve) somebody else's login.
        Optional<PendingDeviceApproval> pendingOpt =
                deviceApprovals.peekByApprovalId(approvalId.trim());
        if (pendingOpt.isEmpty()) {
            return QqResult.NO_PENDING_APPROVAL;
        }
        PendingDeviceApproval pending = pendingOpt.get();
        try {
            Optional<QqBinding> binding =
                    parent.dao().findByUuid(pending.platform(), pending.uuid().toString());
            if (binding.isEmpty() || !binding.get().getQqId().equals(qqId.trim())) {
                logger.warn(SECURITY_MARKER,
                        "Rejected device-approval reply from non-owner QQ for UUID {}",
                        pending.uuid());
                return QqResult.FORBIDDEN;
            }
        } catch (SQLException e) {
            return QqResult.ERROR;
        }

        Optional<PendingDeviceApproval> consumed =
                deviceApprovals.consumeByApprovalId(approvalId.trim());
        if (consumed.isEmpty()) {
            return QqResult.NO_PENDING_APPROVAL;
        }
        cancelDeviceApprovalTask(pending.qqId());

        if (pending.isExpired(System.currentTimeMillis())) {
            return QqResult.CODE_EXPIRED;
        }

        Player player = plugin.getServer().getPlayer(pending.uuid()).orElse(null);
        if (player == null || !player.isActive()
                || !plugin.getConnectionLifecycleRegistry().isCurrent(pending.operation())
                || !plugin.getConnectionManager().isPlayerOnAuthServer(player)) {
            return QqResult.NOT_ONLINE;
        }
        if (parent.alreadyAuthorized(player)) {
            return QqResult.ALREADY_AUTHORIZED;
        }
        return approve ? approveDevice(pending, player) : denyDevice(pending, player);
    }

    /**
     * Cancels a pending approval when the connection disappears before the group replies.
     */
    void cancelDeviceApproval(Player player) {
        if (player == null) {
            return;
        }
        deviceApprovals.consumeByUuid(player.getUniqueId())
                .ifPresent(approval -> cancelDeviceApprovalTask(approval.qqId()));
    }

    private QqResult approveDevice(PendingDeviceApproval pending, Player player) {
        parent.authorizeDevice(player);
        parent.rememberDevice(player);
        boolean applied = plugin.getConnectionLifecycleRegistry().runIfCurrent(
                pending.operation(), () -> {
                    player.sendMessage(ctx.messages().component(
                            "qq.device_approval.approved", NamedTextColor.GREEN));
                    plugin.getConnectionManager().autoTransferFromAuthServerToBackend(player);
                });
        if (!applied) {
            return QqResult.NOT_ONLINE;
        }
        parent.audit(AuditEventType.LOGIN_OK, player.getUsername(), "device-approval");
        if (logger.isInfoEnabled()) {
            logger.info(SECURITY_MARKER, "New device approved over QQ for {}",
                    player.getUsername());
        }
        return QqResult.OK;
    }

    private QqResult denyDevice(PendingDeviceApproval pending, Player player) {
        // Drop the old device row so the next connection behaves like a first login and goes
        // through the normal password/QQ login instead of prompting forever.
        parent.revokeRememberTokens(pending.uuid());
        boolean applied = plugin.getConnectionLifecycleRegistry().runIfCurrent(
                pending.operation(), () -> player.disconnect(ctx.messages().component(
                        "qq.device_approval.denied", NamedTextColor.RED)));
        if (!applied) {
            return QqResult.NOT_ONLINE;
        }
        parent.audit(AuditEventType.LOGIN_FAIL, player.getUsername(), "device-approval-denied");
        if (logger.isInfoEnabled()) {
            logger.info(SECURITY_MARKER, "New device denied over QQ for {}",
                    player.getUsername());
        }
        return QqResult.OK;
    }

    private boolean scheduleDeviceApprovalTimeout(PendingDeviceApproval approval,
                                                  Runnable onTimeout) {
        cancelDeviceApprovalTask(approval.qqId());
        try {
            ScheduledTask task = plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> {
                        deviceApprovalTasks.remove(approval.qqId());
                        if (deviceApprovals.consume(approval.platform(), approval.qqId()).isEmpty()) {
                            return;
                        }
                        if (onTimeout != null) {
                            onTimeout.run();
                        }
                    })
                    .delay(Math.max(1, qqSettings.getDeviceApprovalTimeoutSeconds()),
                            TimeUnit.SECONDS)
                    .schedule();
            deviceApprovalTasks.put(approval.qqId(), task);
            return true;
        } catch (RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Failed to schedule new-device approval timeout");
            return false;
        }
    }

    private void cancelDeviceApprovalTask(String qqId) {
        if (qqId == null) {
            return;
        }
        ScheduledTask task = deviceApprovalTasks.remove(qqId);
        if (task != null) {
            task.cancel();
        }
    }

    private void queueDeviceApprovalNotify(Player player, String qqId, int ttlSeconds,
                                           String approvalId) {
        String brand = player.getClientBrand();
        if (brand == null || brand.isBlank()) {
            brand = "unknown";
        }
        String message = "[YouShuiAuth] 账号 " + player.getUsername()
                + " 新设备登录请求：" + brand
                + " / 版本 " + protocolLabel(player.getProtocolVersion())
                + " / IP 段 " + DeviceFingerprints.subnetLabel(
                        PlayerAddressUtils.getPlayerIp(player))
                + "，有效期 " + ttlSeconds + " 秒。确认本人请引用本条消息回复 Y，拒绝回复 N。";
        parent.enqueueNotification(new QqNotify(qqId, message, QqNotify.TYPE_DEVICE_APPROVAL,
                approvalId));
    }

    private static Boolean parseApprovalDecision(String decision) {
        return switch (decision.trim().toLowerCase(Locale.ROOT)) {
            case "y", "yes", "approve", "allow" -> Boolean.TRUE;
            case "n", "no", "deny", "refuse" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Enqueues an auto-login notification for the bound QQ; the bot handles routing. The
     * notification carries an opaque stopId so the owner can quote-reply {@code 阻止} to kick
     * the session and revoke device trust.
     */
    void queueAutoLoginNotify(Player player, String remainingTime) {
        try {
            String uuid = player.getUniqueId().toString();
            Optional<QqBinding> binding = parent.dao().findByUuid(
                    QqAuthService.PLATFORM_QQ, uuid);
            if (binding.isEmpty()) {
                return;
            }
            String stopId = UUID.randomUUID().toString().replace("-", "");
            long expiresAt = System.currentTimeMillis()
                    + qqSettings.getDeviceApprovalTimeoutSeconds() * 1000L;
            stopRequests.put(new QqStopRequests.StopRequest(
                    QqAuthService.PLATFORM_QQ, binding.get().getQqId(), player.getUniqueId(),
                    stopId, expiresAt));
            String message = "[YouShuiAuth] 账号 " + player.getUsername()
                    + " 刚刚通过 Cookie 自动登录，免密剩余 " + remainingTime
                    + "。若非本人操作，请引用本条消息回复「阻止」撤销免密（"
                    + qqSettings.getDeviceApprovalTimeoutSeconds() + " 秒内有效）。";
            parent.enqueueNotification(new QqNotify(binding.get().getQqId(), message,
                    QqNotify.TYPE_AUTO_LOGIN, stopId));
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Failed to queue auto-login notify for {}",
                    player.getUsername());
        }
    }

    /**
     * Applies the quote-reply {@code 阻止} to a cookie auto-login notification: kicks the
     * online player and revokes the account's device records. The replier must be the bound
     * QQ; a rejected reply does not consume the request.
     */
    QqResult stopAutoLogin(String platform, String groupId, String qqId, String stopId) {
        if (!parent.isEnabled()) {
            return QqResult.DISABLED;
        }
        if (isBlank(qqId) || isBlank(stopId)) {
            return QqResult.INVALID_CODE;
        }

        Optional<QqStopRequests.StopRequest> requestOpt = stopRequests.peek(stopId);
        if (requestOpt.isEmpty()) {
            return QqResult.NO_PENDING_APPROVAL;
        }
        QqStopRequests.StopRequest request = requestOpt.get();
        try {
            Optional<QqBinding> binding =
                    parent.dao().findByUuid(request.platform(), request.uuid().toString());
            if (binding.isEmpty() || !binding.get().getQqId().equals(qqId.trim())) {
                logger.warn(SECURITY_MARKER,
                        "Rejected auto-login stop from non-owner QQ for UUID {}",
                        request.uuid());
                return QqResult.FORBIDDEN;
            }
        } catch (SQLException e) {
            return QqResult.ERROR;
        }

        Optional<QqStopRequests.StopRequest> consumed = stopRequests.consume(stopId);
        if (consumed.isEmpty()) {
            return QqResult.NO_PENDING_APPROVAL;
        }
        if (request.isExpired(System.currentTimeMillis())) {
            return QqResult.CODE_EXPIRED;
        }

        parent.revokeRememberTokens(request.uuid());
        Player player = plugin.getServer().getPlayer(request.uuid()).orElse(null);
        if (player != null && player.isActive()) {
            player.disconnect(ctx.messages().component(
                    "qq.auto_login.stopped_kick", NamedTextColor.RED));
        }
        parent.audit(AuditEventType.LOGIN_FAIL,
                player != null ? player.getUsername() : request.uuid().toString(),
                "auto-login-stopped");
        if (logger.isInfoEnabled()) {
            logger.info(SECURITY_MARKER, "Cookie auto-login stopped over QQ for {}",
                    request.uuid());
        }
        return QqResult.OK;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Human-readable client version for notifications, e.g. {@code 1.21.4 (767)}.
     * Unknown/legacy protocols fall back to the raw protocol number instead of a blank label.
     */
    static String protocolLabel(com.velocitypowered.api.network.ProtocolVersion protocolVersion) {
        if (protocolVersion == null || protocolVersion.isUnknown()) {
            return "unknown";
        }
        if (protocolVersion.isLegacy()) {
            return "legacy (" + protocolVersion.getProtocol() + ")";
        }
        String version = protocolVersion.getMostRecentSupportedVersion();
        return version == null || version.isBlank()
                ? String.valueOf(protocolVersion.getProtocol())
                : version + " (" + protocolVersion.getProtocol() + ")";
    }
}
