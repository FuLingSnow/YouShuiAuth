package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import at.favre.lib.crypto.bcrypt.BCrypt;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;

import java.util.concurrent.CompletionException;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;

public class SetPasswordCommandCore extends AbstractAuthCommand {

    public SetPasswordCommandCore(CommandContext ctx) {
        super(ctx, sender -> CommandHelper.isPlayerAuthorized(sender, ctx));
    }

    public void execute(AuthSender sender, String[] args) {
        CommandHelper.AuthInputs inputs = beginPasswordCommand(sender, args,
                ctx.settings().isQqSetPasswordEnabled(),
                "auth.setpassword.disabled", "auth.setpassword.usage");
        if (inputs == null) {
            return;
        }
        AuthPlayer player = inputs.player();
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
        ConnectionRef operation = ctx.captureConnectionOperation(player);
        if (operation == null) {
            return;
        }
        ctx.runAsyncCommand(player, operation,
                () -> processSetPassword(player, password, operation, passwordSettings),
                ERROR_DATABASE_QUERY);
    }

    private void processSetPassword(
            AuthPlayer player, String password,
            ConnectionRef operation,
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
            if (!QqOps.QQ_ONLY_HASH.equals(hash)) {
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

            ctx.credentialEpochs().bump(authCtx.player().uniqueId());
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
        ConnectionRef operation = authCtx.connectionOperation();
        AuthPlayer player = authCtx.player();
        CachedAuthUser cachedUser = CachedAuthUser.fromRegisteredPlayer(
                authCtx.registeredPlayer(), false, null);
        String playerIp = PlayerAddressUtils.getPlayerIp(player);
        ctx.runIfConnectionCurrent(operation, () -> ctx.authCache().authorize(
                player.uniqueId(), cachedUser, authCtx.username(), playerIp));
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
