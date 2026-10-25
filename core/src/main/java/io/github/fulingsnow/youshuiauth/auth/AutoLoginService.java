package io.github.fulingsnow.youshuiauth.auth;

import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.TimeoutOps;
import io.github.fulingsnow.youshuiauth.qq.QqAuthFlow;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.util.PlatformFingerprints;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 设备指纹免密：代理端与服务端共用的唯一实现。
 *
 * <p>判定链路（开关 → 正版 → 账号 → 二因子 → 账号类型开关 → 设备记录 → 指纹），
 * 命中后的动作（写缓存、取消未认证超时、提示、审计）也在这里完成，
 * 平台只负责“成功后把玩家送去后端”或“失败后进隔离区/limbo”。</p>
 */
public final class AutoLoginService {

    /** 免密判定的结果。 */
    public enum Outcome {
        OK,
        DISABLED,
        ONLINE_MODE,
        NO_ACCOUNT,
        TOTP_REQUIRED,
        TYPE_NOT_ALLOWED,
        /** 没有任何可用的设备记录（首次登录 / 记录过期或已撤销）。 */
        NO_MATCHING_DEVICE,
        /** 有设备记录但指纹不匹配（换设备或换网段），可由 QQ 群内 Y/N 确认后放行。 */
        DEVICE_MISMATCH,
        ERROR
    }

    public record Result(Outcome outcome, long remainingMillis) {

        public boolean authorized() {
            return outcome == Outcome.OK;
        }

        static Result of(Outcome outcome) {
            return new Result(outcome, 0L);
        }
    }

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private final Settings settings;
    private final AuthCache cache;
    private final Supplier<QqDao> daoSupplier;
    private final DatabaseManager databaseManager;
    private final String platformTag;
    private final Logger logger;
    private final Messages messages;
    private final AuditLogService auditLog;
    private final TimeoutOps timeouts;
    private Supplier<DeviceApprovalFlow> deviceApprovalFlow;

    public AutoLoginService(Settings settings, AuthCache cache, Supplier<QqDao> daoSupplier,
                            DatabaseManager databaseManager, String platformTag, Logger logger,
                            Messages messages, AuditLogService auditLog, TimeoutOps timeouts) {
        this.settings = settings;
        this.cache = cache;
        this.daoSupplier = daoSupplier;
        this.databaseManager = databaseManager;
        this.platformTag = platformTag;
        this.logger = logger;
        this.messages = messages;
        this.auditLog = auditLog;
        this.timeouts = timeouts;
    }

    public void setDeviceApprovalFlow(Supplier<DeviceApprovalFlow> deviceApprovalFlow) {
        this.deviceApprovalFlow = deviceApprovalFlow;
    }

    public Result attempt(AuthPlayer player) {
        if (player == null || !settings.isAutoLoginEnabled()) {
            return Result.of(Outcome.DISABLED);
        }
        if (player.onlineMode()) {
            return Result.of(Outcome.ONLINE_MODE);
        }
        if (player.address() == null) {
            return Result.of(Outcome.ERROR);
        }
        QqDao dao = daoSupplier.get();
        if (dao == null) {
            return Result.of(Outcome.ERROR);
        }
        String uuid = player.uniqueId().toString();
        long now = System.currentTimeMillis();
        try {
            var account = databaseManager.findRegisteredPlayerByUuid(player.uniqueId());
            if (account == null) {
                return Result.of(Outcome.NO_ACCOUNT);
            }
            if (settings.getTwoFactorSettings().isEnabled()
                    && account.getTotpToken() != null && !account.getTotpToken().isBlank()) {
                return Result.of(Outcome.TOTP_REQUIRED);
            }
            boolean qqAccount = dao.findByUuid(QqAuthFlow.PLATFORM_QQ, uuid).isPresent();
            boolean allowed = qqAccount
                    ? settings.isAutoLoginQqEnabled()
                    : settings.isAutoLoginPasswordEnabled();
            if (!allowed) {
                return Result.of(Outcome.TYPE_NOT_ALLOWED);
            }

            Optional<QqRememberToken> stored =
                    dao.findUsableRememberTokenByUuidAndPlatform(uuid, now, platformTag);
            if (stored.isEmpty()) {
                return Result.of(Outcome.NO_MATCHING_DEVICE);
            }
            String hash = stored.get().getTokenHash();
            String currentV2 = hash(player, platformTag);
            String legacyV1 = hashLegacy(player);
            if (!currentV2.equals(hash) && !legacyV1.equals(hash)) {
                return Result.of(Outcome.DEVICE_MISMATCH);
            }
            if (!currentV2.equals(hash)) {
                upgradeLegacyRecord(dao, uuid, currentV2, stored.get(), now);
            }

            authorize(player, dao, stored.get(), now);
            return new Result(Outcome.OK, Math.max(0L, stored.get().getExpiresAt() - now));
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Auto-login check failed for {}", player.username());
            return Result.of(Outcome.ERROR);
        }
    }

    public void rememberDevice(AuthPlayer player) {
        if (player == null || player.onlineMode() || player.address() == null) {
            return;
        }
        QqDao dao = daoSupplier.get();
        if (dao == null) {
            return;
        }
        try {
            String uuid = player.uniqueId().toString();
            long now = System.currentTimeMillis();
            int maxAgeDays = Math.max(1, settings.getAutoLoginMaxAgeDays());
            dao.deleteRememberTokensByUuidAndPlatform(uuid, platformTag);
            dao.insertRememberToken(new QqRememberToken(hash(player, platformTag), uuid,
                    platformTag, now, now + maxAgeDays * 86_400_000L));
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Failed to persist device record for {}",
                    player.username());
        }
    }

    private void authorize(AuthPlayer player, QqDao dao, QqRememberToken token, long now)
            throws SQLException {
        String ip = player.address().getHostAddress();
        cache.authorize(player.uniqueId(),
                new CachedAuthUser(player.uniqueId(), player.username(), ip, now, false, null),
                player.username(), ip);
        timeouts.cancel(player.uniqueId());
        dao.touchRememberToken(token);
        player.sendMessage(messages.component("qq.login.remembered", NamedTextColor.GREEN));
        if (auditLog != null && auditLog.isEnabled()) {
            auditLog.save(AuditEventType.LOGIN_OK, player.username(), ip, "device-fingerprint");
        }
        logger.info(SECURITY_MARKER, "Auto-login restored for {} via stored device record",
                player.username());
        notifyOwner(player, Math.max(0L, token.getExpiresAt() - now));
    }

    /** 免密登录后通知绑定的 QQ 本人（可引用回复「阻止」撤销）。 */
    private void notifyOwner(AuthPlayer player, long remainingMillis) {
        DeviceApprovalFlow flow = deviceApprovalFlow == null ? null : deviceApprovalFlow.get();
        if (flow == null) {
            logger.warn(SECURITY_MARKER, "Auto-login notice skipped: device-approval flow unavailable");
            return;
        }
        String remaining = formatRemaining(remainingMillis);
        try {
            flow.notifyAutoLogin(player, remaining);
            logger.info(SECURITY_MARKER, "Auto-login notice queued for {} (remaining {})",
                    player.username(), remaining);
        } catch (RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Failed to queue auto-login notice for {}",
                    player.username());
        }
    }

    private static String formatRemaining(long remainingMillis) {
        long days = remainingMillis / 86_400_000L;
        long hours = (remainingMillis % 86_400_000L) / 3_600_000L;
        if (days > 0) {
            return hours > 0 ? days + " 天 " + hours + " 小时" : days + " 天";
        }
        return Math.max(hours, 1) + " 小时";
    }

    /** 代理端存量记录是 v1 口径；命中后就地升级，避免两端继续分叉。 */
    private void upgradeLegacyRecord(QqDao dao, String uuid, String v2Hash,
                                     QqRememberToken stored, long now) throws SQLException {
        dao.deleteRememberTokensByUuidAndPlatform(uuid, platformTag);
        dao.insertRememberToken(new QqRememberToken(v2Hash, uuid, platformTag, now,
                stored.getExpiresAt()));
        logger.info(SECURITY_MARKER, "Device record upgraded to v2 for {}", uuid);
    }

    private String hash(AuthPlayer player, String tag) {
        boolean includeSubnet = settings.isAutoLoginRequireSameSubnet();
        return PlatformFingerprints.hash(player.uniqueId().toString(),
                PlatformFingerprints.fingerprint(player, includeSubnet, tag));
    }

    private String hashLegacy(AuthPlayer player) {
        boolean includeSubnet = settings.isAutoLoginRequireSameSubnet();
        return PlatformFingerprints.hashV1(player.uniqueId().toString(),
                PlatformFingerprints.fingerprint(player, includeSubnet, null));
    }
}
