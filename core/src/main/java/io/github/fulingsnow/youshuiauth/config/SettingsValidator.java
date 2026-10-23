package io.github.fulingsnow.youshuiauth.config;

import io.github.fulingsnow.youshuiauth.database.DatabaseType;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.util.FloodgateDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates Settings configuration values.
 * All methods are static and throw IllegalArgumentException on invalid values.
 */
public final class SettingsValidator {

    private static final Logger logger = LoggerFactory.getLogger(SettingsValidator.class);

    private SettingsValidator() {}

    /**
     * Validates all settings and adjusts values where appropriate.
     *
     * @param settings the settings to validate
     * @throws IllegalArgumentException if any value is invalid
     */
    public static void validate(Settings settings) {
        validateDatabase(settings);
        validateCache(settings);
        validateSecurity(settings);
        validateAuthServer(settings);
        validateConnection(settings);
        validatePremium(settings);
        validateFloodgate(settings);
        validateQq(settings);
        validateAuditLog(settings);
        validateTwoFactor(settings);
        validateAuthTimeoutWindow(settings);
        validateAlerts(settings);
        settings.normalizeLanguage();
        logger.debug("配置校验通过");
    }

    static void validateQq(Settings settings) {
        QqSettings qq = settings.getQqSettings();
        if (!qq.isEnabled()) {
            return;
        }
        validateQqSecret(qq);
        validateQqBinding(qq);
        validateQqCodes(qq, settings);
        validateQqDeviceApproval(qq, settings);
        if (qq.getAllowedGroups().isEmpty()) {
            logger.warn("配置 'qq.allowed-groups' 为空：所有 QQ 群请求都会被拒绝");
        }
        if (qq.getAllowedGroups().contains("*")) {
            logger.warn("配置 'qq.allowed-groups' 为 '*'：所有 QQ 群都会被接受，"
                    + "本地测试之外请填入真实群号");
        }
    }

    private static void validateQqSecret(QqSettings qq) {
        if (qq.getSharedSecret() == null || qq.getSharedSecret().length() < 32) {
            throw new IllegalArgumentException(
                    "qq.shared-secret must be at least 32 characters when qq.enabled=true");
        }
    }

    private static void validateQqBinding(QqSettings qq) {
        if (qq.getBindAddress() == null || qq.getBindAddress().isBlank()) {
            throw new IllegalArgumentException("qq.bind-address must not be empty");
        }
        if (!isLoopback(qq.getBindAddress())) {
            logger.warn("配置 'qq.bind-address={}' 使内部桥接暴露到回环地址之外；"
                    + "请保持 127.0.0.1，由反向代理终止 TLS",
                    qq.getBindAddress());
        }
        if (qq.getPort() < 1 || qq.getPort() > 65535) {
            throw new IllegalArgumentException("qq.port must be in range 1-65535");
        }
    }

    private static void validateQqCodes(QqSettings qq, Settings settings) {
        if (qq.getBindCodeTtlSeconds() < 60 || qq.getBindCodeTtlSeconds() > 3600) {
            throw new IllegalArgumentException(
                    "qq.bind-code-ttl-seconds must be in range 60-3600");
        }
        if (qq.getLoginCodeTtlSeconds() < 30 || qq.getLoginCodeTtlSeconds() > 600) {
            throw new IllegalArgumentException(
                    "qq.login-code-ttl-seconds must be in range 30-600");
        }
        if (qq.getMaxAttemptsPerMinute() < 1 || qq.getMaxAttemptsPerMinute() > 100) {
            throw new IllegalArgumentException(
                    "qq.max-attempts-per-minute must be in range 1-100");
        }
        if (qq.getLoginCodeTtlSeconds() > settings.getAuthServerTimeoutSeconds()) {
            logger.warn("qq.login-code-ttl-seconds={} 超过 auth-server.timeout-seconds={}："
                    + "登录码要等玩家已被移出 limbo 之后才过期",
                    qq.getLoginCodeTtlSeconds(), settings.getAuthServerTimeoutSeconds());
        }
    }

    /**
     * The device-approval window is only useful while device auto-login is active; enabling it
     * without auto-login is harmless but almost certainly a configuration mistake.
     */
    private static void validateQqDeviceApproval(QqSettings qq, Settings settings) {
        int timeout = qq.getDeviceApprovalTimeoutSeconds();
        if (timeout < 30 || timeout > 600) {
            throw new IllegalArgumentException(
                    "qq.device-approval.timeout-seconds must be in range 30-600");
        }
        if (qq.isDeviceApprovalEnabled() && !settings.isAutoLoginEnabled()) {
            logger.warn("配置 'qq.device-approval.enabled=true' 在 "
                    + "'security.auto-login-enabled=false' 时不生效");
        }
    }

    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "localhost".equalsIgnoreCase(address)
                || "::1".equals(address);
    }

    static void validateAuditLog(Settings settings) {
        int retention = settings.getAuditLogSettings().getRetentionDays();
        if (retention < 1 || retention > 3650) {
            throw new IllegalArgumentException("Audit log retention-days must be in range 1-3650");
        }
    }

    static void validateAuthTimeoutWindow(Settings settings) {

        if (settings.getAuthServerTimeoutSeconds() > 600) {
            logger.warn("auth-server.timeout-seconds={} 超过硬上限 600 秒，未认证会话将在 600 秒时结束",
                    settings.getAuthServerTimeoutSeconds());
        }
    }

    static void validateAlerts(Settings settings) {
        Settings.AlertSettings alerts = settings.getAlertSettings();
        if (!alerts.isEnabled()) {
            return;
        }
        if (alerts.getCheckIntervalMinutes() < 1) {
            logger.warn("配置 'alerts.check-interval-minutes' 小于 1，已按 1 分钟执行统计重置");
        }
        if (alerts.isDiscordEnabled()
                && (alerts.getDiscordWebhookUrl() == null
                || alerts.getDiscordWebhookUrl().isBlank())) {
            logger.warn("配置 'alerts.discord.enabled=true' 但 webhook-url 为空，Discord 告警不会发送");
        }
    }

    static void validateTwoFactor(Settings settings) {
        Settings.TwoFactorSettings twoFactor = settings.getTwoFactorSettings();
        int timeout = twoFactor.getPendingTimeoutSeconds();
        if (timeout < 30 || timeout > 1800) {
            throw new IllegalArgumentException(
                "two-factor.pending-timeout-seconds must be in range 30-1800 (got " + timeout + ")");
        }
        String issuer = twoFactor.getIssuer();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("two-factor.issuer must not be empty");
        }

        if (issuer.contains(":")) {
            throw new IllegalArgumentException(
                "two-factor.issuer must not contain ':' (reserved separator in otpauth URI)");
        }
        if (twoFactor.isQrLinkEnabled()) {
            logger.warn("配置 'two-factor.qr-link-enabled=true' 会把每次生成的 TOTP 绑定密钥发往外部二维码服务；仅需本机绑定时请保持 false。");
        }
    }

    static void validateDatabase(Settings settings) {
        if (DatabaseType.fromName(settings.getDatabaseStorageType()) == null) {
            throw new IllegalArgumentException("Unsupported database type: " + settings.getDatabaseStorageType());
        }

        int port = settings.getDatabasePort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Database port must be in range 1-65535");
        }
    }

    static void validateCache(Settings settings) {
        if (settings.getCacheTtlMinutes() < 0) {
            throw new IllegalArgumentException("Cache TTL must not be negative");
        }
        if (settings.getCacheMaxSize() <= 0) {
            throw new IllegalArgumentException("Cache max size must be > 0");
        }
        if (settings.getCacheCleanupIntervalMinutes() <= 0) {
            throw new IllegalArgumentException("Cache cleanup interval must be > 0");
        }
        if (settings.getSessionTimeoutMinutes() <= 0) {
            throw new IllegalArgumentException("Session timeout minutes must be > 0");
        }
        if (settings.getPremiumTtlHours() <= 0) {
            throw new IllegalArgumentException("Premium TTL hours must be > 0");
        }
        if (settings.getPremiumRefreshThreshold() < 0.0 || settings.getPremiumRefreshThreshold() > 1.0) {
            throw new IllegalArgumentException("Premium refresh threshold must be in range 0.0-1.0");
        }
    }

    static void validateSecurity(Settings settings) {
        validateBcryptCost(settings);
        validateBruteForceSettings(settings);
        validatePasswordLengthSettings(settings);
        adjustMaxPasswordLengthIfNeeded(settings);
        validateIpLimitRegistrations(settings);
        validateConflictModeTtl(settings);
    }

    /**
     * {@code conflict-mode-ttl-hours = 0} is explicitly allowed — it disables the TTL,
     * restoring the pre-1.3.3 permanent-conflict behaviour for operators who want it.
     */
    private static void validateConflictModeTtl(Settings settings) {
        if (settings.getConflictModeTtlHours() < 0) {
            throw new IllegalArgumentException("conflict-mode-ttl-hours must be >= 0 (0 = disabled)");
        }
        if (settings.getConflictModeTtlHours() == 0) {
            logger.warn("配置 'security.conflict-mode-ttl-hours=0' 会让 UUID 冲突例外永久保留；除非确实需要该遗留兼容模式，请填正数。");
        }
    }

    static void validateConnection(Settings settings) {
        if (settings.getConnectionTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Connection timeout must be > 0");
        }

        if (settings.getPingTimeoutMillis() <= 0) {
            throw new IllegalArgumentException("connection.ping-timeout-ms must be > 0");
        }
        if (settings.getPingTimeoutMillis() > 30_000) {
            throw new IllegalArgumentException("connection.ping-timeout-ms must be <= 30000 (30s)");
        }

        if (settings.getAutoTransferDelayMillis() < 0) {
            throw new IllegalArgumentException("connection.auto-transfer-delay-ms must be >= 0");
        }
        if (settings.getAutoTransferDelayMillis() > 30_000) {
            throw new IllegalArgumentException("connection.auto-transfer-delay-ms must be <= 30000 (30s)");
        }

        if (settings.getDatabaseConnectionPoolSize() <= 0) {
            throw new IllegalArgumentException("Connection pool size must be > 0");
        }
    }

    static void validateAuthServer(Settings settings) {

        Settings.AuthServerMode mode = Settings.AuthServerMode.parse(settings.getConfiguredAuthServerMode());

        String externalServerName = settings.getAuthServerName();
        if (mode == Settings.AuthServerMode.EXTERNAL && externalServerName.isBlank()) {
            throw new IllegalArgumentException("auth-server.server-name must not be empty");
        }

        Settings.EmbeddedAuthServerSettings embedded = settings.getEmbeddedAuthServerSettings();

        int port = embedded.getPort();
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("auth-server.embedded.port must be in range 0-65535");
        }
        if (embedded.getMaxConnections() < 1 || embedded.getMaxConnections() > 10_000) {
            throw new IllegalArgumentException(
                    "auth-server.embedded.max-connections must be in range 1-10000");
        }
        if (embedded.getHandshakeTimeoutSeconds() < 1
                || embedded.getHandshakeTimeoutSeconds() > 120) {
            throw new IllegalArgumentException(
                    "auth-server.embedded.handshake-timeout-seconds must be in range 1-120");
        }
        if (embedded.getLoginTimeoutSeconds() < 1 || embedded.getLoginTimeoutSeconds() > 300) {
            throw new IllegalArgumentException(
                    "auth-server.embedded.login-timeout-seconds must be in range 1-300");
        }
    }

    static void validatePremium(Settings settings) {
        warnAllowCrackedOnPremiumNicks(settings);
        warnPremiumAuthServerBypass(settings);

        if (!settings.isPremiumCheckEnabled()) {
            return;
        }

        Settings.PremiumResolverSettings resolver = settings.getPremiumResolverSettings();
        if (resolver == null) {
            return;
        }

        validatePremiumResolverSources(resolver);
        validatePremiumResolverTimeout(resolver);
        validatePremiumResolverTtl(resolver);
        validatePremiumResolverLimits(resolver);
    }

    private static void warnAllowCrackedOnPremiumNicks(Settings settings) {
        if (!settings.isAllowCrackedOnPremiumNicks()) {
            return;
        }
        logger.warn("配置 'premium.allow-cracked-on-premium-nicks=true'——对数据库中没有记录的正版昵称，YouShuiAuth 不会强制 Mojang 认证。盗版客户端可抢先注册正版昵称，防冒名保护被削弱。");
        logger.warn("配置 'premium.allow-cracked-on-premium-nicks=true'——首次连接的正版玩家会被永久分配离线 UUID（Velocity PreLogin 没有「先试正版、失败再离线」模式）。AUTH 表中已有的正版账号仍保留原后端 UUID 身份。");
    }

    private static void warnPremiumAuthServerBypass(Settings settings) {
        if (settings.isPremiumBypassAuthServerEnabled()) {
            logger.warn("配置 'premium.bypass-auth-server=true'——通过 Mojang 验证的 Java 玩家会直连后端服务器，这会削弱 limbo 对「使用有效正版账号的机器人」的隔离。");
        }
    }

    /**
     * Upper bound on how long a lookup may wait for upstream budget before failing closed.
     * Velocity's own {@code connection-timeout} (5s by default) covers the whole PreLogin
     * phase, and the resolver still has to spend {@code request-timeout-ms} on the HTTP call
     * afterwards, so the wait may only claim a fraction of that budget.
     */
    private static final int MAX_RESOLVER_RATE_LIMIT_WAIT_MS = 2000;

    private static final int MIN_BCRYPT_COST = 10;
    private static final int MAX_BCRYPT_COST = 31;

    private static void validateBcryptCost(Settings settings) {
        if (settings.getBcryptCost() < MIN_BCRYPT_COST || settings.getBcryptCost() > MAX_BCRYPT_COST) {
            throw new IllegalArgumentException("BCrypt cost must be in range " + MIN_BCRYPT_COST + "-" + MAX_BCRYPT_COST);
        }
    }

    private static void validateBruteForceSettings(Settings settings) {
        if (settings.getBruteForceMaxAttempts() <= 0) {
            throw new IllegalArgumentException("Brute force max attempts must be > 0");
        }
        if (settings.getBruteForceTimeoutMinutes() <= 0) {
            throw new IllegalArgumentException("Brute force timeout must be > 0");
        }
    }

    private static void validatePasswordLengthSettings(Settings settings) {
        if (settings.getMinPasswordLength() <= 0) {
            throw new IllegalArgumentException("Min password length must be > 0");
        }
        if (settings.getMaxPasswordLength() <= settings.getMinPasswordLength()) {
            throw new IllegalArgumentException("Max password length must be > min password length");
        }
    }

    private static void adjustMaxPasswordLengthIfNeeded(Settings settings) {
        if (settings.getMaxPasswordLength() > 72) {
            logger.warn("BCrypt 密码上限为 72 字节——已将 maxPasswordLength 从 {} 调整为 72",
                    settings.getMaxPasswordLength());
            settings.adjustMaxPasswordLength();
        }
    }

    private static void validateIpLimitRegistrations(Settings settings) {
        if (settings.getIpLimitRegistrations() <= 0) {
            throw new IllegalArgumentException("IP limit registrations must be > 0");
        }
    }

    private static void validatePremiumResolverSources(Settings.PremiumResolverSettings resolver) {
        if (!resolver.isMojangEnabled() && !resolver.isAshconEnabled() && !resolver.isWpmeEnabled()) {
            throw new IllegalArgumentException("Premium resolver: at least one source (mojang/ashcon/wpme) must be enabled");
        }
    }

    private static void validatePremiumResolverTimeout(Settings.PremiumResolverSettings resolver) {
        if (resolver.getRequestTimeoutMs() <= 0) {
            throw new IllegalArgumentException("Premium resolver: request-timeout-ms must be > 0");
        }
    }

    private static void validatePremiumResolverTtl(Settings.PremiumResolverSettings resolver) {
        if (resolver.getHitTtlMinutes() < 0 || resolver.getMissTtlMinutes() < 0) {
            throw new IllegalArgumentException("Premium resolver: TTL in minutes must not be negative");
        }
        if (resolver.getMemoryCacheMaxSize() <= 0) {
            throw new IllegalArgumentException("Premium resolver: memory-cache-max-size must be > 0");
        }
    }

    private static void validatePremiumResolverLimits(Settings.PremiumResolverSettings resolver) {
        if (resolver.getMaxLookupsPerIpPerMinute() <= 0) {
            throw new IllegalArgumentException(
                    "Premium resolver: max-lookups-per-ip-per-minute must be > 0");
        }
        if (resolver.getMaxConcurrentLookups() <= 0) {
            throw new IllegalArgumentException(
                    "Premium resolver: max-concurrent-lookups must be > 0");
        }
        validateResolverRateLimits(resolver.getRateLimit());
    }

    private static void validateResolverRateLimits(Settings.ResolverRateLimitSettings rateLimit) {
        requireNonNegativeRequestBudget(
                "mojang-requests-per-minute", rateLimit.getMojangRequestsPerMinute());
        requireNonNegativeRequestBudget(
                "ashcon-requests-per-minute", rateLimit.getAshconRequestsPerMinute());
        requireNonNegativeRequestBudget(
                "wpme-requests-per-minute", rateLimit.getWpmeRequestsPerMinute());

        int maxWaitMillis = rateLimit.getMaxWaitMillis();
        if (maxWaitMillis < 0 || maxWaitMillis > MAX_RESOLVER_RATE_LIMIT_WAIT_MS) {
            throw new IllegalArgumentException(
                    "Premium resolver: rate-limit-max-wait-ms must be between 0 and "
                            + MAX_RESOLVER_RATE_LIMIT_WAIT_MS
                            + " — a longer wait holds a global lookup slot and risks Velocity's"
                            + " connection-timeout closing the connection mid-login");
        }
    }

    private static void requireNonNegativeRequestBudget(String key, int requestsPerMinute) {
        if (requestsPerMinute < 0) {
            throw new IllegalArgumentException(
                    "Premium resolver: " + key + " must be >= 0 (0 disables the ceiling)");
        }
    }

    static void validateFloodgate(Settings settings) {
        if (!settings.isFloodgateIntegrationEnabled()) {
            return;
        }

        String prefix = requireFloodgatePrefix(settings);
        validateFloodgatePrefix(prefix);
        warnAboutFloodgateConfiguration(prefix);
    }

    private static String requireFloodgatePrefix(Settings settings) {
        String prefix = settings.getFloodgateUsernamePrefix();
        if (prefix == null) {
            throw new IllegalArgumentException("Floodgate username prefix must not be null");
        }
        return prefix;
    }

    private static void validateFloodgatePrefix(String prefix) {
        if (prefix.length() > 16) {
            throw new IllegalArgumentException("Floodgate username prefix must be at most 16 characters");
        }
        if (prefix.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Floodgate username prefix must not contain whitespace");
        }
    }

    private static void warnAboutFloodgateConfiguration(String prefix) {
        if (prefix.isEmpty()) {
            logger.warn("Floodgate 已启用但前缀为空，请确保 Java 与基岩用户名不会冲突");
        }
        if (isCollisionPronePrefix(prefix)) {
            logger.warn("Floodgate 用户名前缀 '{}' 是字母数字，会提高用户名冲突风险", prefix);
        }

        var detectedPrefix = FloodgateDetector.getPlayerPrefix();
        if (detectedPrefix.isEmpty()) {
            logger.warn("配置中启用了 Floodgate，但 Floodgate 插件未加载：基岩玩家识别不会生效");
        } else if (!detectedPrefix.get().equals(prefix)) {
            logger.warn("配置的 Floodgate 前缀 '{}' 与运行中 Floodgate 的前缀 '{}' 不一致，将以运行值为准",
                    prefix, detectedPrefix.get());
        }
    }

    private static boolean isCollisionPronePrefix(String prefix) {
        return !prefix.isEmpty() && prefix.chars().allMatch(SettingsValidator::isUsernameSafeCharacter);
    }

    private static boolean isUsernameSafeCharacter(int value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }
}
