package io.github.fulingsnow.youshuiauth;

import jakarta.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.fulingsnow.youshuiauth.report.ReportService;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.command.CommandHandler;
import io.github.fulingsnow.youshuiauth.command.QqAuthService;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.connection.AuthTimeoutScheduler;
import io.github.fulingsnow.youshuiauth.connection.ConnectionManager;
import io.github.fulingsnow.youshuiauth.database.DatabaseConfig;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.database.DatabaseType;
import io.github.fulingsnow.youshuiauth.database.HikariConfigParams;
import io.github.fulingsnow.youshuiauth.exception.YouShuiAuthException;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.listener.AuthListener;
import io.github.fulingsnow.youshuiauth.listener.ListenerFactory;
import io.github.fulingsnow.youshuiauth.lifecycle.VelocityConnectionLifecycleRegistry;
import io.github.fulingsnow.youshuiauth.alert.PremiumResolverAlertService;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.auth.totp.PendingTotpStore;
import io.github.fulingsnow.youshuiauth.auth.totp.TotpReplayGuard;
import io.github.fulingsnow.youshuiauth.auth.totp.TotpService;
import io.github.fulingsnow.youshuiauth.authserver.AuthServerProvider;
import io.github.fulingsnow.youshuiauth.premium.PremiumResolverService;
import io.github.fulingsnow.youshuiauth.qq.QqBridgeServer;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.util.FloodgateDetector;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Velocity proxy authentication plugin.
 * Manages player auth flows between Velocity, a configurable auth server, and backend servers.
 */
@Plugin(
        id = "youshuiauth",
        name = "YouShuiAuth",
        version = BuildConstants.VERSION,
        description = "Complete Velocity Authentication Plugin with BCrypt, Virtual Threads and multi-database support",
    authors = {"FuLingSnow"},
    dependencies = {
        @Dependency(id = "floodgate", optional = true)
    }
)
public class YouShuiAuth implements io.github.fulingsnow.youshuiauth.platform.AuthServices {

    private static final int BSTATS_PLUGIN_ID = 28142;
    private static final long CLEANUP_SCHEDULER_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile io.github.fulingsnow.youshuiauth.platform.PlatformAccess platformAccess;
    private final Metrics.Factory metricsFactory;
    private final VelocityConnectionLifecycleRegistry connectionLifecycleRegistry =
            new VelocityConnectionLifecycleRegistry();

    private volatile Metrics metrics;

    private Settings settings;
    private Messages messages;
    private DatabaseManager databaseManager;
    private AuthCache authCache;
    private CommandHandler commandHandler;
    private ConnectionManager connectionManager;
    private AuthServerProvider authServerProvider;
    private AuthTimeoutScheduler authTimeoutScheduler;
    private io.github.fulingsnow.youshuiauth.auth.AutoLoginService autoLoginService;
    private QqBridgeServer qqBridgeServer;
    /**
     * Volatile: read by EarlyLoginBlocker from the initialization future's dependent stage.
     * The future's happens-before already covers the phase-8 assignment, but the field is
     * published safely on its own so no future call site has to re-derive that reasoning.
     */
    private volatile AuthListener authListener;
    private PremiumResolverService premiumResolverService;
    private PremiumResolverAlertService premiumResolverAlertService;
    private AuditLogService auditLogService;
    private TotpService totpService;
    private PendingTotpStore pendingTotpStore;
    private TotpReplayGuard totpReplayGuard;
    private io.github.fulingsnow.youshuiauth.report.ReportService reportService;
    private ScheduledExecutorService premiumDbCleanupScheduler;
    private ScheduledExecutorService qqCleanupScheduler;
    private ScheduledExecutorService auditLogCleanupScheduler;

    private volatile boolean initialized = false;
    private volatile boolean shutdownStarted = false;

    private final CompletableFuture<Void> initializationFuture = new CompletableFuture<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /**
     * Konstruktor YouShuiAuth.
     *
     * @param server        ProxyServer instance
     * @param logger        Logger instance
     * @param dataDirectory Path do data folder
     */
    @Inject
    public YouShuiAuth(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.metricsFactory = metricsFactory;

        migrateLegacyDataDirectory(dataDirectory, logger);
        logger.info("YouShuiAuth 实例已创建");

        if (logger.isDebugEnabled()) {
            logger.debug("YouShuiAuth 构造器 - Java {}，Velocity API {}",
                    System.getProperty("java.version"),
                    server.getVersion().getVersion());
        }
    }

    private static void migrateLegacyDataDirectory(Path dataDirectory, Logger logger) {
        try {
            // 首次安装时 @DataDirectory 指向的目录可能还不存在；
            // 先创建空目录，否则下面的「是否为空」判断会因为目录不存在而直接放弃迁移。
            Files.createDirectories(dataDirectory);
            try (var entries = Files.list(dataDirectory)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Path legacy = dataDirectory.getParent() == null
                    ? null : dataDirectory.getParent().resolve("veloauth");
            if (legacy == null || !Files.isDirectory(legacy)) {
                return;
            }
            try (var sources = Files.walk(legacy)) {
                for (Path source : sources.toList()) {
                    Path target = dataDirectory.resolve(legacy.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception migrationFailure) {
            logger.warn("遗留数据目录迁移失败，使用默认路径继续",
                    migrationFailure);
        }
    }

    private boolean reloadLanguageFiles() {
        try {

            String newLanguage = settings.getLanguage();

            messages.reloadWithLanguage(newLanguage);
            logger.info("语言文件重载成功（语言：{}）", newLanguage);
            return true;
        } catch (IOException e) {
            logger.error("重新加载语言文件失败", e);
            return false;
        }
    }

    /**
     * Inicjalizacja pluginu przy starcie proxy.
     */
    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logVersion();
        logEnvironment();

        if (!registerEarlyLoginBlocker()) {
            return;
        }

        long initializationStartTime = System.currentTimeMillis();

        CompletableFuture.runAsync(this::initializePlugin, VirtualThreadExecutorProvider.getVirtualExecutor())
                .whenComplete((result, throwable) -> handleInitializationResult(throwable, initializationStartTime));
    }

    private void logVersion() {
        try {
            logger.info("正在加载 YouShuiAuth v{}...", getVersion());
        } catch (Exception e) {
            logger.error("从 BuildConstants 读取版本失败", e);
            logger.info("正在加载 YouShuiAuth（版本未知）...");
        }
    }

    private void logEnvironment() {
        if (logger.isDebugEnabled()) {
            logger.debug("Java：{}，虚拟线程：{}",
                    System.getProperty("java.version"),
                    Thread.currentThread().isVirtual() ? "Available" : "Unavailable");
        }
    }

    private boolean registerEarlyLoginBlocker() {

        if (logger.isDebugEnabled()) {
            logger.debug("注册早期 PreLogin 拦截器以保护初始化过程...");
        }
        try {
            var earlyBlocker = ListenerFactory.createEarlyLoginBlocker(this);
            server.getEventManager().register(this, earlyBlocker);
            if (logger.isDebugEnabled()) {
                logger.debug("✅ EarlyLoginBlocker registered BEFORE initialization - PreLogin protection active");
            }
            return true;
        } catch (Exception e) {
            logger.error("注册早期 PreLogin 拦截器失败", e);
            return false;
        }
    }

    private void handleInitializationResult(Throwable throwable, long startTime) {
        long initializationDuration = System.currentTimeMillis() - startTime;

        if (throwable != null) {
            logger.error("❌ YouShuiAuth initialization FAILED after {} ms", initializationDuration, throwable);

            logger.warn("⚠️ Initialization flag remains FALSE - all player connections will be blocked");
            initializationFuture.completeExceptionally(throwable);
            shutdown();
        } else {
            finalizeInitialization(initializationDuration);
        }
    }

    private void finalizeInitialization(long initializationDuration) {

        if (databaseManager != null) {
            databaseManager.clearCache();
        }
        if (authCache != null) {
            authCache.clearAll();
        }

        lifecycleLock.lock();
        try {
            if (shutdownStarted) {
                logger.warn("关闭流程已开始，跳过初始化完成流程");
                return;
            }

            initialized = true;
            initializationFuture.complete(null);
        } finally {
            lifecycleLock.unlock();
        }

        initializeMetricsSafely();
        logStartupInfo(initializationDuration);
        scheduleEmbeddedProtocolUpdate();
    }

    private void scheduleEmbeddedProtocolUpdate() {
        AuthServerProvider provider = authServerProvider;
        if (provider == null || !provider.isProtocolRuntimeUpdateEnabled()) {
            return;
        }
        if (!VirtualThreadExecutorProvider.submitTask(provider::stageProtocolRuntimeUpdate)) {
            logger.debug("关闭期间跳过了内嵌协议运行时校验");
        }
    }

    private void initializeMetricsSafely() {
        Metrics candidate = null;
        try {
            candidate = Objects.requireNonNull(
                    metricsFactory.make(this, BSTATS_PLUGIN_ID),
                    "bStats metrics factory returned null");
            BStatsCharts.register(candidate, server, settings);
        } catch (RuntimeException e) {
            closeMetricsSafely(candidate);
            logger.warn("bStats 初始化失败，将在无统计数据的情况下继续运行", e);
            return;
        }

        boolean published;
        lifecycleLock.lock();
        try {
            published = !shutdownStarted && metrics == null;
            if (published) {
                metrics = candidate;
            }
        } finally {
            lifecycleLock.unlock();
        }

        if (!published) {
            closeMetricsSafely(candidate);
            logger.debug("插件正在停止或统计数据已存在，本次 bStats 初始化已丢弃");
        }
    }

    private void shutdownMetricsSafely() {
        Metrics activeMetrics;
        lifecycleLock.lock();
        try {
            activeMetrics = metrics;
            metrics = null;
        } finally {
            lifecycleLock.unlock();
        }
        closeMetricsSafely(activeMetrics);
    }

    private void closeMetricsSafely(Metrics metricsToClose) {
        if (metricsToClose == null) {
            return;
        }
        try {
            metricsToClose.shutdown();
        } catch (RuntimeException exception) {
            logger.warn("bStats 关闭失败，继续执行其余清理", exception);
        }
    }

    /**
     * Zamknięcie pluginu przy wyłączaniu proxy.
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {

        if (logger.isInfoEnabled()) {
            logger.info("=== YouShuiAuth - Shutdown ===");
        }

        shutdown();

        if (logger.isInfoEnabled()) {
            logger.info("=== YouShuiAuth - Closed ===");
        }
    }

    /**
     * Helper method to reduce cognitive complexity in error handling.
     * Logs error if enabled and throws the specified exception.
     */
    private void logErrorAndRethrow(String message, Exception cause, RuntimeException toThrow) {
        if (logger.isErrorEnabled()) {
            logger.error(message, cause);
        }
        throw toThrow;
    }

    /**
     * Inicjalizuje wszystkie komponenty pluginu.
     */
    private void initializePlugin() {
        try {
            initializeConfiguration();
            initializeMessages();
            initializeDatabase();
            initializeAuditLog();
            initializeTwoFactor();
            initializeCache();
            initializeCommands();
            initializeQqBridge();
            initializeReportService();
            initializeConnectionManager();
            initializePremiumResolver();
            initializeListeners();
            debugServers();

            if (logger.isDebugEnabled()) {
                logger.debug(messages.get("plugin.initialization.components_ready"));
            }

        } catch (YouShuiAuthException e) {
            logErrorAndRethrow("YouShuiAuth error during initialization", e, e);
        } catch (IllegalStateException | IllegalArgumentException e) {
            logErrorAndRethrow("Critical error during YouShuiAuth initialization", e,
                YouShuiAuthException.configuration("plugin initialization", e));
        } catch (Exception e) {
            logErrorAndRethrow("Unexpected error during YouShuiAuth initialization", e,
                YouShuiAuthException.configuration("unexpected error", e));
        }
    }

    private void initializeConfiguration() {
        if (logger.isDebugEnabled()) {
            logger.debug("📋 [1/8] Loading configuration...");
        }
        long startTime = System.currentTimeMillis();

        settings = new Settings(dataDirectory);
        if (!settings.load()) {
            throw YouShuiAuthException.configuration("settings loading", null);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("✅ Configuration loaded in {} ms", System.currentTimeMillis() - startTime);
        }
    }

    private void initializeMessages() {
        if (logger.isDebugEnabled()) {
            logger.debug("💬 [2/8] Initializing message system...");
        }
        long startTime = System.currentTimeMillis();

        String language = settings.getLanguage();

        try {
            messages = new Messages(dataDirectory, language);
            if (logger.isDebugEnabled()) {
                logger.debug("✅ Message system initialized in {} ms (Language: {}, External files: enabled)",
                        System.currentTimeMillis() - startTime, language);
            }
        } catch (IOException e) {
            logger.error("外部语言文件初始化失败，回退到 JAR 内置文件", e);
            messages = new Messages();
            messages.setLanguage(language);
            if (logger.isDebugEnabled()) {
                logger.debug("✅ Message system initialized in {} ms (Language: {}, External files: disabled)",
                        System.currentTimeMillis() - startTime, language);
            }
        }
    }

    private void initializeDatabase() {
        if (logger.isDebugEnabled()) {
            logger.debug("🗄️ [3/8] Initializing database connection...");
        }
        long startTime = System.currentTimeMillis();

        DatabaseConfig dbConfig = createDatabaseConfig();
        databaseManager = new DatabaseManager(dbConfig, messages);

        boolean dbInitialized = databaseManager.initialize().join();
        if (!dbInitialized) {
            throw YouShuiAuthException.database("initialization", null);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("✅ Database initialized in {} ms (Type: {})",
                    System.currentTimeMillis() - startTime, settings.getDatabaseStorageType());
        }
    }

    private void initializeAuditLog() {
        Settings.AuditLogSettings auditSettings = settings.getAuditLogSettings();
        boolean enabled = auditSettings.isEnabled();
        auditLogService = new AuditLogService(databaseManager.getAuditLogDao(), enabled);

        if (!enabled) {
            if (logger.isInfoEnabled()) {
                logger.info("审计日志已按配置禁用");
            }
            return;
        }

        int retentionDays = auditSettings.getRetentionDays();
        auditLogCleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread cleanup = new Thread(r, "YouShuiAuth-AuditLogCleanup");
            cleanup.setDaemon(true);
            return cleanup;
        });
        auditLogCleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                int pruned = auditLogService.prune(retentionDays);
                if (pruned > 0) {
                    logger.info("审计日志清理：移除 {} 条超过 {} 天的记录", pruned, retentionDays);
                }
            } catch (RuntimeException e) {
                logger.error("审计日志清理失败", e);
            }
        }, 1, 24, TimeUnit.HOURS);

        if (logger.isDebugEnabled()) {
            logger.debug("审计日志已初始化（保留 {} 天）", retentionDays);
        }
    }

    /**
     * Phase 3.5: Two-Factor (TOTP) services. Always instantiated — even when
     * {@code two-factor.enabled=false} — because {@code TotpService} is a stateless
     * verifier and {@code PendingTotpStore} is cheap. The complete two-factor tree is
     * restart-scoped: call sites read the active startup snapshot, and reload records edits as
     * pending until the next full proxy restart.
     */
    private void initializeTwoFactor() {
        Settings.TwoFactorSettings twoFactorSettings = settings.getTwoFactorSettings();
        this.totpService = new TotpService();
        this.totpReplayGuard = new TotpReplayGuard();
        java.time.Duration pendingTtl = java.time.Duration.ofSeconds(twoFactorSettings.getPendingTimeoutSeconds());
        this.pendingTotpStore = new PendingTotpStore(pendingTtl, auditLogService);

        if (logger.isDebugEnabled()) {
            logger.debug("2FA initialized (enabled: {}, pending-timeout: {}s, issuer: {})",
                    twoFactorSettings.isEnabled(),
                    twoFactorSettings.getPendingTimeoutSeconds(),
                    twoFactorSettings.getIssuer());
        }
    }

    private void initializeCache() {
        if (logger.isDebugEnabled()) {
            logger.debug("💾 [4/8] Initializing authentication cache...");
        }
        long startTime = System.currentTimeMillis();

        authCache = new AuthCache(
                new AuthCache.AuthCacheConfig(
                    settings.getCacheTtlMinutes(),
                    settings.getCacheMaxSize(),
                    settings.getCacheMaxSessions(),
                    settings.getCacheMaxPremium(),
                    settings.getBruteForceMaxAttempts(),
                    settings.getBruteForceTimeoutMinutes(),
                    settings.getCacheCleanupIntervalMinutes(),
                    settings.getSessionTimeoutMinutes()
                ),
                settings,
                messages
        );

        if (databaseManager != null) {
            databaseManager.setAuthCacheReference(authCache);
            logger.debug("已向 DatabaseManager 注入 AuthCache 引用");
        }

        long premiumTtlMinutes = (long) settings.getPremiumTtlHours() * 60;
        if (premiumTtlMinutes > 0 && databaseManager != null) {
            premiumDbCleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "YouShuiAuth-PremiumDbCleanup");
                t.setDaemon(true);
                return t;
            });
            premiumDbCleanupScheduler.scheduleAtFixedRate(() -> {
                try {
                    int cleaned = databaseManager.getPremiumUuidDao()
                            .cleanExpiredEntries(premiumTtlMinutes);
                    if (cleaned > 0) {
                        logger.info("已从数据库清理 {} 条过期的正版 UUID 缓存", cleaned);
                    }
                } catch (Exception e) {
                    logger.error("清理数据库中的正版 UUID 缓存出错", e);
                }
            }, 1, 1, TimeUnit.HOURS);
        }

        logger.debug("✅ Cache initialized in {} ms (TTL: {} min, Max size: {}, Premium cache: {})",
                System.currentTimeMillis() - startTime,
                settings.getCacheTtlMinutes(),
                settings.getCacheMaxSize(),
                settings.getCacheMaxPremium());
    }

    private void initializeCommands() {
        if (logger.isDebugEnabled()) {
            logger.debug("⌨️ [5/8] Registering commands...");
        }
        long startTime = System.currentTimeMillis();

        commandHandler = new CommandHandler(this, databaseManager, authCache, settings, messages);
        commandHandler.registerCommands();

        logger.debug("✅ Commands registered in {} ms", System.currentTimeMillis() - startTime);
    }

    /**
     * Starts the loopback QQ bridge. Failure is non-fatal: password authentication must keep
     * working even when the optional bot integration cannot bind its port.
     */
    private void initializeQqBridge() {
        QqSettings qqSettings = settings.getQqSettings();
        if (!qqSettings.isEnabled()) {
            logger.debug("QQ 桥接已按配置禁用");
            return;
        }
        if (commandHandler == null) {
            logger.warn("QQ 桥接未启动：命令层不可用");
            return;
        }
        try {
            QqBridgeServer candidate = new QqBridgeServer(
                    qqSettings, commandHandler.getQqAuthService());
            candidate.start();
            qqBridgeServer = candidate;
            qqCleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "YouShuiAuth-QqCleanup");
                thread.setDaemon(true);
                return thread;
            });
            qqCleanupScheduler.scheduleAtFixedRate(this::cleanupQqTables, 1, 24, TimeUnit.HOURS);
        } catch (IOException e) {
            logger.error("QQ 桥接无法监听 {}:{}——QQ 相关功能已禁用",
                    qqSettings.getBindAddress(), qqSettings.getPort(), e);
        }
    }

    /** Drops expired one-time codes and remember-device tokens; keeps the tables bounded. */
    private void cleanupQqTables() {
        try {
            var qqDao = databaseManager == null ? null : databaseManager.getQqDao();
            if (qqDao == null) {
                return;
            }
            long now = System.currentTimeMillis();
            int codes = qqDao.deleteExpiredLinkCodes(now);
            int tokens = qqDao.purgeExpiredRememberTokens(now);
            if ((codes > 0 || tokens > 0) && logger.isInfoEnabled()) {
                logger.info("QQ 清理：移除 {} 个过期码与 {} 条免密记录",
                        codes, tokens);
            }
        } catch (Exception e) {
            logger.error("清理 QQ 相关数据表失败", e);
        }
    }

    private void initializeReportService() {
        reportService = new io.github.fulingsnow.youshuiauth.report.ReportService(this, settings);
        if (logger.isDebugEnabled()) {
            logger.debug("📋 Report service initialized (enabled={})", settings.isReportEnabled());
        }
    }

    private void initializeConnectionManager() {
        if (logger.isDebugEnabled()) {
            logger.debug("🔌 [6/8] Initializing connection manager...");
        }
        long startTime = System.currentTimeMillis();

        authServerProvider = AuthServerProvider.create(server, settings, messages, logger, dataDirectory);
        authServerProvider.start();
        connectionManager = new ConnectionManager(
                this, authCache, settings, messages, authServerProvider);
        authTimeoutScheduler = new AuthTimeoutScheduler(this, settings, messages, authCache, connectionManager);
        Settings.AuditLogSettings auditSettings = settings.getAuditLogSettings();
        autoLoginService = new io.github.fulingsnow.youshuiauth.auth.AutoLoginService(
                settings, authCache, databaseManager::getQqDao, databaseManager, "velocity", logger,
                messages,
                new io.github.fulingsnow.youshuiauth.audit.AuditLogService(
                        databaseManager.getAuditLogDao(), auditSettings.isEnabled()),
                authTimeoutScheduler);

        autoLoginService.setDeviceApprovalFlow(
                () -> getQqAuthService().deviceApprovalFlow());

        logger.debug("✅ Connection manager initialized in {} ms (auth timeout: {}s)",
                System.currentTimeMillis() - startTime,
                settings.getAuthServerTimeoutSeconds());
    }

    private void initializePremiumResolver() {
        if (logger.isDebugEnabled()) {
            logger.debug("👑 [7/8] Initializing premium resolver service...");
        }
        long startTime = System.currentTimeMillis();

        if (settings.getAlertSettings().isEnabled()) {
            premiumResolverAlertService = new PremiumResolverAlertService(settings);
            logger.info("正版解析 Discord 告警已启用（已配置 webhook：{}）",
                    settings.getAlertSettings().isDiscordEnabled());
        }

        premiumResolverService = new PremiumResolverService(
                logger, settings, databaseManager.getPremiumUuidDao(), premiumResolverAlertService);

        logger.debug("✅ Premium resolver initialized in {} ms (Enabled: {}, Alerts: {})",
                System.currentTimeMillis() - startTime,
                settings.isPremiumCheckEnabled(),
                premiumResolverAlertService != null);
    }

    private void initializeListeners() {
        if (logger.isDebugEnabled()) {
            logger.debug("🎧 [8/8] Registering event listeners...");
        }
        long startTime = System.currentTimeMillis();

        var preLoginHandler = ListenerFactory.createPreLoginHandler(
            authCache, premiumResolverService, settings, databaseManager,
            messages, logger);
        logger.debug("PreLoginHandler 创建成功");

        var postLoginHandler = ListenerFactory.createPostLoginHandler(
            authCache, databaseManager,
            messages, logger);
        logger.debug("PostLoginHandler 创建成功");

        authListener = new AuthListener(
            this, authCache, settings,
            preLoginHandler, postLoginHandler, connectionManager, databaseManager, messages);

        server.getEventManager().register(this, authListener);
        logger.debug("✅ Event listeners registered in {} ms (PreLoginHandler + PostLoginHandler + AuthListener)",
                System.currentTimeMillis() - startTime);
    }

    private void debugServers() {
        connectionManager.debugServers();
    }

    /**
     * Simple pending operations cleanup.
     * Components handle their own graceful shutdown internally.
     */
    private void waitForPendingOperations() {
        if (logger.isDebugEnabled()) {
            logger.debug("组件关闭进行中——未完成的操作由内部处理");
        }
    }

    /**
     * Creates database configuration from settings.
     * Uses HikariCP for remote databases (MySQL, PostgreSQL).
     */
    private DatabaseConfig createDatabaseConfig() {
        String storageType = settings.getDatabaseStorageType();
        DatabaseType dbType = DatabaseType.fromName(storageType);

        if (dbType != null && dbType.isLocalDatabase()) {

            return DatabaseConfig.forLocalDatabaseWithMigration(
                    storageType, settings.getDatabaseName(), settings.getDataDirectory(), logger);
        } else {

            return DatabaseConfig.forRemoteWithHikari(
                    HikariConfigParams.builder()
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
                            .build()
            );
        }
    }

    /**
     * Zamyka wszystkie komponenty pluginu z graceful shutdown.
     */
    private void shutdown() {
        lifecycleLock.lock();
        try {
            if (shutdownStarted) {
                logger.debug("关闭已在进行中，忽略重复的关闭请求");
                return;
            }

            shutdownStarted = true;
            initialized = false;
            initializationFuture.completeExceptionally(new IllegalStateException("Plugin shutting down"));
        } finally {
            lifecycleLock.unlock();
        }

        connectionLifecycleRegistry.close();
        logger.info("🔴 Initialization flag set to FALSE - blocking all new player connections");

        try {
            logger.info("开始 YouShuiAuth 优雅关闭...");

            shutdownMetricsSafely();

            if (authListener != null) {
                server.getEventManager().unregisterListener(this, authListener);
                logger.debug("AuthListener 已注销");
            }

            if (commandHandler != null) {
                commandHandler.unregisterCommands();
                commandHandler.shutdown();
                logger.debug("命令已注销");
            }

            waitForPendingOperations();

            shutdownComponentsInReverseOrder();

            logger.info("YouShuiAuth 关闭完成");

        } catch (RuntimeException e) {

            logger.error("优雅关闭出错，强制关闭剩余组件", e);
            connectionLifecycleRegistry.close();
            closeAuthServerProviderSafely();
            if (databaseManager != null) {
                try {
                    databaseManager.shutdown();
                } catch (RuntimeException dbEx) {
                    logger.error("强制清理阶段 DatabaseManager 关闭失败", dbEx);
                }
            }
            VirtualThreadExecutorProvider.shutdown();
        }
    }

    /** Components shut down in reverse initialization order; the DB pool and VT executor go last. */
    private void shutdownComponentsInReverseOrder() {
        if (qqBridgeServer != null) {
            qqBridgeServer.close();
            qqBridgeServer = null;
            logger.debug("QQ 桥接已停止");
        }

        if (authTimeoutScheduler != null) {
            authTimeoutScheduler.shutdown();
            logger.debug("AuthTimeoutScheduler 已关闭");
        }

        if (connectionManager != null) {
            connectionManager.shutdown();
            logger.debug("ConnectionManager 已关闭");
        }
        if (authServerProvider != null) {
            closeAuthServerProviderSafely();
        }

        shutdownCleanupScheduler(qqCleanupScheduler, "QQ cleanup scheduler");
        shutdownCleanupScheduler(auditLogCleanupScheduler, "Audit log cleanup scheduler");
        shutdownCleanupScheduler(premiumDbCleanupScheduler, "Premium DB cleanup scheduler");

        if (premiumResolverService != null) {
            premiumResolverService.shutdown();
            logger.debug("PremiumResolverService 已关闭");
        }

        if (premiumResolverAlertService != null) {
            premiumResolverAlertService.close();
            logger.debug("PremiumResolverAlertService 已关闭");
        }

        if (authCache != null) {
            authCache.shutdown();
            logger.debug("AuthCache 已关闭");
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
            logger.debug("DatabaseManager 已关闭");
        }

        VirtualThreadExecutorProvider.shutdown();
        logger.debug("虚拟线程执行器已关闭");
    }

    /** Clears only state owned by a concrete connection; it performs no database or backend I/O. */
    public void clearConnectionBoundState(
            Player player, AuthCache connectionAuthCache,
            ConnectionManager connectionManagerOwner) {
        Objects.requireNonNull(player, "player");
        clearConnectionBoundState(
                player, player.getUniqueId(), connectionAuthCache, connectionManagerOwner);
    }

    /** Variant for async/lifecycle paths that captured the UUID while the Player was valid. */
    public void clearConnectionBoundState(
            Player player, java.util.UUID playerId, AuthCache connectionAuthCache,
            ConnectionManager connectionManagerOwner) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionAuthCache, "connectionAuthCache");
        Objects.requireNonNull(connectionManagerOwner, "connectionManagerOwner");
        runConnectionCleanup("authorization cache", playerId,
                () -> connectionAuthCache.removeAuthorizedPlayer(playerId));
        runConnectionCleanup("session cache", playerId,
                () -> connectionAuthCache.endSession(playerId));
        if (pendingTotpStore != null) {
            runConnectionCleanup("pending two-factor state", playerId,
                    () -> pendingTotpStore.invalidate(playerId));
        }
        runConnectionCleanup("connection-manager state", playerId,
                () -> connectionManagerOwner.clearTransferState(player));
        if (authTimeoutScheduler != null) {
            runConnectionCleanup("authentication timeout", playerId,
                    () -> authTimeoutScheduler.cancel(playerId));
        }
    }

    private void runConnectionCleanup(String component, java.util.UUID playerId, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException exception) {
            logger.error("清理已退役连接的 {} 失败：{}",
                    component, playerId, exception);
        }
    }

    private void closeAuthServerProviderSafely() {
        if (authServerProvider == null) {
            return;
        }
        try {
            authServerProvider.close();
            logger.debug("AuthServerProvider 已关闭");
        } catch (RuntimeException exception) {
            logger.error("AuthServerProvider 关闭失败", exception);
        }
    }

    private void shutdownCleanupScheduler(ScheduledExecutorService scheduler, String schedulerName) {
        if (scheduler == null) {
            return;
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(CLEANUP_SCHEDULER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("{} did not stop in time - forcing shutdown", schedulerName);
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(CLEANUP_SCHEDULER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn("{} still running after forced shutdown", schedulerName);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
            logger.warn("停止 {} 时被中断", schedulerName, e);
        }

        logger.debug("{} shut down", schedulerName);
    }

    private void logStartupInfo(long initializationDuration) {
        if (logger.isInfoEnabled()) {
            if (authServerProvider != null) {
                logger.info("认证服 '{}' 已配置（模式={}，客户端兼容性={}）",
                        authServerProvider.serverName(),
                        authServerProvider.mode().getConfigValue(),
                        authServerProvider.compatibilityDescription());
            } else {
                logger.info("认证服 '{}' 已配置", settings.getAuthServerName());
            }
            String effectiveFloodgatePrefix = FloodgateDetector.getPlayerPrefix()
                    .orElse(settings.getFloodgateUsernamePrefix());
            logger.info("Floodgate 支持：{}（实际前缀='{}'，跳过认证={}）",
                    settings.isFloodgateIntegrationEnabled() ? "enabled" : "disabled",
                    effectiveFloodgatePrefix,
                    settings.isFloodgateBypassAuthServerEnabled());

            String dbType = settings.getDatabaseStorageType();
            String language = settings.getLanguage();

            logger.info("初始化完成，耗时 {} ms（数据库 {}，语言 {}，bStats 集成 {}）",
                    initializationDuration, dbType, language, metrics == null ? "unavailable" : "initialized");
            logger.info("就绪——允许玩家连接");
        }
    }

    /**
     * Przeładowuje konfigurację pluginu i pliki językowe.
     *
     * @return true jeśli sukces
     */
    public boolean reloadConfig() {
        try {
            if (logger.isInfoEnabled()) {
                logger.info(messages.get("config.reloading"));
            }

            boolean configReloaded = settings.load();

            boolean languageReloaded = reloadLanguageFiles();

            if (configReloaded) {
                if (logger.isInfoEnabled()) {
                    logger.info(messages.get("config.reloaded_success"));
                }
                Set<String> pendingRestartChanges = settings.getPendingRestartChanges();
                if (!pendingRestartChanges.isEmpty()) {
                    logger.warn("以下有效配置变更需重启后生效：{}",
                            String.join(", ", pendingRestartChanges));
                }
                logger.info("配置已重载（部分变更需完整重启后才生效）");
                return languageReloaded;
            } else {
                if (logger.isErrorEnabled()) {
                    logger.error(messages.get("config.reload_failed"));
                }
                return false;
            }

        } catch (IllegalStateException | IllegalArgumentException e) {

            if (logger.isErrorEnabled()) {
                logger.error("配置重载失败：{}", e.getMessage(), e);
            }
            return false;
        }
    }

    /**
     * Zwraca ProxyServer instance.
     *
     * @return ProxyServer
     */
    public ProxyServer getServer() {
        return server;
    }

    /**
     * Zwraca Logger instance.
     *
     * @return Logger
     */
    public Logger getLogger() {
        return logger;
    }

    /**
     * Zwraca katalog danych pluginu.
     *
     * @return Path do katalogu danych
     */
    public Path getDataDirectory() {
        return dataDirectory;
    }

    public io.github.fulingsnow.youshuiauth.platform.PlatformAccess getPlatformAccess() {
        io.github.fulingsnow.youshuiauth.platform.PlatformAccess current = platformAccess;
        if (current == null) {
            synchronized (this) {
                current = platformAccess;
                if (current == null) {
                    current = new io.github.fulingsnow.youshuiauth.velocity.VelocityPlatformAccess(
                            this, server, dataDirectory);
                    platformAccess = current;
                }
            }
        }
        return current;
    }

    /**
     * Zwraca ustawienia pluginu.
     *
     * @return Settings
     */
    public Settings getSettings() {
        return settings;
    }

    /**
     * Zwraca system wiadomości (i18n).
     *
     * @return Messages
     */
    public Messages getMessages() {
        return messages;
    }

    /**
     * Zwraca manager bazy danych.
     *
     * @return DatabaseManager
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * Zwraca cache autoryzacji.
     *
     * @return AuthCache
     */
    public AuthCache getAuthCache() {
        return authCache;
    }

    /**
     * Zwraca audit log service (zawsze ne-null po inicjalizacji,
     * ale wewnętrznie no-op gdy wyłączony przez config).
     *
     * @return AuditLogService
     */
    public AuditLogService getAuditLogService() {
        return auditLogService;
    }

    /**
     * Zwraca connection manager.
     *
     * @return ConnectionManager
     */
    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * @return QQ binding / quick-login adapter, or {@code null} before the command layer starts
     */
    public QqAuthService getQqAuthService() {
        return commandHandler != null ? commandHandler.getQqAuthService() : null;
    }

    /** @return concrete-player lifecycle generations used by listeners and command operations. */
    public VelocityConnectionLifecycleRegistry getConnectionLifecycleRegistry() {
        return connectionLifecycleRegistry;
    }

    /** Returns the restart-scoped owner of the external or embedded auth-server topology. */
    public AuthServerProvider getAuthServerProvider() {
        return authServerProvider;
    }

    /**
     * @return auth-server timeout scheduler (kicks players who linger unauthenticated)
     */
    public AuthServices asServices() {
        return this;
    }

    public io.github.fulingsnow.youshuiauth.platform.ConnectionOps connections() {
        return new io.github.fulingsnow.youshuiauth.platform.ConnectionOps() {
            public io.github.fulingsnow.youshuiauth.platform.ConnectionRef capture(
                    io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
                Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
                return velocity == null ? null : getConnectionLifecycleRegistry().capture(velocity);
            }

            public boolean isCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
                return getConnectionLifecycleRegistry().isCurrent(operation);
            }

            public boolean runIfCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
                                        Runnable effect) {
                return getConnectionLifecycleRegistry().runIfCurrent(operation, effect);
            }

            public boolean retire(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
                Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
                return velocity != null && getConnectionLifecycleRegistry().markRetired(
                        velocity, () -> clearConnectionBoundState(
                                velocity, getAuthCache(), getConnectionManager()));
            }
        };
    }

    public io.github.fulingsnow.youshuiauth.platform.TimeoutOps timeouts() {
        return getAuthTimeoutScheduler();
    }

    public io.github.fulingsnow.youshuiauth.platform.TransferOps transfers() {
        ConnectionManager cm = getConnectionManager();
        return new io.github.fulingsnow.youshuiauth.platform.TransferOps() {
            public boolean isPlayerOnAuthServer(java.util.UUID playerId) {
                return cm.isPlayerOnAuthServer(playerId);
            }

            public boolean transferToBackend(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
                Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
                return velocity != null && cm.transferToBackend(velocity);
            }
        };
    }

    public io.github.fulingsnow.youshuiauth.platform.ReportOps reports() {
        ReportService service = getReportService();
        return service == null ? null : new io.github.fulingsnow.youshuiauth.platform.ReportOps() {
            public io.github.fulingsnow.youshuiauth.platform.ReportOps.Outcome generateAndUpload(
                    Settings.OperationSettings settings) {
                ReportService.ReportResult result = service.generateAndUpload(settings);
                return new io.github.fulingsnow.youshuiauth.platform.ReportOps.Outcome(
                        result.success(), result.url(), result.error());
            }
        };
    }

    public io.github.fulingsnow.youshuiauth.platform.QqOps qq() {
        return getQqAuthService();
    }

    public io.github.fulingsnow.youshuiauth.database.DatabaseManager databaseManager() {
        return getDatabaseManager();
    }

    public io.github.fulingsnow.youshuiauth.cache.AuthCache authCache() {
        return getAuthCache();
    }

    public Settings settings() {
        return getSettings();
    }

    public Messages messages() {
        return getMessages();
    }

    public org.slf4j.Logger logger() {
        return getLogger();
    }

    public io.github.fulingsnow.youshuiauth.audit.AuditLogService auditLogService() {
        return getAuditLogService();
    }

    public io.github.fulingsnow.youshuiauth.auth.totp.TotpService totpService() {
        return getTotpService();
    }

    public io.github.fulingsnow.youshuiauth.auth.totp.PendingTotpStore pendingTotpStore() {
        return getPendingTotpStore();
    }

    public io.github.fulingsnow.youshuiauth.auth.totp.TotpReplayGuard totpReplayGuard() {
        return getTotpReplayGuard();
    }

    public io.github.fulingsnow.youshuiauth.platform.PlatformAccess platformAccess() {
        return getPlatformAccess();
    }

    public io.github.fulingsnow.youshuiauth.auth.AutoLoginService getAutoLoginService() {
        return autoLoginService;
    }

    public AuthTimeoutScheduler getAuthTimeoutScheduler() {
        return authTimeoutScheduler;
    }

    /** @return shared RFC 6238 TOTP verifier (stateless). */
    public TotpService getTotpService() {
        return totpService;
    }

    /** @return store of in-flight 2FA verification states (post-BCrypt and pending /2fa setup). */
    public PendingTotpStore getPendingTotpStore() {
        return pendingTotpStore;
    }

    /** @return diagnostic report service for /vauth report (mclo.gs integration). */
    public io.github.fulingsnow.youshuiauth.report.ReportService getReportService() {
        return reportService;
    }

    /** @return per-player TOTP replay guard (RFC 6238 §5.2 — one-time-only enforcement). */
    public TotpReplayGuard getTotpReplayGuard() {
        return totpReplayGuard;
    }

    /**
     * Sprawdza czy plugin jest zainicjalizowany.
     *
     * @return true jeśli zainicjalizowany
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the fully wired auth listener, or {@code null} until phase 8 of initialization
     * has registered it. EarlyLoginBlocker delegates queued PreLogin events here, because a
     * listener registered while an event dispatch is suspended never receives that event —
     * Velocity snapshots the handler list at fire time.
     */
    public AuthListener getAuthListener() {
        return authListener;
    }

    /**
     * Returns a future that completes when the plugin is fully initialized.
     * Used by EarlyLoginBlocker to queue connections during startup.
     *
     * @return CompletableFuture that completes on successful initialization
     */
    public CompletableFuture<Void> getInitializationFuture() {
        return initializationFuture.copy();
    }

    /**
     * Zwraca wersję pluginu.
     *
     * @return Wersja pluginu
     */
    public String getVersion() {
        return BuildConstants.VERSION;
    }
}
