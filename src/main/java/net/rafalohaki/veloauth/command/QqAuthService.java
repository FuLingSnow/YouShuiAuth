package net.rafalohaki.veloauth.command;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rafalohaki.veloauth.VeloAuth;
import net.rafalohaki.veloauth.audit.AuditEventType;
import net.rafalohaki.veloauth.audit.AuditLogService;
import net.rafalohaki.veloauth.lifecycle.ConnectionLifecycleRegistry;
import net.rafalohaki.veloauth.model.CachedAuthUser;
import net.rafalohaki.veloauth.model.QqBinding;
import net.rafalohaki.veloauth.model.QqLinkCode;
import net.rafalohaki.veloauth.model.QqRememberToken;
import net.rafalohaki.veloauth.model.RegisteredPlayer;
import net.rafalohaki.veloauth.qq.QqAuthBridge;
import net.rafalohaki.veloauth.qq.QqDao;
import net.rafalohaki.veloauth.qq.QqLinkCodes;
import net.rafalohaki.veloauth.qq.QqLoginChallenges;
import net.rafalohaki.veloauth.qq.QqNotify;
import net.rafalohaki.veloauth.util.DeviceFingerprints;
import net.rafalohaki.veloauth.qq.QqResult;
import net.rafalohaki.veloauth.qq.QqSettings;
import net.rafalohaki.veloauth.util.FloodgateDetector;
import net.rafalohaki.veloauth.util.PlayerAddressUtils;
import net.rafalohaki.veloauth.util.VirtualThreadExecutorProvider;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapts the QQ bridge to the existing authentication stack.
 *
 * <p>Binding issuance reuses the command-layer ownership rules, and quick login reuses
 * {@link PostAuthFlow} so authorization and backend transfer stay identical to {@code /login}.
 */
public class QqAuthService implements QqAuthBridge {

    public static final String PLATFORM_QQ = "qq";

    /**
     * Marker stored in {@code AUTH.HASH} for QQ-first accounts. It is deliberately not a BCrypt
     * hash: password login treats it as "no password", while premium detection still sees a
     * non-empty hash (so the account is not mistaken for a Mojang account). Password changes
     * are refused for this marker: QQ is the only credential for such accounts.
     */
    public static final String QQ_ONLY_HASH = "!qq-only";

    /** Notification queue cap: drops the oldest entries when the bot falls behind. */
    private static final int MAX_PENDING_NOTIFICATIONS = 200;

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(QqAuthService.class);
    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");
    private static final int BIND_CODE_LENGTH = 8;
    private static final int BIND_CODE_COOLDOWN_SECONDS = 5;
    private static final int LOGIN_REQUEST_COOLDOWN_SECONDS = 5;

    private final VeloAuth plugin;
    private final CommandContext ctx;
    private final QqSettings qqSettings;
    private final QqLoginChallenges loginChallenges;
    private final Cache<UUID, Boolean> bindCodeCooldown = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(BIND_CODE_COOLDOWN_SECONDS))
            .build();
    private final Cache<UUID, Boolean> loginRequestCooldown = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(LOGIN_REQUEST_COOLDOWN_SECONDS))
            .build();
    private final Cache<UUID, ActiveBindCode> activeBindCodes;
    private final QqDeviceApprovalService deviceApprovalService;
    private final java.util.Deque<QqNotify> pendingNotifications = new java.util.concurrent.ConcurrentLinkedDeque<>();

    QqAuthService(VeloAuth plugin, CommandContext ctx) {
        this.plugin = plugin;
        this.ctx = ctx;
        this.qqSettings = ctx.settings().getQqSettings();
        this.loginChallenges = new QqLoginChallenges(qqSettings.getLoginCodeTtlSeconds());
        this.activeBindCodes = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(qqSettings.getBindCodeTtlSeconds()))
                .build();
        this.deviceApprovalService = new QqDeviceApprovalService(plugin, ctx, qqSettings, this);
    }

    /**
     * @return true when the new-device confirmation is usable (QQ bridge, feature switch and
     *         device auto-login all enabled)
     */
    public boolean isDeviceApprovalEnabled() {
        return deviceApprovalService.isDeviceApprovalEnabled();
    }

    /**
     * @return true when the feature is enabled and the DAO is available
     */
    public boolean isAccountQqBound(Player player) {
        if (!isEnabled() || player == null) {
            return false;
        }
        try {
            return dao().findByUuid(PLATFORM_QQ, player.getUniqueId().toString()).isPresent();
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return qqSettings.isEnabled() && ctx.databaseManager().getQqDao() != null;
    }

    public String getJoinUrl() {
        return qqSettings.getJoinUrl();
    }

    public int getLoginCodeTtlSeconds() {
        return qqSettings.getLoginCodeTtlSeconds();
    }

    /**
     * Issues a single-use binding code for an already authenticated player.
     */
    public BindCodeIssue createBindCode(Player player) {
        if (!isEnabled()) {
            return new BindCodeIssue(QqResult.DISABLED, null, 0, false);
        }
        if (player == null) {
            return new BindCodeIssue(QqResult.NOT_AUTHENTICATED, null, 0, false);
        }

        boolean registration = false;
        if (!isAuthenticatedForBinding(player)) {
            QqResult gate = registrationGate(player);
            if (gate != null) {
                return new BindCodeIssue(gate, null, 0, false);
            }
            registration = true;
        }

        if (bindCodeCooldown.asMap().putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) {
            return new BindCodeIssue(QqResult.RATE_LIMITED, null, 0, false);
        }

        String uuid = player.getUniqueId().toString();
        String purpose = registration ? QqLinkCode.PURPOSE_REGISTER : QqLinkCode.PURPOSE_BIND;
        long now = System.currentTimeMillis();
        int ttlSeconds = qqSettings.getBindCodeTtlSeconds();
        try {
            QqDao dao = dao();
            if (dao.findByUuid(PLATFORM_QQ, uuid).isPresent()) {
                return new BindCodeIssue(QqResult.ALREADY_BOUND, null, 0, false);
            }

            // Reuse the live code until it expires: one account always has at most one bind or
            // registration code, so the player cannot end up with several valid codes.
            ActiveBindCode active = activeBindCodes.getIfPresent(player.getUniqueId());
            if (active != null && active.purpose().equals(purpose) && active.expiresAt() > now) {
                Optional<QqLinkCode> stored = dao.findLinkCode(QqLinkCodes.hash(active.code()));
                if (stored.isPresent() && !stored.get().isConsumed() && !stored.get().isExpired(now)) {
                    int remaining = (int) Math.max(1, (active.expiresAt() - now) / 1000);
                    return new BindCodeIssue(QqResult.OK, active.code(), remaining, registration);
                }
            }

            activeBindCodes.invalidate(player.getUniqueId());
            String code = QqLinkCodes.generate(BIND_CODE_LENGTH);
            long expiresAt = now + ttlSeconds * 1000L;
            // Invalidate any previous row for this account/purpose before inserting the new one.
            dao.deleteLinkCodesByUuidAndPurpose(uuid, purpose);
            dao.insertLinkCode(new QqLinkCode(
                    QqLinkCodes.hash(code), uuid, purpose, now, expiresAt));
            activeBindCodes.put(player.getUniqueId(),
                    new ActiveBindCode(code, purpose, expiresAt));
            if (logger.isDebugEnabled()) {
                logger.debug(SECURITY_MARKER,
                        "Issued QQ {} code for UUID {} (ttl={}s)",
                        registration ? "registration" : "bind", uuid, ttlSeconds);
            }
            return new BindCodeIssue(QqResult.OK, code, ttlSeconds, registration);
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to issue QQ bind code for UUID {}", uuid, e);
            return new BindCodeIssue(QqResult.ERROR, null, 0, false);
        }
    }

    /**
     * Button-triggered {@code /login}: rate-limited, but reuses the same live code instead of
     * generating a new one. Cooldown is intentionally 0 for the first request after join.
     */
    public Optional<String> issueLoginCodeManual(
            Player player, ConnectionLifecycleRegistry.Operation operation) {
        if (!isEnabled() || player == null || operation == null) {
            return Optional.empty();
        }
        if (loginRequestCooldown.asMap().putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) {
            return Optional.empty();
        }
        return loginChallenges.issue(player.getUniqueId(), operation);
    }

    @Override
    public QqResult bind(String platform, String groupId, String qqId, String code, String nickname) {
        if (!isEnabled()) {
            return QqResult.DISABLED;
        }
        if (isBlank(qqId) || isBlank(code)) {
            return QqResult.INVALID_CODE;
        }
        try {
            return bindChecked(normalizePlatform(platform), groupId, qqId, code, nickname);
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to bind QQ identity", e);
            return QqResult.ERROR;
        }
    }

    private QqResult bindChecked(String platform, String groupId, String qqId,
                                 String code, String nickname) throws SQLException {
        QqDao dao = dao();
        String codeHash = QqLinkCodes.hash(code);
        long now = System.currentTimeMillis();
        Optional<QqLinkCode> found = dao.findLinkCode(codeHash);
        if (found.isEmpty()) {
            return QqResult.INVALID_CODE;
        }
        QqLinkCode link = found.get();
        boolean registration = QqLinkCode.PURPOSE_REGISTER.equals(link.getPurpose());
        if (link.isConsumed()
                || (!registration && !QqLinkCode.PURPOSE_BIND.equals(link.getPurpose()))) {
            return QqResult.INVALID_CODE;
        }
        if (link.isExpired(now)) {
            return QqResult.CODE_EXPIRED;
        }
        if (dao.findByQq(platform, qqId).isPresent()) {
            return QqResult.ALREADY_BOUND;
        }
        if (dao.findByUuid(platform, link.getUuid()).isPresent()) {
            return QqResult.UUID_BOUND_TO_OTHER;
        }
        if (!dao.consumeLinkCode(codeHash, now)) {
            return QqResult.INVALID_CODE;
        }

        if (registration) {
            return registerAndBind(dao, platform, groupId, qqId, link, now);
        }

        try {
            dao.createBinding(new QqBinding(
                    platform, qqId, link.getUuid(), nickname, now, "bind_code"));
        } catch (SQLException e) {
            return mapBindingFailure(dao, platform, qqId, link.getUuid(), e);
        }
        audit(AuditEventType.QQ_BIND, nickname, null);
        if (logger.isInfoEnabled()) {
            logger.info(SECURITY_MARKER,
                    "QQ identity bound to UUID {} (platform={}, group={})",
                    link.getUuid(), platform, groupId);
        }
        return QqResult.OK;
    }

    /**
     * First-time QQ registration: the nickname has no AUTH row, so the binding itself creates
     * the account with an unusable password marker. QQ remains the only credential; password
     * changes are refused for accounts carrying {@link #QQ_ONLY_HASH}.
     */
    private QqResult registerAndBind(QqDao dao, String platform, String groupId, String qqId,
                                     QqLinkCode link, long now) throws SQLException {
        Player player = plugin.getServer().getPlayer(UUID.fromString(link.getUuid())).orElse(null);
        if (player == null || !player.isActive()
                || !plugin.getConnectionManager().isPlayerOnAuthServer(player)) {
            return QqResult.NOT_ONLINE;
        }
        Boolean exists = accountExists(player);
        if (exists == null) {
            return QqResult.ERROR;
        }
        if (exists) {
            if (!isOrphanedQqOnlyAccount(player)) {
                return QqResult.ACCOUNT_EXISTS;
            }
            if (!deleteStaleQqOnlyAccount(player)) {
                return QqResult.ERROR;
            }
        }

        try {
            dao.createBinding(new QqBinding(
                    platform, qqId, link.getUuid(), player.getUsername(), now, "register"));
        } catch (SQLException e) {
            return mapBindingFailure(dao, platform, qqId, link.getUuid(), e);
        }
        RegisteredPlayer newPlayer = new RegisteredPlayer(
                player.getUsername(), QQ_ONLY_HASH,
                PlayerAddressUtils.getPlayerIp(player), link.getUuid());
        try {
            var result = ctx.databaseManager().registerPlayerIfAbsent(newPlayer).join();
            if (result == null || result.isDatabaseError()) {
                dao.deleteByUuid(platform, link.getUuid());
                return QqResult.ERROR;
            }
            if (!Boolean.TRUE.equals(result.getValue())) {
                dao.deleteByUuid(platform, link.getUuid());
                return QqResult.ACCOUNT_EXISTS;
            }
        } catch (RuntimeException e) {
            logger.error(SECURITY_MARKER, "QQ registration failed for {}", player.getUsername(), e);
            dao.deleteByUuid(platform, link.getUuid());
            return QqResult.ERROR;
        }

        audit(AuditEventType.QQ_BIND, player.getUsername(), "register");
        if (logger.isInfoEnabled()) {
            logger.info(SECURITY_MARKER,
                    "QQ-first account created for {} (platform={}, group={})",
                    player.getUsername(), platform, groupId);
        }

        // Reply to the bot before the backend transfer (PostAuthFlow may exceed the plugin's
        // HTTP timeout); the captured generation keeps deferred authorization connection-bound.
        ConnectionLifecycleRegistry.Operation operation =
                plugin.getConnectionLifecycleRegistry().capture(player);
        if (operation != null) {
            VirtualThreadExecutorProvider.submitTask(
                    () -> authorizeNewAccount(player, newPlayer, operation));
        }
        return QqResult.REGISTERED;
    }

    private void authorizeNewAccount(Player player, RegisteredPlayer dbPlayer,
                                     ConnectionLifecycleRegistry.Operation operation) {
        AuthenticationContext authContext = new AuthenticationContext(
                player, player.getUsername(),
                PlayerAddressUtils.getPlayerAddress(player), dbPlayer, operation);
        if (PostAuthFlow.execute(ctx, authContext, dbPlayer, "QQ registration")) {
            ctx.runIfConnectionCurrent(operation, () -> player.sendMessage(
                    ctx.messages().component("qq.register.success", NamedTextColor.GREEN)));
        }
    }

    /**
     * A nickname alone must not be enough to take over an existing account: registration needs
     * no AUTH row, unless the row is a passwordless QQ account whose binding disappeared.
     *
     * @return rejection reason, or {@code null} when registration may proceed
     */
    private QqResult registrationGate(Player player) {
        if (!plugin.getConnectionManager().isPlayerOnAuthServer(player)) {
            return QqResult.NOT_AUTHENTICATED;
        }
        Boolean exists = accountExists(player);
        if (exists == null) {
            return QqResult.ERROR;
        }
        if (exists && !isOrphanedQqOnlyAccount(player)) {
            return QqResult.ACCOUNT_EXISTS;
        }
        if (exists && logger.isInfoEnabled()) {
            logger.info(SECURITY_MARKER,
                    "Re-registration allowed for orphaned QQ-only account {}",
                    player.getUsername());
        }
        return null;
    }

    private Boolean accountExists(Player player) {
        var result = ctx.databaseManager().findPlayerByNickname(player.getUsername()).join();
        if (result == null || result.isDatabaseError()) {
            return null;
        }
        return result.getValue() != null;
    }

    /**
     * A passwordless QQ account whose binding is gone can never authenticate again, so it is
     * treated as unregistered: the owner may register the nickname once more.
     */
    public boolean isOrphanedQqOnlyAccount(Player player) {
        if (!isEnabled() || player == null) {
            return false;
        }
        RegisteredPlayer account = loadRegisteredPlayer(player);
        if (account == null || !QQ_ONLY_HASH.equals(account.getHash())) {
            return false;
        }
        try {
            return dao().findByUuid(PLATFORM_QQ, player.getUniqueId().toString()).isEmpty();
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to inspect QQ binding for {}",
                    player.getUsername(), e);
            return false;
        }
    }

    private boolean deleteStaleQqOnlyAccount(Player player) {
        String nickname = player.getUsername();
        var result = ctx.databaseManager().deletePlayer(nickname).join();
        if (result == null || result.isDatabaseError() || !Boolean.TRUE.equals(result.getValue())) {
            logger.error(SECURITY_MARKER, "Failed to remove orphaned QQ account {}", nickname);
            return false;
        }
        revokeRememberTokens(player.getUniqueId());
        audit(AuditEventType.QQ_UNBIND, nickname, "orphaned-replaced");
        return true;
    }

    @Override
    public QqResult confirmLogin(String platform, String groupId, String qqId, String code) {
        if (!isEnabled()) {
            return QqResult.DISABLED;
        }
        if (isBlank(qqId) || isBlank(code)) {
            return QqResult.INVALID_CODE;
        }
        Optional<QqLoginChallenges.Challenge> consumed = loginChallenges.consume(code);
        if (consumed.isEmpty()) {
            return QqResult.INVALID_CODE;
        }
        return completeLogin(normalizePlatform(platform), groupId, qqId, consumed.get());
    }

    private QqResult completeLogin(String platform, String groupId, String qqId,
                                   QqLoginChallenges.Challenge challenge) {
        if (challenge.isExpired(System.currentTimeMillis())) {
            return QqResult.CODE_EXPIRED;
        }
        if (!plugin.getConnectionLifecycleRegistry().isCurrent(challenge.operation())) {
            return QqResult.NOT_ONLINE;
        }
        Player player = plugin.getServer().getPlayer(challenge.uuid()).orElse(null);
        if (player == null || !player.isActive()) {
            return QqResult.NOT_ONLINE;
        }
        if (alreadyAuthorized(player)) {
            return QqResult.ALREADY_AUTHORIZED;
        }
        try {
            return authorizeBoundPlayer(platform, groupId, qqId, challenge, player);
        } catch (SQLException | RuntimeException e) {
            logger.error(SECURITY_MARKER, "QQ quick login failed for {}", player.getUsername(), e);
            audit(AuditEventType.QQ_LOGIN_FAIL, player.getUsername(), "error");
            return QqResult.ERROR;
        }
    }

    private QqResult authorizeBoundPlayer(String platform, String groupId, String qqId,
                                          QqLoginChallenges.Challenge challenge,
                                          Player player) throws SQLException {
        Optional<QqBinding> binding = dao().findByQq(platform, qqId);
        if (binding.isEmpty()) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.getUsername(), "not-bound");
            return QqResult.NOT_BOUND;
        }
        if (!binding.get().getUuid().equals(player.getUniqueId().toString())) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.getUsername(), "uuid-mismatch");
            return QqResult.UUID_MISMATCH;
        }
        RegisteredPlayer dbPlayer = loadRegisteredPlayer(player);
        if (dbPlayer == null) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.getUsername(), "not-registered");
            return QqResult.NOT_BOUND;
        }
        if (requiresTotp(dbPlayer)) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.getUsername(), "totp-required");
            return QqResult.TOTP_REQUIRED;
        }

        AuthenticationContext authContext = new AuthenticationContext(
                player, player.getUsername(),
                PlayerAddressUtils.getPlayerAddress(player),
                dbPlayer, challenge.operation());
        if (!PostAuthFlow.execute(ctx, authContext, dbPlayer, "QQ quick login")) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.getUsername(), "transfer-failed");
            return QqResult.ERROR;
        }

        ctx.runIfConnectionCurrent(challenge.operation(), () -> player.sendMessage(
                ctx.messages().component("qq.login.success", NamedTextColor.GREEN)));
        audit(AuditEventType.QQ_LOGIN_OK, player.getUsername(), null);
        if (logger.isInfoEnabled()) {
            logger.info(SECURITY_MARKER,
                    "QQ quick login authorized for {} (platform={}, group={})",
                    player.getUsername(), platform, groupId);
        }
        return QqResult.OK;
    }

    boolean alreadyAuthorized(Player player) {
        String playerIp = PlayerAddressUtils.getPlayerIp(player);
        return ctx.authCache().isPlayerAuthorized(player.getUniqueId(), playerIp)
                && ctx.authCache().hasActiveSession(
                        player.getUniqueId(), player.getUsername(), playerIp);
    }

    boolean requiresTotp(RegisteredPlayer dbPlayer) {
        return ctx.settings().getTwoFactorSettings().isEnabled()
                && dbPlayer.getTotpToken() != null
                && !dbPlayer.getTotpToken().isBlank();
    }

    /**
     * Records the client fingerprint after a successful offline authentication so the next join
     * from the same device can skip limbo. Premium connections are excluded: Mojang re-verifies
     * them on every login and they may have no AUTH row.
     */
    public void rememberDevice(Player player) {
        if (!isEnabled() || player == null || player.isOnlineMode()) {
            return;
        }
        try {
            rememberDeviceInternal(player);
        } catch (RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Device record skipped for {}", player.getUsername());
        }
    }

    private void rememberDeviceInternal(Player player) {
        RegisteredPlayer dbPlayer = loadRegisteredPlayer(player);
        if (dbPlayer == null) {
            return;
        }
        boolean includeSubnet = ctx.settings().isAutoLoginRequireSameSubnet();
        String fingerprint = DeviceFingerprints.fingerprint(player, includeSubnet);
        String hash = DeviceFingerprints.hash(player.getUniqueId().toString(), fingerprint);
        int maxAgeDays = Math.max(1, ctx.settings().getAutoLoginMaxAgeDays());
        long now = System.currentTimeMillis();
        try {
            // Single device per account: a new login replaces any previous record.
            dao().deleteRememberTokensByUuid(player.getUniqueId().toString());
            dao().insertRememberToken(new QqRememberToken(
                    hash, player.getUniqueId().toString(), now,
                    now + maxAgeDays * 86_400_000L));
        } catch (SQLException e) {
            logger.warn(SECURITY_MARKER, "Failed to persist device record for {}",
                    player.getUsername());
        }
    }

    /**
     * Validates the device fingerprint of a reconnecting player and restores authorization.
     *
     * @return true when the connection is now authorized by the stored device
     */
    public boolean tryDeviceLogin(Player player, String fingerprint) {
        if (!isEnabled() || player == null || player.isOnlineMode()
                || fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        String hash = DeviceFingerprints.hash(player.getUniqueId().toString(), fingerprint);
        long now = System.currentTimeMillis();
        try {
            QqDao dao = dao();
            Optional<QqRememberToken> stored = dao.findRememberToken(hash);
            if (stored.isEmpty() || !stored.get().isUsable(now)
                    || !stored.get().getUuid().equals(player.getUniqueId().toString())) {
                logger.debug(SECURITY_MARKER,
                        "Device auto-login rejected for {} (no matching device)",
                        player.getUsername());
                return false;
            }

            authorizeDevice(player);
            stored.get().markSeen(now);
            dao.touchRememberToken(stored.get());

            player.sendMessage(ctx.messages().component("qq.login.remembered", NamedTextColor.GREEN));
            audit(AuditEventType.LOGIN_OK, player.getUsername(), "device-fingerprint");
            if (logger.isInfoEnabled()) {
                logger.info(SECURITY_MARKER,
                        "Device auto-login authorized for {}", player.getUsername());
            }
            queueAutoLoginNotify(player, formatRemaining(
                    Math.max(0, stored.get().getExpiresAt() - now)));
            return true;
        } catch (SQLException | RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Device auto-login failed for {}", player.getUsername());
            return false;
        }
    }

    void authorizeDevice(Player player) {
        String playerIp = PlayerAddressUtils.getPlayerIp(player);
        ctx.authCache().authorize(player.getUniqueId(),
                new CachedAuthUser(player.getUniqueId(), player.getUsername(), playerIp,
                        System.currentTimeMillis(), false, null),
                player.getUsername(), playerIp);
        ctx.resetSecurityCounters(
                PlayerAddressUtils.getPlayerAddress(player), player.getUsername());
        plugin.getAuthTimeoutScheduler().cancel(player.getUniqueId());
    }

    // ===== New-device approvals (Y/N reply in the bound QQ group) =====

    /**
     * Freezes an unrecognised-device login until the bound QQ replies in the group.
     *
     * <p>Only triggers when the account already has a usable device record that does not match
     * the current fingerprint; a first-ever login, a premium connection, a Bedrock bypass login
     * and 2FA accounts return {@code false} unchanged.
     *
     * @return true when the login is now waiting for the reply; false means "fall back to limbo"
     */
    public boolean requestDeviceApproval(Player player,
                                         ConnectionLifecycleRegistry.Operation operation,
                                         String fingerprint,
                                         Runnable onTimeout) {
        return deviceApprovalService.requestDeviceApproval(
                player, operation, fingerprint, onTimeout);
    }

    @Override
    public QqResult decideDeviceApproval(String platform, String groupId, String qqId,
                                         String decision, String approvalId) {
        return deviceApprovalService.decideDeviceApproval(
                platform, groupId, qqId, decision, approvalId);
    }

    /**
     * Cancels a pending approval when the connection disappears before the group replies.
     */
    public void cancelDeviceApproval(Player player) {
        deviceApprovalService.cancelDeviceApproval(player);
    }
    /**
     * Revokes every remember-device token of an account (password change, unbind, admin action).
     */
    public void revokeRememberTokens(UUID uuid) {
        if (!isEnabled() || uuid == null) {
            return;
        }
        try {
            dao().revokeAllForUuid(uuid.toString());
        } catch (SQLException e) {
            logger.warn(SECURITY_MARKER, "Failed to revoke remember-device tokens for {}", uuid);
        }
    }

    // ===== Auto-login notifications (delivered by the AstrBot poller) =====

    /**
     * Enqueues an auto-login notification for the bound QQ; the bot handles routing. The
     * notification carries an opaque stopId so the owner can quote-reply {@code 阻止} to kick
     * the session and revoke device trust.
     */
    public void queueAutoLoginNotify(Player player, String remainingTime) {
        deviceApprovalService.queueAutoLoginNotify(player, remainingTime);
    }

    @Override
    public QqResult stopAutoLogin(String platform, String groupId, String qqId, String stopId) {
        return deviceApprovalService.stopAutoLogin(platform, groupId, qqId, stopId);
    }
    void enqueueNotification(QqNotify item) {
        pendingNotifications.addLast(item);
        while (pendingNotifications.size() > MAX_PENDING_NOTIFICATIONS) {
            pendingNotifications.pollFirst();
        }
    }

    private String formatRemaining(long remainingMillis) {
        long days = remainingMillis / 86_400_000L;
        long hours = (remainingMillis % 86_400_000L) / 3_600_000L;
        if (days > 0) {
            return hours > 0 ? days + " 天 " + hours + " 小时" : days + " 天";
        }
        return hours + " 小时";
    }

    /**
     * Returns and clears the pending notifications for the bot poller.
     */
    @Override
    public java.util.List<QqNotify> pollNotifications() {
        java.util.List<QqNotify> drained = new java.util.ArrayList<>();
        QqNotify item;
        while ((item = pendingNotifications.pollFirst()) != null) {
            drained.add(item);
        }
        return drained;
    }

    RegisteredPlayer loadRegisteredPlayer(Player player) {
        var result = ctx.databaseManager().findPlayerByNickname(player.getUsername()).join();
        if (result == null || result.isDatabaseError()) {
            return null;
        }
        return result.getValue();
    }

    /**
     * A concurrent bind can lose the unique-index race after both sides passed the read checks.
     * Re-read the rows to return the accurate business code instead of a generic error.
     */
    private QqResult mapBindingFailure(
            QqDao dao, String platform, String qqId, String uuid, SQLException failure) {
        try {
            if (dao.findByQq(platform, qqId).isPresent()) {
                return QqResult.ALREADY_BOUND;
            }
            if (dao.findByUuid(platform, uuid).isPresent()) {
                return QqResult.UUID_BOUND_TO_OTHER;
            }
        } catch (SQLException ignored) {
            // fall through to the generic error
        }
        logger.error(SECURITY_MARKER, "Failed to create QQ binding for UUID {}", uuid, failure);
        return QqResult.ERROR;
    }

    /**
     * Ownership proof for binding: a valid session, a Mojang-verified connection, or a
     * Floodgate-confirmed Bedrock player (who never enters limbo and has no AUTH row).
     */
    private boolean isAuthenticatedForBinding(Player player) {
        String playerIp = PlayerAddressUtils.getPlayerIp(player);
        if (ctx.authCache().isPlayerAuthorized(player.getUniqueId(), playerIp)) {
            return true;
        }
        if (player.isOnlineMode()) {
            return true;
        }
        return ctx.settings().isFloodgateIntegrationEnabled()
                && FloodgateDetector.isBedrockPlayer(player.getUniqueId());
    }

    private String normalizePlatform(String platform) {
        return isBlank(platform) ? PLATFORM_QQ : platform.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    QqDao dao() {
        return ctx.databaseManager().getQqDao();
    }

    void audit(AuditEventType type, String nickname, String details) {
        AuditLogService audit = plugin.getAuditLogService();
        if (audit != null) {
            audit.save(type, nickname, null, details);
        }
    }

    /**
     * Result of a bind-code request; {@code ttlSeconds} is zero unless {@code result} is OK.
     */
    public record BindCodeIssue(QqResult result, String code, int ttlSeconds, boolean createsAccount) {

        public boolean isOk() {
            return result != null && result.isOk();
        }
    }

    private record ActiveBindCode(String code, String purpose, long expiresAt) {
    }
}
