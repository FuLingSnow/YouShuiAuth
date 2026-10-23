package io.github.fulingsnow.youshuiauth.runtime;

import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.auth.AutoLoginService;
import io.github.fulingsnow.youshuiauth.auth.ConflictModeService;
import io.github.fulingsnow.youshuiauth.auth.totp.PendingTotpStore;
import io.github.fulingsnow.youshuiauth.auth.totp.TotpReplayGuard;
import io.github.fulingsnow.youshuiauth.auth.totp.TotpService;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseConfig;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.database.DatabaseType;
import io.github.fulingsnow.youshuiauth.database.HikariConfigParams;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;
import io.github.fulingsnow.youshuiauth.qq.QqAuthFlow;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.platform.ConnectionOps;
import io.github.fulingsnow.youshuiauth.platform.PlatformAccess;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.platform.ReportOps;
import io.github.fulingsnow.youshuiauth.platform.TimeoutOps;
import io.github.fulingsnow.youshuiauth.platform.TransferOps;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 平台无关的服务装配：按 Settings → Messages → Database → Audit → TOTP → Cache 顺序构建，
 * 与 Velocity 端初始化保持一致，便于两个平台共享同一套认证状态机。
 */
public final class AuthRuntime implements AuthServices {

    private final Path dataDirectory;
    private final Logger logger;
    private final PlatformAccess platformAccess;
    private final TransferOps transfers;
    private final QqOps requestedQq;
    private final QqOps qq;
    private final ReportOps reports;

    private final Settings settings;
    private final Messages messages;
    private final DatabaseManager databaseManager;
    private final AuditLogService auditLogService;
    private final TotpService totpService;
    private final TotpReplayGuard totpReplayGuard;
    private final PendingTotpStore pendingTotpStore;
    private final AuthCache authCache;
    private final ConnectionOps connections;
    private final TimeoutOps timeouts;
    private final AutoLoginService autoLogin;
    private final io.github.fulingsnow.youshuiauth.auth.DeviceApprovalFlow deviceApprovalFlow;
    private final SessionExpiryFlow sessionExpiry;
    private io.github.fulingsnow.youshuiauth.platform.PlatformScheduler.TaskHandle heartbeatHandle;

    public AuthRuntime(Path dataDirectory, Logger logger, PlatformAccess platformAccess,
                       TransferOps transfers, QqOps qq, ReportOps reports,
                       Consumer<UUID> sessionExpired) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.platformAccess = platformAccess;
        this.transfers = transfers;
        this.requestedQq = qq;
        this.reports = reports;

        this.settings = new Settings(dataDirectory);
        if (!settings.load()) {
            throw new IllegalStateException("Failed to load configuration from " + dataDirectory);
        }
        this.messages = loadMessages(settings.getLanguage());
        this.databaseManager = new DatabaseManager(createDatabaseConfig(), messages);
        if (!databaseManager.initialize().join()) {
            databaseManager.shutdown();
            throw new IllegalStateException("Failed to initialize the authentication database");
        }

        Settings.AuditLogSettings auditSettings = settings.getAuditLogSettings();
        this.auditLogService = new AuditLogService(databaseManager.getAuditLogDao(),
                auditSettings.isEnabled());
        if (auditSettings.isEnabled()) {
            int retentionDays = auditSettings.getRetentionDays();
            platformAccess.scheduler().scheduleRepeating(
                    () -> {
                        try {
                            int pruned = auditLogService.prune(retentionDays);
                            if (pruned > 0) {
                                logger.info("审计日志清理：移除 {} 条超过 {} 天的记录",
                                        pruned, retentionDays);
                            }
                        } catch (RuntimeException e) {
                            logger.error("审计日志清理失败", e);
                        }
                    }, Duration.ofHours(1), Duration.ofHours(24));
        }

        try {
        Settings.TwoFactorSettings twoFactorSettings = settings.getTwoFactorSettings();
        this.totpService = new TotpService();
        this.totpReplayGuard = new TotpReplayGuard();
        this.pendingTotpStore = new PendingTotpStore(
                Duration.ofSeconds(twoFactorSettings.getPendingTimeoutSeconds()), auditLogService);

        this.authCache = new AuthCache(new AuthCache.AuthCacheConfig(
                settings.getCacheTtlMinutes(),
                settings.getCacheMaxSize(),
                settings.getCacheMaxSessions(),
                settings.getCacheMaxPremium(),
                settings.getBruteForceMaxAttempts(),
                settings.getBruteForceTimeoutMinutes(),
                settings.getCacheCleanupIntervalMinutes(),
                settings.getSessionTimeoutMinutes()), settings, messages);
        databaseManager.setAuthCacheReference(authCache);

        this.connections = new UuidConnectionRegistry();
        this.timeouts = new SchedulerTimeoutOps(platformAccess.scheduler(),
                Duration.ofSeconds(Math.max(1, settings.getAuthServerTimeoutSeconds())),
                sessionExpired);
        this.qq = requestedQq != null ? requestedQq : buildDefaultQq();
        this.autoLogin = new AutoLoginService(settings, authCache, databaseManager::getQqDao,
                databaseManager, platformAccess.platformId(), logger, messages,
                auditLogService, timeouts);
        this.autoLogin.setDeviceApprovalFlow(this::deviceApprovalFlow);
        this.deviceApprovalFlow = buildDeviceApprovalFlow();
        this.sessionExpiry = new SessionExpiryFlow(authCache, messages);
        } catch (RuntimeException e) {
            databaseManager.shutdown();
            throw e;
        }
    }

    public AutoLoginService autoLogin() {
        return autoLogin;
    }

    public SessionExpiryFlow sessionExpiry() {
        return sessionExpiry;
    }

    public io.github.fulingsnow.youshuiauth.auth.DeviceApprovalFlow deviceApprovalFlow() {
        return deviceApprovalFlow;
    }

    private io.github.fulingsnow.youshuiauth.auth.DeviceApprovalFlow buildDeviceApprovalFlow() {
        QqSettings qqSettings = settings.getQqSettings();

        if (!qqSettings.isEnabled()) {
            return null;
        }
        return new io.github.fulingsnow.youshuiauth.auth.DeviceApprovalFlow(qqSettings, settings,
                databaseManager, databaseManager::getQqDao, platformAccess.platformId(), logger);
    }

    private QqOps buildDefaultQq() {
        QqSettings qqSettings = settings.getQqSettings();
        if (!qqSettings.isEnabled()) {

            return io.github.fulingsnow.youshuiauth.qq.DisabledQqOps.INSTANCE;
        }
        return new QqAuthFlow(qqSettings, settings, databaseManager::getQqDao,
                timeouts, logger, platformAccess.platformId(), authCache, databaseManager);
    }

    private Messages loadMessages(String language) {
        try {
            return new Messages(dataDirectory, language);
        } catch (IOException e) {
            logger.warn("回退到内置语言文件", e);
            Messages fallback = new Messages();
            fallback.setLanguage(language);
            return fallback;
        }
    }

    private DatabaseConfig createDatabaseConfig() {
        String storageType = settings.getDatabaseStorageType();
        DatabaseType dbType = DatabaseType.fromName(storageType);
        if (dbType != null && dbType.isLocalDatabase()) {
            return DatabaseConfig.forLocalDatabaseWithMigration(
                    storageType, settings.getDatabaseName(), settings.getDataDirectory(), logger);
        }
        return DatabaseConfig.forRemoteWithHikari(HikariConfigParams.builder()
                .storageType(storageType)
                .hostname(settings.getDatabaseHostname())
                .port(settings.getDatabasePort())
                .database(settings.getDatabaseName())
                .user(settings.getDatabaseUser())
                .password(settings.getDatabasePassword())
                .connectionPoolSize(settings.getDatabaseConnectionPoolSize())
                .maxLifetime((int) settings.getDatabaseMaxLifetimeMillis())
                .connectionParameters(settings.getDatabaseConnectionParameters())
                .postgreSQLSettings(settings.getPostgreSQLSettings())
                .debugEnabled(settings.isDebugEnabled())
                .build());
    }

    public void startInstanceHeartbeat(String deploymentId, String version) {
        if (deploymentId == null || deploymentId.isBlank()) {
            return;
        }
        Runnable beat = () -> databaseManager.upsertInstanceHeartbeat(
                deploymentId.trim(), platformAccess.platformId(), version);
        beat.run();
        heartbeatHandle = platformAccess.scheduler().scheduleRepeating(
                beat, Duration.ofMinutes(5), Duration.ofMinutes(5));
    }

    public void shutdown() {
        if (heartbeatHandle != null) {
            heartbeatHandle.cancel();
        }

        authCache.shutdown();
        databaseManager.shutdown();
    }

    public ConflictModeService conflictModeService() {
        return new ConflictModeService(databaseManager,
                settings.captureOperationSettings().bruteForce().conflictModeTtlHours());
    }

    /**
     * 「已授权且会话仍有效」的组合判定。
     *
     * <p>与 {@link #shouldRestoreSession} 的区别：本方法不看 {@code session-cache-enabled}
     * 开关，用于「这个玩家现在算不算已登录」这类与断线恢复策略无关的判定。</p>
     */
    public static boolean isAuthorizedWithSession(AuthCache authCache,
                                                  io.github.fulingsnow.youshuiauth.platform.AuthPlayer player,
                                                  String playerIp) {
        return authCache.isPlayerAuthorized(player.uniqueId(), playerIp)
                && authCache.hasActiveSession(player.uniqueId(), player.username(), playerIp);
    }

    /**
     * 断线重连的会话恢复判定（统一入口）：开关 + 授权缓存 + 活跃会话三者同查。
     * 各端 join/重连路径一律调用它，避免开关逻辑在多处手工组合时漏接或口径分裂。
     * IP 由调用方传入（各端有自己的提取规则），保证与缓存写入时口径一致。
     */
    public static boolean shouldRestoreSession(Settings settings, AuthCache authCache,
                                               io.github.fulingsnow.youshuiauth.platform.AuthPlayer player,
                                               String playerIp) {
        if (!settings.isSessionCacheEnabled()) {
            return false;
        }
        return authCache.isPlayerAuthorized(player.uniqueId(), playerIp)
                && authCache.hasActiveSession(player.uniqueId(), player.username(), playerIp);
    }

    public void sendLoginPrompt(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        player.sendMessage(messages.component("auth.prompt.generic",
                net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }

    public void sendAuthSuccess(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        player.sendMessage(messages.component("auth.login.success",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    public void sendPermissionDenied(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        player.sendMessage(messages.component("error.permission",
                net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    @Override
    public DatabaseManager databaseManager() {
        return databaseManager;
    }

    @Override
    public AuthCache authCache() {
        return authCache;
    }

    @Override
    public Settings settings() {
        return settings;
    }

    @Override
    public Messages messages() {
        return messages;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public AuditLogService auditLogService() {
        return auditLogService;
    }

    @Override
    public TotpService totpService() {
        return totpService;
    }

    @Override
    public PendingTotpStore pendingTotpStore() {
        return pendingTotpStore;
    }

    @Override
    public TotpReplayGuard totpReplayGuard() {
        return totpReplayGuard;
    }

    @Override
    public PlatformAccess platformAccess() {
        return platformAccess;
    }

    @Override
    public ConnectionOps connections() {
        return connections;
    }

    @Override
    public TimeoutOps timeouts() {
        return timeouts;
    }

    @Override
    public TransferOps transfers() {
        return transfers;
    }

    @Override
    public ReportOps reports() {
        return reports;
    }

    @Override
    public QqOps qq() {
        return qq;
    }

    @Override
    public boolean reloadConfig() {
        return settings.load();
    }
}
