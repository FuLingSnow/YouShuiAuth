package io.github.fulingsnow.youshuiauth.qq;

import io.github.fulingsnow.youshuiauth.util.FloodgateDetector;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.model.QqLinkCode;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.platform.TimeoutOps;
import io.github.fulingsnow.youshuiauth.util.PlatformFingerprints;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端平台（无客户端设置 API）使用的 QQ 数据流程：绑定码、手动登录码、设备记忆。
 * 代理端保留自己的实现（设备指纹 v1 与 limbo 窗口逻辑不同），两端共享同一套表。
 */
public final class QqAuthFlow implements QqOps {

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");
    private static final int BIND_CODE_LENGTH = 8;

    private final QqSettings settings;
    private final Settings coreSettings;
    private final Supplier<QqDao> daoSupplier;
    private final TimeoutOps timeouts;
    private final Logger logger;
    private final String platformTag;
    private final AuthCache authCache;
    private final DatabaseManager databaseManager;

    private final QqLoginChallenges loginChallenges;
    private final QqDeviceApprovals deviceApprovals;
    private final Cache<UUID, ActiveBindCode> activeBindCodes;
    private final Cache<UUID, Boolean> bindCodeCooldown;
    private final Cache<UUID, Boolean> loginCodeCooldown;

    public static final String QQ_ONLY_HASH = "!qq-only";

    public QqAuthFlow(QqSettings settings, Settings coreSettings, Supplier<QqDao> daoSupplier,
                      TimeoutOps timeouts, Logger logger, String platformTag,
                      AuthCache authCache, DatabaseManager databaseManager) {
        this.settings = settings;
        this.coreSettings = coreSettings;
        this.daoSupplier = daoSupplier;
        this.timeouts = timeouts;
        this.logger = logger;
        this.platformTag = platformTag;
        this.authCache = authCache;
        this.databaseManager = databaseManager;
        this.loginChallenges = new QqLoginChallenges(settings.getLoginCodeTtlSeconds());
        this.deviceApprovals = new QqDeviceApprovals(settings.getDeviceApprovalTimeoutSeconds());
        this.activeBindCodes = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(settings.getBindCodeTtlSeconds()))
                .maximumSize(10_000)
                .build();
        this.bindCodeCooldown = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(5))
                .maximumSize(100_000)
                .build();
        this.loginCodeCooldown = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(3))
                .maximumSize(100_000)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return settings.isEnabled() && dao() != null;
    }

    @Override
    public String getJoinUrl() {
        return settings.getJoinUrl();
    }

    @Override
    public int getLoginCodeTtlSeconds() {
        return settings.getLoginCodeTtlSeconds();
    }

    @Override
    public int getDeviceApprovalTimeoutSeconds() {
        return settings.getDeviceApprovalTimeoutSeconds();
    }

    /** 该 QQ 号是否已绑定任意账号（桥接在消费码之前的短路判定）。 */
    public boolean isQqBound(String qqId) {
        if (qqId == null || qqId.isBlank()) {
            return false;
        }
        try {
            QqDao dao = dao();
            return dao != null && dao.findByQq(PLATFORM_QQ, qqId.trim()).isPresent();
        } catch (SQLException e) {
            logger.warn(SECURITY_MARKER, "Failed to inspect QQ binding for {}", qqId);
            return false;
        }
    }

    @Override
    public boolean isAccountQqBound(AuthPlayer player) {
        if (!isEnabled() || player == null) {
            return false;
        }
        try {
            return dao().findByUuid(PLATFORM_QQ, player.uniqueId().toString()).isPresent();
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Failed to inspect QQ binding for {}", player.username());
            return false;
        }
    }

    @Override
    public boolean isDeviceApprovalPending(AuthPlayer player) {
        return player != null && deviceApprovals.peekByUuid(player.uniqueId()).isPresent();
    }

    @Override
    public BindCodeIssue createBindCodeOutcome(AuthPlayer player) {
        if (!isEnabled()) {
            return new BindCodeIssue(QqResult.DISABLED, null, 0, false);
        }
        if (player == null) {
            return new BindCodeIssue(QqResult.NOT_AUTHENTICATED, null, 0, false);
        }
        String uuid = player.uniqueId().toString();
        QqDao dao = dao();
        try {
            if (dao.findByUuid(PLATFORM_QQ, uuid).isPresent()) {
                return new BindCodeIssue(QqResult.ALREADY_BOUND, null, 0, false);
            }
        } catch (SQLException e) {
            logger.warn(SECURITY_MARKER, "Failed to inspect QQ binding for UUID {}", uuid);
            return new BindCodeIssue(QqResult.ERROR, null, 0, false);
        }

        boolean registration = false;
        if (!isAuthenticatedForBinding(player)) {
            QqResult gate = registrationGate(player);
            if (gate != null) {
                return new BindCodeIssue(gate, null, 0, false);
            }
            registration = true;
        }

        if (bindCodeCooldown.asMap().putIfAbsent(player.uniqueId(), Boolean.TRUE) != null) {
            return new BindCodeIssue(QqResult.RATE_LIMITED, null, 0, false);
        }

        long now = System.currentTimeMillis();
        int ttlSeconds = settings.getBindCodeTtlSeconds();
        String purpose = registration ? QqLinkCode.PURPOSE_REGISTER : QqLinkCode.PURPOSE_BIND;
        try {
            ActiveBindCode active = activeBindCodes.getIfPresent(player.uniqueId());
            if (active != null && active.expiresAt() > now) {
                Optional<QqLinkCode> stored = dao.findLinkCode(QqLinkCodes.hash(active.code()));
                if (stored.isPresent() && !stored.get().isConsumed()
                        && CrossDeploymentClock.isUsable(stored.get().getExpiresAt(), now)) {
                    int remaining = (int) Math.max(1, (active.expiresAt() - now) / 1000);
                    timeouts.renewForCode(player, remaining);
                    return new BindCodeIssue(QqResult.OK, active.code(), remaining, registration);
                }
            }

            activeBindCodes.invalidate(player.uniqueId());
            String code = QqLinkCodes.generate(BIND_CODE_LENGTH);
            long expiresAt = now + ttlSeconds * 1000L;
            dao.deleteLinkCodesByUuidAndPurpose(uuid, purpose);
            dao.insertLinkCode(new QqLinkCode(QqLinkCodes.hash(code), uuid, purpose, now, expiresAt));
            activeBindCodes.put(player.uniqueId(), new ActiveBindCode(code, purpose, expiresAt));
            timeouts.renewForCode(player, ttlSeconds);
            return new BindCodeIssue(QqResult.OK, code, ttlSeconds, registration);
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to issue QQ bind code for UUID {}", uuid, e);
            return new BindCodeIssue(QqResult.ERROR, null, 0, false);
        }
    }

    private boolean isAuthenticatedForBinding(AuthPlayer player) {
        String ip = player.address() == null ? "unknown" : player.address().getHostAddress();
        if (authCache.isPlayerAuthorized(player.uniqueId(), ip)) {
            return true;
        }
        if (player.onlineMode()) {
            return true;
        }
        return coreSettings.isFloodgateIntegrationEnabled()
                && FloodgateDetector.isBedrockPlayer(player.uniqueId());
    }

    private QqResult registrationGate(AuthPlayer player) {
        var result = databaseManager.findPlayerByNickname(player.username()).join();
        if (result == null || result.isDatabaseError()) {
            return QqResult.ERROR;
        }
        if (result.getValue() == null) {
            return null;
        }
        return isOrphanedQqOnlyAccount(result.getValue()) ? null : QqResult.ACCOUNT_EXISTS;
    }

    public boolean isOrphanedQqOnlyAccount(io.github.fulingsnow.youshuiauth.model.RegisteredPlayer account) {
        if (!QQ_ONLY_HASH.equals(account.getHash())) {
            return false;
        }
        try {
            return dao().findByUuid(PLATFORM_QQ, account.getUuid()).isEmpty();
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to inspect orphaned QQ-only account {}",
                    account.getNickname());
            return false;
        }
    }

    @Override
    public Optional<String> issueLoginCodeManual(AuthPlayer player, ConnectionRef operation) {
        if (!isEnabled() || player == null || operation == null) {
            return Optional.empty();
        }
        if (loginCodeCooldown.asMap().putIfAbsent(player.uniqueId(), Boolean.TRUE) != null) {
            return Optional.empty();
        }
        Optional<String> code = loginChallenges.issue(player.uniqueId(), operation);
        code.ifPresent(ignored -> timeouts.renewForCode(player, loginWindowSeconds()));
        return code;
    }

    @Override
    public Optional<QqLoginChallenges.Challenge> peekLoginCode(String code) {
        return loginChallenges.peek(QqLinkCodes.normalize(code));
    }

    @Override
    public Optional<QqLoginChallenges.Challenge> consumeLoginCode(String code) {
        if (!isEnabled() || code == null || code.isBlank()) {
            return Optional.empty();
        }
        return loginChallenges.consume(QqLinkCodes.normalize(code));
    }

    @Override
    public void rememberDevice(AuthPlayer player) {
        if (!isEnabled() || player == null || player.onlineMode()) {
            return;
        }
        try {
            boolean includeSubnet = coreSettings.isAutoLoginRequireSameSubnet();
            String fingerprint = PlatformFingerprints.fingerprint(player, includeSubnet, platformTag);
            String hash = PlatformFingerprints.hash(player.uniqueId().toString(), fingerprint);
            int maxAgeDays = Math.max(1, coreSettings.getAutoLoginMaxAgeDays());
            long now = System.currentTimeMillis();
            QqDao dao = dao();
            dao.deleteRememberTokensByUuidAndPlatform(
                    player.uniqueId().toString(), platformTag);
            dao.insertRememberToken(new QqRememberToken(
                    hash, player.uniqueId().toString(), platformTag,
                    now, now + maxAgeDays * 86_400_000L));
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Device record skipped for {}", player.username());
        }
    }

    @Override
    public void revokeRememberTokens(UUID playerId) {
        if (playerId == null || !isEnabled()) {
            return;
        }
        try {
            dao().deleteRememberTokensByUuid(playerId.toString());
        } catch (SQLException e) {
            logger.warn(SECURITY_MARKER, "Failed to revoke device record for UUID {}", playerId);
        }
    }

    public int loginWindowSeconds() {
        int loginTtl = settings.getLoginCodeTtlSeconds();
        int totpPending = coreSettings.getTwoFactorSettings().pendingTimeoutSeconds();
        return Math.max(loginTtl, totpPending);
    }

    private QqDao dao() {
        return daoSupplier.get();
    }

    private record ActiveBindCode(String code, String purpose, long expiresAt) {
    }
}
