package net.rafalohaki.veloauth.command;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rafalohaki.veloauth.audit.AuditEventType;
import net.rafalohaki.veloauth.audit.AuditLogService;
import net.rafalohaki.veloauth.config.Settings;
import net.rafalohaki.veloauth.lifecycle.ConnectionLifecycleRegistry;
import net.rafalohaki.veloauth.model.CachedAuthUser;
import net.rafalohaki.veloauth.util.PlayerAddressUtils;

import java.util.concurrent.CompletionException;

class SetPasswordCommand extends PasswordCommandBase {

    SetPasswordCommand(CommandContext ctx) {
        super(ctx);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void execute(Invocation invocation) {
        if (!ctx.settings().isQqSetPasswordEnabled()) {
            invocation.source().sendMessage(
                    ctx.messages().component("auth.setpassword.disabled", NamedTextColor.RED));
            return;
        }
        CommandHelper.CommandInputs inputs = CommandHelper.requirePlayerAndArgs(
                invocation, ctx.messages(), 2, "auth.setpassword.usage");
        if (inputs == null) {
            return;
        }
        Player player = inputs.player();
        String password = inputs.args()[0];
        String confirmPassword = inputs.args()[1];
        if (!password.equals(confirmPassword)) {
            player.sendMessage(ctx.messages().component(
                    "auth.setpassword.mismatch", NamedTextColor.RED));
            return;
        }
        var passwordSettings = ctx.settings().captureOperationSettings().password();
        if (!CommandHelper.requireValidPassword(player, password, passwordSettings, ctx.messages())) {
            return;
        }
        ConnectionLifecycleRegistry.Operation operation = ctx.captureConnectionOperation(player);
        if (operation == null) {
            return;
        }
        ctx.runAsyncCommand(player, operation,
                () -> processSetPassword(player, password, operation, passwordSettings),
                ERROR_DATABASE_QUERY);
    }

    private void processSetPassword(
            Player player, String password,
            ConnectionLifecycleRegistry.Operation operation,
            Settings.PasswordSettings passwordSettings) {
        if (!ctx.beginConnectionCommand(player, operation)) {
            return;
        }
        try {
            AuthenticationContext authCtx = ctx.validateAndAuthenticatePlayer(
                    player, "password setup", operation);
            if (authCtx == null) {
                return;
            }
            if (authCtx.registeredPlayer() == null) {
                ctx.runIfConnectionCurrent(operation,
                        () -> authCtx.player().sendMessage(ctx.messages().component(
                                "auth.login.not_registered", NamedTextColor.RED)));
                return;
            }
            String hash = authCtx.registeredPlayer().getHash();
            if (!QqAuthService.QQ_ONLY_HASH.equals(hash)) {
                ctx.runIfConnectionCurrent(operation,
                        () -> authCtx.player().sendMessage(ctx.messages().component(
                                "auth.setpassword.not_qq_only", NamedTextColor.RED)));
                return;
            }

            authCtx.registeredPlayer().setHash(BCrypt.with(BCrypt.Version.VERSION_2Y)
                    .hashToString(passwordSettings.bcryptCost(), password.toCharArray()));
            try {
                var saveResult = ctx.databaseManager().savePlayer(authCtx.registeredPlayer()).join();
                if (ctx.handleDatabaseError(saveResult, authCtx.username(), authCtx.player(),
                        "Password setup save failed for", operation)) {
                    return;
                }
                if (!Boolean.TRUE.equals(saveResult.getValue())) {
                    ctx.runIfConnectionCurrent(operation,
                            () -> ctx.sendDatabaseErrorMessage(authCtx.player()));
                    return;
                }
            } catch (CompletionException e) {
                ctx.logger().error(DB_MARKER, "Failed to save password setup for player {}",
                        authCtx.username(), e);
                ctx.runIfConnectionCurrent(operation,
                        () -> ctx.sendDatabaseErrorMessage(authCtx.player()));
                return;
            }

            restoreAuthorization(authCtx);
            saveAudit(authCtx);
            ctx.runIfConnectionCurrent(operation, () -> authCtx.player().sendMessage(
                    ctx.messages().component("auth.setpassword.success", NamedTextColor.GREEN)));
            if (ctx.logger().isInfoEnabled()) {
                ctx.logger().info(AUTH_MARKER, "Player {} set a password from IP {}",
                        authCtx.username(), authCtx.playerAddress() == null
                                ? "unknown" : authCtx.playerAddress().getHostAddress());
            }
        } finally {
            ctx.releaseCommandLock(operation.playerId(), operation);
        }
    }

    private void restoreAuthorization(AuthenticationContext authCtx) {
        ConnectionLifecycleRegistry.Operation operation = authCtx.connectionOperation();
        Player player = authCtx.player();
        CachedAuthUser cachedUser = CachedAuthUser.fromRegisteredPlayer(
                authCtx.registeredPlayer(), false, null);
        String playerIp = PlayerAddressUtils.getPlayerIp(player);
        ctx.runIfConnectionCurrent(operation, () -> ctx.authCache().authorize(
                player.getUniqueId(), cachedUser, authCtx.username(), playerIp));
    }

    private void saveAudit(AuthenticationContext authCtx) {
        AuditLogService audit = ctx.auditLogService();
        if (audit == null) {
            return;
        }
        try {
            audit.save(AuditEventType.QQ_SET_PASSWORD, authCtx.username(),
                    authCtx.playerAddress() == null ? null : authCtx.playerAddress().getHostAddress());
        } catch (RuntimeException auditFailure) {
            ctx.logger().error(AUTH_MARKER,
                    "Failed to submit password-setup audit for {}", authCtx.username(), auditFailure);
        }
    }
}
