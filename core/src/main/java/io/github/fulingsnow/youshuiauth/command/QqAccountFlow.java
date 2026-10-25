package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.model.QqBinding;
import io.github.fulingsnow.youshuiauth.model.QqLinkCode;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.qq.QqAuthFlow;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.qq.QqLinkCodes;
import io.github.fulingsnow.youshuiauth.qq.QqLoginChallenges;
import io.github.fulingsnow.youshuiauth.qq.QqResult;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * QQ 绑定码消费与 QQ 快捷登录：代理端与服务端共用的唯一实现。
 *
 * <p>平台只负责把群消息送进来（bridge），校验顺序、账号创建、会话授权与审计都在这里，
 * 避免两端各写一份后出现「Paper 先建号再校验」「Paper 无二因子门禁」这类分叉。</p>
 */
public final class QqAccountFlow {

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(QqAccountFlow.class);

    private final AuthServices services;
    private CommandContext ctx;

    public QqAccountFlow(AuthServices services) {
        this.services = services;
    }

    /** 平台用例可能只为绑定/登录准备这个对象，不能在建构造时就碰 services（测试与关闭态会为 null）。 */
    private CommandContext ctx() {
        CommandContext current = ctx;
        if (current == null) {
            current = CommandContext.forPlatform(services, services.databaseManager(),
                    services.authCache(), services.settings(), services.messages());
            ctx = current;
        }
        return current;
    }

    public QqResult bind(String platform, String groupId, String qqId, String code, String nickname) {
        if (!services.qq().isEnabled()) {
            return QqResult.DISABLED;
        }
        if (isBlank(qqId) || isBlank(code)) {
            return QqResult.INVALID_CODE;
        }
        try {
            return bindChecked(platform, groupId, qqId, code, nickname);
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to bind QQ identity", e);
            return QqResult.ERROR;
        }
    }

    public QqResult confirmLogin(String platform, String groupId, String qqId, String code) {
        if (!services.qq().isEnabled()) {
            return QqResult.DISABLED;
        }
        if (isBlank(qqId) || isBlank(code)) {
            return QqResult.INVALID_CODE;
        }
        Optional<QqLoginChallenges.Challenge> peeked = services.qq().peekLoginCode(code);
        if (peeked.isEmpty()) {
            return QqResult.INVALID_CODE;
        }

        QqResult precheck = precheckLogin(peeked.get());
        if (precheck != null) {
            return precheck;
        }
        Optional<QqLoginChallenges.Challenge> consumed = services.qq().consumeLoginCode(code);
        if (consumed.isEmpty()) {

            return QqResult.INVALID_CODE;
        }
        return completeLogin(platform, groupId, qqId, consumed.get());
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

        if (registration) {
            QqResult gate = precheckRegistration(link);
            if (gate != null) {
                return gate;
            }
        }
        if (!dao.consumeLinkCode(codeHash, now)) {
            return QqResult.INVALID_CODE;
        }
        if (registration) {
            return registerAndBind(dao, platform, groupId, qqId, link, now);
        }
        try {
            dao.createBinding(new QqBinding(platform, qqId, link.getUuid(), nickname, now, "bind_code"));
        } catch (SQLException e) {
            return mapBindingFailure(dao, platform, qqId, link.getUuid(), e);
        }
        audit(AuditEventType.QQ_BIND, nickname, null);
        logger.info(SECURITY_MARKER, "QQ identity bound to UUID {} (platform={}, group={})",
                link.getUuid(), platform, groupId);
        authorizeAfterBind(link.getUuid());
        return QqResult.OK;
    }

    /** 首绑建号的只读前置检查；返回 null 表示可以继续消费绑定码。 */
    private QqResult precheckRegistration(QqLinkCode link) {
        Optional<AuthPlayer> online = services.platformAccess()
                .player(UUID.fromString(link.getUuid()));
        if (online.isEmpty() || !online.get().isActive()) {
            return QqResult.NOT_ONLINE;
        }
        RegisteredPlayer existing = services.databaseManager()
                .findRegisteredPlayerByUuid(online.get().uniqueId());
        if (existing != null && !isOrphanedQqOnlyAccount(existing)) {
            return QqResult.ACCOUNT_EXISTS;
        }

        var byName = services.databaseManager()
                .findPlayerByNickname(online.get().username()).join();
        RegisteredPlayer nameOwner = byName != null && !byName.isDatabaseError()
                ? byName.getValue() : null;
        if (nameOwner != null
                && !nameOwner.getUuid().equalsIgnoreCase(online.get().uniqueId().toString())
                && !isOrphanedQqOnlyAccount(nameOwner)) {
            return QqResult.ACCOUNT_EXISTS;
        }
        return null;
    }

    private QqResult registerAndBind(QqDao dao, String platform, String groupId, String qqId,
                                     QqLinkCode link, long now) throws SQLException {
        Optional<AuthPlayer> online = services.platformAccess()
                .player(UUID.fromString(link.getUuid()));
        if (online.isEmpty() || !online.get().isActive()) {
            return QqResult.NOT_ONLINE;
        }
        AuthPlayer player = online.get();
        RegisteredPlayer existing = services.databaseManager()
                .findRegisteredPlayerByUuid(player.uniqueId());
        if (existing != null) {
            if (!isOrphanedQqOnlyAccount(existing)) {
                return QqResult.ACCOUNT_EXISTS;
            }
            if (!deleteStaleQqOnlyAccount(player, existing)) {
                return QqResult.ERROR;
            }
        }

        try {
            dao.createBinding(new QqBinding(platform, qqId, link.getUuid(),
                    player.username(), now, "register"));
        } catch (SQLException e) {
            return mapBindingFailure(dao, platform, qqId, link.getUuid(), e);
        }
        RegisteredPlayer newPlayer = new RegisteredPlayer(player.username(),
                QqAuthFlow.QQ_ONLY_HASH, PlayerAddressUtils.getPlayerIp(player), link.getUuid());
        try {
            var result = services.databaseManager().registerPlayerIfAbsent(newPlayer).join();
            if (result == null || result.isDatabaseError()) {
                dao.deleteByUuid(platform, link.getUuid());
                return QqResult.ERROR;
            }
            if (!Boolean.TRUE.equals(result.getValue())) {
                dao.deleteByUuid(platform, link.getUuid());
                return QqResult.ACCOUNT_EXISTS;
            }
        } catch (RuntimeException e) {
            logger.error(SECURITY_MARKER, "QQ registration failed for {}", player.username(), e);
            dao.deleteByUuid(platform, link.getUuid());
            return QqResult.ERROR;
        }

        audit(AuditEventType.QQ_BIND, player.username(), "register");
        logger.info(SECURITY_MARKER, "QQ-first account created for {} (platform={}, group={})",
                player.username(), platform, groupId);

        ConnectionRef operation = services.connections().capture(player);
        if (operation != null) {
            VirtualThreadExecutorProvider.submitTask(
                    () -> authorizeNewAccount(player, newPlayer, operation));
        }
        return QqResult.REGISTERED;
    }

    private void authorizeNewAccount(AuthPlayer player, RegisteredPlayer dbPlayer,
                                     ConnectionRef operation) {
        AuthenticationContext authContext = new AuthenticationContext(player, player.username(),
                player.address(), dbPlayer, operation);
        if (PostAuthFlow.execute(ctx(), authContext, dbPlayer, "QQ registration")) {
            services.connections().runIfCurrent(operation, () -> player.sendMessage(
                    services.messages().component("qq.register.success", NamedTextColor.GREEN)));
        }
    }

    private void authorizeAfterBind(String uuidText) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException malformed) {
            return;
        }
        Optional<AuthPlayer> online = services.platformAccess().player(uuid);
        if (online.isEmpty() || !online.get().isActive()) {
            return;
        }
        AuthPlayer player = online.get();
        ConnectionRef operation = services.connections().capture(player);
        if (operation == null) {
            return;
        }
        VirtualThreadExecutorProvider.submitTask(() -> {
            RegisteredPlayer dbPlayer = services.databaseManager()
                    .findRegisteredPlayerByUuid(player.uniqueId());
            if (dbPlayer == null) {
                return;
            }
            AuthenticationContext authContext = new AuthenticationContext(player,
                    player.username(), player.address(), dbPlayer, operation);
            if (PostAuthFlow.execute(ctx(), authContext, dbPlayer, "QQ bind")) {
                services.connections().runIfCurrent(operation, () -> player.sendMessage(
                        services.messages().component("qq.bind.success", NamedTextColor.GREEN)));
            }
        });
    }

    /** 登录码的只读预检；返回 null 表示可以消费。 */
    private QqResult precheckLogin(QqLoginChallenges.Challenge challenge) {
        if (challenge.isExpired(System.currentTimeMillis())) {
            return QqResult.CODE_EXPIRED;
        }
        if (!services.connections().isCurrent(challenge.operation())) {
            return QqResult.NOT_ONLINE;
        }
        Optional<AuthPlayer> online = services.platformAccess().player(challenge.uuid());
        if (online.isEmpty() || !online.get().isActive()) {
            return QqResult.NOT_ONLINE;
        }
        if (alreadyAuthorized(online.get())) {
            return QqResult.ALREADY_AUTHORIZED;
        }
        return null;
    }

    private QqResult completeLogin(String platform, String groupId, String qqId,
                                   QqLoginChallenges.Challenge challenge) {

        AuthPlayer player = services.platformAccess().player(challenge.uuid()).orElse(null);
        if (player == null) {
            return QqResult.NOT_ONLINE;
        }
        try {
            return authorizeBoundPlayer(platform, groupId, qqId, challenge, player);
        } catch (SQLException | RuntimeException e) {
            logger.error(SECURITY_MARKER, "QQ quick login failed for {}", player.username(), e);
            audit(AuditEventType.QQ_LOGIN_FAIL, player.username(), "error");
            return QqResult.ERROR;
        }
    }

    private QqResult authorizeBoundPlayer(String platform, String groupId, String qqId,
                                          QqLoginChallenges.Challenge challenge,
                                          AuthPlayer player) throws SQLException {
        Optional<QqBinding> binding = dao().findByQq(platform, qqId);
        if (binding.isEmpty()) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.username(), "not-bound");
            return QqResult.NOT_BOUND;
        }
        if (!binding.get().getUuid().equals(player.uniqueId().toString())) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.username(), "uuid-mismatch");
            return QqResult.UUID_MISMATCH;
        }
        RegisteredPlayer dbPlayer = services.databaseManager()
                .findRegisteredPlayerByUuid(player.uniqueId());
        if (dbPlayer == null) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.username(), "not-registered");
            return QqResult.NOT_BOUND;
        }
        if (requiresTotp(dbPlayer)) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.username(), "totp-required");
            return QqResult.TOTP_REQUIRED;
        }

        AuthenticationContext authContext = new AuthenticationContext(player, player.username(),
                player.address(), dbPlayer, challenge.operation());
        if (!PostAuthFlow.execute(ctx(), authContext, dbPlayer, "QQ quick login")) {
            audit(AuditEventType.QQ_LOGIN_FAIL, player.username(), "transfer-failed");
            return QqResult.ERROR;
        }
        services.connections().runIfCurrent(challenge.operation(), () -> player.sendMessage(
                services.messages().component("qq.login.success", NamedTextColor.GREEN)));
        audit(AuditEventType.QQ_LOGIN_OK, player.username(), null);
        logger.info(SECURITY_MARKER, "QQ quick login authorized for {} (platform={}, group={})",
                player.username(), platform, groupId);
        return QqResult.OK;
    }

    private boolean alreadyAuthorized(AuthPlayer player) {
        String ip = PlayerAddressUtils.getPlayerIp(player);
        return services.authCache().isPlayerAuthorized(player.uniqueId(), ip)
                && services.authCache().hasActiveSession(player.uniqueId(), player.username(), ip);
    }

    private boolean requiresTotp(RegisteredPlayer player) {
        return services.settings().getTwoFactorSettings().isEnabled()
                && player.getTotpToken() != null && !player.getTotpToken().isBlank();
    }

    private boolean isOrphanedQqOnlyAccount(RegisteredPlayer account) {
        if (!QqAuthFlow.QQ_ONLY_HASH.equals(account.getHash())) {
            return false;
        }
        try {
            return dao().findByUuid(QqAuthFlow.PLATFORM_QQ, account.getUuid()).isEmpty();
        } catch (SQLException e) {
            logger.error(SECURITY_MARKER, "Failed to inspect orphaned QQ account {}",
                    account.getNickname());
            return false;
        }
    }

    private boolean deleteStaleQqOnlyAccount(AuthPlayer player, RegisteredPlayer account) {
        var result = services.databaseManager().deletePlayer(account.getNickname()).join();
        if (result == null || result.isDatabaseError() || !Boolean.TRUE.equals(result.getValue())) {
            logger.error(SECURITY_MARKER, "Failed to remove orphaned QQ account {}",
                    account.getNickname());
            return false;
        }
        services.qq().revokeRememberTokens(player.uniqueId());
        audit(AuditEventType.QQ_UNBIND, account.getNickname(), "orphaned-replaced");
        return true;
    }

    private QqResult mapBindingFailure(QqDao dao, String platform, String qqId, String uuid,
                                       SQLException failure) throws SQLException {
        if (dao.findByQq(platform, qqId).isPresent()) {
            return QqResult.ALREADY_BOUND;
        }
        if (dao.findByUuid(platform, uuid).isPresent()) {
            return QqResult.UUID_BOUND_TO_OTHER;
        }
        logger.error(SECURITY_MARKER, "Failed to create QQ binding", failure);
        return QqResult.ERROR;
    }

    private void audit(AuditEventType type, String username, String details) {
        var auditLog = services.auditLogService();
        if (auditLog != null && auditLog.isEnabled()) {
            auditLog.save(type, username, null, details);
        }
    }

    private QqDao dao() {
        return services.databaseManager().getQqDao();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
