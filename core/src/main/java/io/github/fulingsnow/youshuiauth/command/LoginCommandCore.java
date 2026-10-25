package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import at.favre.lib.crypto.bcrypt.BCrypt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.auth.totp.PendingTotpState;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.SecurityUtils;

import java.net.InetAddress;

/**
 * Handles the /login command.
 * Validates credentials against the database and delegates
 * post-auth flow to {@link PostAuthFlow}.
 * <p>
 * This command is hidden from players who are already authenticated
 * and not on the auth server (via {@link #hasPermission}).
 */
public class LoginCommandCore extends AbstractAuthCommand {

    public LoginCommandCore(CommandContext ctx) {
        super(ctx, sender -> CommandHelper.isPlayerOnAuthServerAndNeedsAuthentication(sender, ctx));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void execute(io.github.fulingsnow.youshuiauth.platform.AuthSender sender, String[] args) {
        io.github.fulingsnow.youshuiauth.platform.AuthPlayer player =
                CommandHelper.validatePlayerSource(sender, ctx.messages());
        if (player == null) {
            return;
        }
        io.github.fulingsnow.youshuiauth.platform.QqOps qqService = ctx.qqService();
        if (qqService != null && qqService.isDeviceApprovalPending(player)) {
            player.sendMessage(ctx.messages().component("qq.device_approval.login_blocked",
                    NamedTextColor.YELLOW, qqService.getDeviceApprovalTimeoutSeconds()));
            return;
        }
        if (args.length == 0) {
            ConnectionRef operation = ctx.captureConnectionOperation(player);
            if (operation != null) {
                sendQuickLoginCode(player, operation);
            }
            return;
        }
        if (args.length != 1) {
            player.sendMessage(ctx.messages().component("auth.login.usage", NamedTextColor.YELLOW));
            return;
        }
        String password = args[0];
        ConnectionRef operation = ctx.captureConnectionOperation(player);
        if (operation == null) {
            return;
        }
        ctx.runAsyncCommand(player, operation,
                () -> processLogin(player, password, operation),
                ERROR_DATABASE_QUERY);
    }

    /**
     * {@code /login} without arguments re-displays the QQ quick-login code. This is the recovery
     * path when the code shown on join scrolled out of chat or expired.
     */
    private void sendQuickLoginCode(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, ConnectionRef operation) {
        io.github.fulingsnow.youshuiauth.platform.QqOps service = ctx.qqService();
        if (service == null || !service.isEnabled()) {
            player.sendMessage(ctx.messages().component("auth.login.usage", NamedTextColor.YELLOW));
            return;
        }
        java.util.Optional<String> code = service.issueLoginCodeManual(player, operation);
        if (code.isEmpty()) {
            player.sendMessage(ctx.messages().component("qq.login.code_wait", NamedTextColor.YELLOW));
            return;
        }
        String loginCommand = "/登录 " + code.get();
        ClickEvent copyEvent = ClickEvent.copyToClipboard(loginCommand);
        Component hint = CommandHelper.withHighlightedCommand(
                ctx.messages().component("qq.login.code_hint", NamedTextColor.AQUA,
                        code.get(), service.getLoginCodeTtlSeconds()),
                loginCommand, NamedTextColor.AQUA, NamedTextColor.GOLD, copyEvent);
        Component copy = ctx.messages().component("qq.login.copy", NamedTextColor.AQUA)
                .clickEvent(copyEvent);
        player.sendMessage(hint.append(Component.space()).append(copy));
    }

    private void processLogin(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String password,
            ConnectionRef operation) {
        if (!ctx.beginConnectionCommand(player, operation)) {
            return;
        }
        try {
            InetAddress playerAddress = PlayerAddressUtils.getPlayerAddress(player);
            if (ctx.rejectRateLimited(player, playerAddress, operation)) {
                return;
            }

            AuthenticationContext authContext = ctx.validateAndAuthenticatePlayer(
                    player, "login", operation);
            if (authContext == null) {
                return;
            }

            String playerIp = PlayerAddressUtils.getPlayerIp(player);
            if (ctx.authCache().isPlayerAuthorized(player.uniqueId(), playerIp)
                    && ctx.authCache().hasActiveSession(player.uniqueId(), authContext.username(), playerIp)) {
                ctx.runIfConnectionCurrent(authContext.connectionOperation(),
                        () -> player.sendMessage(ctx.messages().component(
                                "auth.login.already_logged_in", NamedTextColor.YELLOW)));
                return;
            }

            if (authContext.registeredPlayer() == null) {
                ctx.runIfConnectionCurrent(authContext.connectionOperation(),
                        () -> player.sendMessage(ctx.messages().component(
                                "auth.login.not_registered", NamedTextColor.RED)));
                return;
            }
            String hash = authContext.registeredPlayer().getHash();
            if (hash == null || hash.isBlank()) {
                ctx.runIfConnectionCurrent(authContext.connectionOperation(),
                        () -> player.sendMessage(ctx.messages().component(
                                "auth.login.not_registered", NamedTextColor.RED)));
                return;
            }
            if (io.github.fulingsnow.youshuiauth.platform.QqOps.QQ_ONLY_HASH.equals(hash)) {
                ctx.runIfConnectionCurrent(authContext.connectionOperation(),
                        () -> player.sendMessage(ctx.messages().component(
                                "auth.login.qq_only", NamedTextColor.YELLOW)));
                return;
            }

            if (ctx.settings().isDenyQqPasswordLogin()) {
                io.github.fulingsnow.youshuiauth.platform.QqOps qqService = ctx.qqService();
                if (qqService != null && qqService.isAccountQqBound(player)) {
                    ctx.runIfConnectionCurrent(authContext.connectionOperation(),
                            () -> player.sendMessage(ctx.messages().component(
                                    "auth.login.qq_deny_password", NamedTextColor.YELLOW)));
                    return;
                }
            }

            BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), hash);

            if (!ctx.isConnectionCurrent(authContext.connectionOperation())) {
                return;
            }

            if (result.verified) {
                handleSuccessfulLogin(authContext);
            } else {
                handleFailedLogin(authContext);
            }
        } finally {
            ctx.releaseCommandLock(operation.playerId(), operation);
        }
    }

    private void handleSuccessfulLogin(AuthenticationContext authContext) {
        try {
            authContext.registeredPlayer().updateLoginData(PlayerAddressUtils.getPlayerIp(authContext.player()));
            var saveResult = ctx.databaseManager().savePlayer(authContext.registeredPlayer()).join();

            if (!ctx.isConnectionCurrent(authContext.connectionOperation())) {
                return;
            }

            if (ctx.handleDatabaseError(saveResult, authContext.username(), authContext.player(),
                    "Failed to save login data for", authContext.connectionOperation())) {
                return;
            }

            if (shouldRequireTotp(authContext.registeredPlayer())) {
                parkForTotpVerify(authContext);
                return;
            }

            if (PostAuthFlow.execute(ctx, authContext, authContext.registeredPlayer(), "logged in")) {
                ctx.runIfConnectionCurrent(authContext.connectionOperation(), () -> {
                    authContext.player().sendMessage(ctx.messages().component(
                            "auth.login.success", NamedTextColor.GREEN));
                    emitAudit(AuditEventType.LOGIN_OK, authContext, null);
                });
            }

        } catch (java.util.concurrent.CompletionException e) {
            if (ctx.logger().isErrorEnabled()) {
                ctx.logger().error("Error processing successful login: {}", authContext.username(), e);
            }
            ctx.runIfConnectionCurrent(authContext.connectionOperation(),
                    () -> ctx.sendDatabaseErrorMessage(authContext.player()));
        }
    }

    /**
     * True iff the player has a TOTP secret and the active startup snapshot enables two-factor.
     * Reloaded two-factor edits remain pending until a full proxy restart, so one running process
     * cannot change enforcement semantics underneath an authentication flow.
     */
    private boolean shouldRequireTotp(RegisteredPlayer dbPlayer) {
        if (!ctx.settings().getTwoFactorSettings().isEnabled()) {
            return false;
        }
        String token = dbPlayer.getTotpToken();
        return token != null && !token.isBlank();
    }

    private void parkForTotpVerify(AuthenticationContext authContext) {
        io.github.fulingsnow.youshuiauth.platform.AuthPlayer player = authContext.player();
        ctx.timeouts().renewForCode(
                player, ctx.settings().getTwoFactorSettings().pendingTimeoutSeconds());
        ctx.runIfConnectionCurrent(authContext.connectionOperation(), () -> {
            String ip = PlayerAddressUtils.getPlayerIp(player);
            ctx.pendingTotpStore().put(PendingTotpState.forLogin(
                    player.uniqueId(), authContext.registeredPlayer(), ip));
            player.sendMessage(ctx.messages().component("2fa.login.pending_prompt", NamedTextColor.GOLD));

            if (ctx.logger().isDebugEnabled()) {
                ctx.logger().debug("Player {} parked for 2FA verification (IP {})",
                        authContext.username(), ip);
            }
        });
    }

    private void handleFailedLogin(AuthenticationContext authContext) {
        ctx.runIfConnectionCurrent(authContext.connectionOperation(), () -> {
            boolean blocked = SecurityUtils.registerFailedLogin(
                    authContext.playerAddress(), authContext.username(), ctx.authCache());
            InetAddress playerAddress = authContext.playerAddress();
            if (playerAddress != null) {
                ctx.ipRateLimiter().incrementAttempts(playerAddress);
            }

            if (blocked) {
                authContext.player().sendMessage(ctx.messages().component(
                        "security.brute_force.blocked", NamedTextColor.RED));
                if (ctx.logger().isWarnEnabled()) {
                    ctx.logger().warn("Player {} blocked for brute force from IP {}",
                            authContext.username(), PlayerAddressUtils.getPlayerIp(authContext.player()));
                }
                emitAudit(AuditEventType.LOGIN_FAIL, authContext, "brute-force-blocked");
            } else {
                authContext.player().sendMessage(ctx.messages().component(
                        "auth.login.incorrect_password", NamedTextColor.RED));
                if (ctx.logger().isDebugEnabled()) {
                    ctx.logger().debug("Failed login attempt for player {} from IP {}",
                            authContext.username(), PlayerAddressUtils.getPlayerIp(authContext.player()));
                }
                emitAudit(AuditEventType.LOGIN_FAIL, authContext, "wrong-password");
            }
        });
    }

    private void emitAudit(AuditEventType type, AuthenticationContext authContext, String details) {
        AuditLogService audit = ctx.auditLogService();
        if (audit == null) {
            return;
        }
        audit.save(type, authContext.username(),
                PlayerAddressUtils.getPlayerIp(authContext.player()), details);
    }
}
