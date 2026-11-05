package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.util.VelocityPlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.YouShuiAuth;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.lifecycle.ConnectionLifecycleRegistry;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.model.QqBinding;
import io.github.fulingsnow.youshuiauth.model.QqLinkCode;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.qq.QqAuthBridge;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.qq.QqLinkCodes;
import io.github.fulingsnow.youshuiauth.qq.QqLoginChallenges;
import io.github.fulingsnow.youshuiauth.qq.QqNotify;
import io.github.fulingsnow.youshuiauth.util.DeviceFingerprints;
import io.github.fulingsnow.youshuiauth.qq.QqResult;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.util.FloodgateDetector;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;
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
public class QqAuthService implements QqAuthBridge, io.github.fulingsnow.youshuiauth.platform.QqOps {
    /**
     * core 的 QQ 实现按需创建：测试上下文里的 services 可能为空，构造期不能访问。
     */
    public io.github.fulingsnow.youshuiauth.auth.DeviceApprovalFlow deviceApprovalFlow() {
        return deviceApprovalService == null ? null : deviceApprovalService.flow();
    }

    private io.github.fulingsnow.youshuiauth.qq.QqAuthFlow flow() {
        io.github.fulingsnow.youshuiauth.qq.QqAuthFlow current = flow;
        if (current != null) {
            return current;
        }
        io.github.fulingsnow.youshuiauth.platform.AuthServices services = ctx.services();
        current = new io.github.fulingsnow.youshuiauth.qq.QqAuthFlow(qqSettings, ctx.settings(),
                ctx.databaseManager()::getQqDao, services == null ? null : services.timeouts(),
                logger, "velocity", ctx.authCache(), ctx.databaseManager());
        flow = current;
        return current;
    }

    public static final String PLATFORM_QQ = io.github.fulingsnow.youshuiauth.platform.QqOps.PLATFORM_QQ;

    /**
     * Marker stored in {@code AUTH.HASH} for QQ-first accounts. It is deliberately not a BCrypt
     * hash: password login treats it as "no password", while premium detection still sees a
     * non-empty hash (so the account is not mistaken for a Mojang account). Password changes
     * are refused for this marker: QQ is the only credential for such accounts.
     */
    public static final String QQ_ONLY_HASH =
            io.github.fulingsnow.youshuiauth.platform.QqOps.QQ_ONLY_HASH;

    /** Notification queue cap: drops the oldest entries when the bot falls behind. */
    private static final int MAX_PENDING_NOTIFICATIONS = 200;

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(QqAuthService.class);
    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private final YouShuiAuth plugin;
    private io.github.fulingsnow.youshuiauth.qq.QqAuthFlow flow;
    private final CommandContext ctx;
    private final QqSettings qqSettings;
    private final QqDeviceApprovalService deviceApprovalService;
    private final io.github.fulingsnow.youshuiauth.command.QqAccountFlow accountFlow;
    private final java.util.Deque<QqNotify> pendingNotifications = new java.util.concurrent.ConcurrentLinkedDeque<>();

    QqAuthService(YouShuiAuth plugin, CommandContext ctx) {
        this.plugin = plugin;
        this.ctx = ctx;
        this.qqSettings = ctx.settings().getQqSettings();
        this.deviceApprovalService = new QqDeviceApprovalService(plugin, ctx, qqSettings, this);
        this.accountFlow = new io.github.fulingsnow.youshuiauth.command.QqAccountFlow(ctx.services());
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
    public boolean isAccountQqBound(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
        return velocity != null && isAccountQqBound(velocity);
    }

    public boolean isAccountQqBound(Player player) {
        return player != null && flow().isAccountQqBound(
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player));
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

    public int getDeviceApprovalTimeoutSeconds() {
        return qqSettings.getDeviceApprovalTimeoutSeconds();
    }

    /**
     * Issues a single-use binding code for an already authenticated player.
     */
    public BindCodeIssue createBindCode(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
        return velocity == null
                ? new BindCodeIssue(QqResult.NOT_AUTHENTICATED, null, 0, false)
                : createBindCode(velocity);
    }

    public BindCodeIssue createBindCode(Player player) {
        io.github.fulingsnow.youshuiauth.platform.QqOps.BindCodeIssue issue =
                flow().createBindCodeOutcome(io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player));
        return new BindCodeIssue(issue.result(), issue.code(), issue.ttlSeconds(),
                issue.createsAccount());
    }

    public io.github.fulingsnow.youshuiauth.platform.QqOps.BindCodeIssue createBindCodeOutcome(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        return flow().createBindCodeOutcome(player);
    }

    /** Window that must contain a login code and, when enabled, the TOTP follow-up prompt. */
    public int loginCodeWindowSeconds() {
        return flow().loginWindowSeconds();
    }

    /**
     * Button-triggered {@code /login}: rate-limited, but reuses the same live code instead of
     * generating a new one. Cooldown is intentionally 0 for the first request after join.
     */
    public Optional<String> issueLoginCodeManual(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, ConnectionRef operation) {
        Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
        return velocity == null ? Optional.empty() : issueLoginCodeManual(velocity, operation);
    }

    public Optional<String> issueLoginCodeManual(
            Player player, ConnectionRef operation) {
        return flow().issueLoginCodeManual(
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player), operation);
    }

    public boolean isDeviceApprovalPending(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
        return velocity != null && isDeviceApprovalPending(velocity);
    }

    public boolean isDeviceApprovalPending(Player player) {
        return deviceApprovalService.isPending(player);
    }

    @Override
    public boolean isQqBound(String platform, String qqId) {
        if (!isEnabled() || isBlank(qqId)) {
            return false;
        }
        try {
            return dao().findByQq(normalizePlatform(platform), qqId.trim()).isPresent();
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to inspect QQ binding for {}", qqId, e);
            return false;
        }
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
            return accountFlow.bind(normalizePlatform(platform), groupId, qqId, code, nickname);
        } catch (RuntimeException e) {
            logger.error(SECURITY_MARKER, "Failed to bind QQ identity", e);
            return QqResult.ERROR;
        }
    }

    /**
     * First-time QQ registration: the nickname has no AUTH row, so the binding itself creates
     * the account with an unusable password marker. QQ remains the only credential; password
     * changes are refused for accounts carrying {@link #QQ_ONLY_HASH}.
     */

    /**
     * A passwordless QQ account whose binding is gone can never authenticate again, so it is
     * treated as unregistered: the owner may register the nickname once more.
     */
    public boolean isOrphanedQqOnlyAccount(Player player) {
        if (!isEnabled() || player == null) {
            return false;
        }
        var account = ctx.databaseManager().findRegisteredPlayerByUuid(player.getUniqueId());
        return account != null && flow().isOrphanedQqOnlyAccount(account);
    }

    @Override
    public QqResult confirmLogin(String platform, String groupId, String qqId, String code) {
        if (!isEnabled()) {
            return QqResult.DISABLED;
        }
        if (isBlank(qqId) || isBlank(code)) {
            return QqResult.INVALID_CODE;
        }
        return accountFlow.confirmLogin(normalizePlatform(platform), groupId, qqId, code);
    }

    boolean alreadyAuthorized(Player player) {
        return io.github.fulingsnow.youshuiauth.runtime.AuthRuntime.isAuthorizedWithSession(
                ctx.authCache(),
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player),
                VelocityPlayerAddressUtils.getPlayerIp(player));
    }

    /**
     * Records the client fingerprint after a successful offline authentication so the next join
     * from the same device can skip limbo. Premium connections are excluded: Mojang re-verifies
     * them on every login and they may have no AUTH row.
     */
    public void rememberDevice(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        Player velocityPlayer = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
        if (velocityPlayer != null) {
            rememberDevice(velocityPlayer);
        }
    }

    public void rememberDevice(Player player) {
        if (!isEnabled() || player == null || player.isOnlineMode()) {
            return;
        }
        plugin.getAutoLoginService().rememberDevice(
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player));
    }

    void authorizeDevice(Player player) {
        String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);
        ctx.authCache().authorize(player.getUniqueId(),
                new CachedAuthUser(player.getUniqueId(), player.getUsername(), playerIp,
                        System.currentTimeMillis(), false, null),
                player.getUsername(), playerIp);
        ctx.resetSecurityCounters(
                VelocityPlayerAddressUtils.getPlayerAddress(player), player.getUsername());
        plugin.getAuthTimeoutScheduler().cancel(player.getUniqueId());
    }

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
                                         ConnectionRef operation,
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
    public void cancelDeviceApproval(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        Player velocity = io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
        if (velocity != null) {
            cancelDeviceApproval(velocity);
        }
    }

    public void cancelDeviceApproval(Player player) {
        deviceApprovalService.cancelDeviceApproval(player);
    }
    /**
     * Revokes every remember-device token of an account (password change, unbind, admin action).
     */
    public void revokeRememberTokens(UUID uuid) {
        flow().revokeRememberTokens(uuid);
    }

    /**
     * 为绑定 QQ 发起一次免密提醒（携带 opaque stopId，本人可引用回复「阻止」撤销）。
     * 通知直接进 core 的队列，桥接轮询时由 pollNotifications() 统一取走。
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

    /**
     * Returns and clears the pending notifications for the bot poller.
     */
    @Override
    public java.util.List<QqNotify> pollNotifications() {
        java.util.List<QqNotify> drained = new java.util.ArrayList<>();
        if (deviceApprovalService != null) {
            drained.addAll(deviceApprovalService.pollNotifications());
        }
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

    @Override
    public Optional<QqLoginChallenges.Challenge> peekLoginCode(String code) {
        return flow().peekLoginCode(code);
    }

    @Override
    public Optional<QqLoginChallenges.Challenge> consumeLoginCode(String code) {
        return flow().consumeLoginCode(code);
    }
}
