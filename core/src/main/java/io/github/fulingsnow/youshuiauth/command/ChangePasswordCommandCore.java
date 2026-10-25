package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import at.favre.lib.crypto.bcrypt.BCrypt;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.util.SecurityUtils;

import java.util.concurrent.CompletionException;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;

/**
 * Handles the /changepassword command.
 * Verifies old password, validates new one, and updates the database.
 */
public class ChangePasswordCommandCore extends AbstractAuthCommand {

    public ChangePasswordCommandCore(CommandContext ctx) {
        super(ctx, sender -> CommandHelper.isPlayerAuthorized(sender, ctx));
    }

    public void execute(AuthSender sender, String[] args) {
        CommandHelper.AuthInputs inputs = beginPasswordCommand(sender, args,
                ctx.settings().isPasswordChangeEnabled(),
                "auth.changepassword.disabled", "auth.changepassword.usage");
        if (inputs == null) {
            return;
        }
        AuthPlayer player = inputs.player();
        String oldPassword = inputs.args()[0];
        String newPassword = inputs.args()[1];
        var passwordSettings = ctx.settings().captureOperationSettings().password();

        if (!CommandHelper.requireValidPassword(player, newPassword, passwordSettings, ctx.messages())) {
            return;
        }

        ConnectionRef operation = ctx.captureConnectionOperation(player);
        if (operation == null) {
            return;
        }
        ctx.runAsyncCommand(player, operation,
                () -> processPasswordChange(
                        player, oldPassword, newPassword, operation, passwordSettings),
                ERROR_DATABASE_QUERY);
    }

    private void processPasswordChange(
            AuthPlayer player, String oldPassword, String newPassword,
            ConnectionRef operation,
            Settings.PasswordSettings passwordSettings) {
        if (!ctx.beginConnectionCommand(player, operation)) {
            return;
        }

        try {
            AuthenticationContext authCtx = preparePasswordChange(player, operation);
            if (authCtx == null) {
                return;
            }

            if (!verifyOldPassword(authCtx, oldPassword)) {
                return;
            }

            if (!updatePassword(authCtx, newPassword, passwordSettings)) {
                return;
            }

            finalizePasswordChange(authCtx);
        } finally {
            ctx.releaseCommandLock(operation.playerId(), operation);
        }
    }

    private AuthenticationContext preparePasswordChange(
            AuthPlayer player, ConnectionRef operation) {
        AuthenticationContext authCtx = ctx.validateAndAuthenticatePlayer(
                player, "password change", operation);
        if (authCtx == null) {
            return null;
        }
        if (authCtx.registeredPlayer() == null) {
            ctx.runIfConnectionCurrent(operation,
                    () -> authCtx.player().sendMessage(ctx.messages().component(
                            "auth.login.not_registered", NamedTextColor.RED)));
            return null;
        }
        return authCtx;
    }

    private boolean verifyOldPassword(AuthenticationContext authCtx, String oldPassword) {
        String hash = authCtx.registeredPlayer().getHash();
        if (hash == null || hash.isBlank()) {

            ctx.runIfConnectionCurrent(authCtx.connectionOperation(),
                    () -> authCtx.player().sendMessage(ctx.messages().component(
                            "auth.login.not_registered", NamedTextColor.RED)));
            return false;
        }
        if (QqOps.QQ_ONLY_HASH.equals(hash)) {

            ctx.runIfConnectionCurrent(authCtx.connectionOperation(),
                    () -> authCtx.player().sendMessage(ctx.messages().component(
                            "auth.changepassword.qq_only", NamedTextColor.RED)));
            return false;
        }
        BCrypt.Result result = BCrypt.verifyer().verify(oldPassword.toCharArray(), hash);
        if (!ctx.isConnectionCurrent(authCtx.connectionOperation())) {
            return false;
        }
        if (!result.verified) {
            ctx.runIfConnectionCurrent(authCtx.connectionOperation(), () -> {
                authCtx.player().sendMessage(ctx.messages().component(
                        "auth.changepassword.incorrect_old_password", NamedTextColor.RED));
                SecurityUtils.registerFailedLogin(
                        authCtx.playerAddress(), authCtx.username(), ctx.authCache());
            });
            return false;
        }
        return true;
    }

    private boolean updatePassword(
            AuthenticationContext authCtx,
            String newPassword,
            Settings.PasswordSettings passwordSettings) {
        String newHashedPassword = BCrypt.with(BCrypt.Version.VERSION_2Y)
                .hashToString(passwordSettings.bcryptCost(), newPassword.toCharArray());
        if (!ctx.isConnectionCurrent(authCtx.connectionOperation())) {
            return false;
        }
        authCtx.registeredPlayer().setHash(newHashedPassword);
        try {
            var saveResult = ctx.databaseManager().savePlayer(authCtx.registeredPlayer()).join();
            if (ctx.handleDatabaseError(saveResult, authCtx.username(), authCtx.player(),
                    "Password change save failed for", authCtx.connectionOperation())) {
                return false;
            }
            boolean saved = Boolean.TRUE.equals(saveResult.getValue());
            if (!saved) {
                ctx.runIfConnectionCurrent(authCtx.connectionOperation(),
                        () -> ctx.sendDatabaseErrorMessage(authCtx.player()));
                return false;
            }
            return true;
        } catch (CompletionException e) {
            ctx.logger().error(DB_MARKER, "Failed to save password change for player {}", authCtx.username(), e);
            ctx.runIfConnectionCurrent(authCtx.connectionOperation(),
                    () -> ctx.sendDatabaseErrorMessage(authCtx.player()));
            return false;
        }
    }

    private void finalizePasswordChange(AuthenticationContext authCtx) {
        java.util.UUID playerId = authCtx.connectionOperation().playerId();
        String playerIp = authCtx.playerAddress() == null
                ? "unknown"
                : authCtx.playerAddress().getHostAddress();

        ctx.authCache().endSession(playerId);
        ctx.authCache().removeAuthorizedPlayer(playerId);

        ctx.credentialEpochs().bump(playerId);
        QqOps qqAuthService = ctx.qqService();
        if (qqAuthService != null) {
            qqAuthService.revokeRememberTokens(playerId);
        }
        savePasswordChangeAudit(authCtx.username(), playerIp);

        AuthPlayer origin = authCtx.player();
        disconnectDuplicateSessions(origin, authCtx.username(), playerIp);

        ctx.runIfConnectionCurrent(authCtx.connectionOperation(),
                () -> authCtx.player().sendMessage(ctx.messages().component(
                        "auth.changepassword.success", NamedTextColor.GREEN)));
        if (ctx.logger().isInfoEnabled()) {
            ctx.logger().info(AUTH_MARKER, "Player {} changed password from IP {}",
                    authCtx.username(), playerIp);
        }

        var premiumResult = ctx.checkPremiumStatus(
                authCtx.username(), origin, "Premium check during password change",
                authCtx.connectionOperation());
        if (!premiumResult.isDatabaseError() && Boolean.TRUE.equals(premiumResult.getValue())) {
            ctx.authCache().removePremiumPlayer(authCtx.username());
        }
    }

    private void savePasswordChangeAudit(String username, String playerIp) {
        AuditLogService audit = ctx.auditLogService();
        if (audit == null) {
            return;
        }
        try {
            audit.save(AuditEventType.PASSWORD_CHANGE, username, playerIp);
        } catch (RuntimeException auditFailure) {
            ctx.logger().error(AUTH_MARKER,
                    "Failed to submit password-change audit for {}", username, auditFailure);
        }
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void disconnectDuplicateSessions(AuthPlayer origin, String username, String playerIp) {
        for (AuthPlayer connected : ctx.platformAccess().onlinePlayers()) {
            if (connected == origin) {
                continue;
            }
            try {
                if (connected.username().equalsIgnoreCase(username)) {
                    connected.disconnect(ctx.messages().component(
                            "general.kick.message", NamedTextColor.YELLOW));
                    if (ctx.logger().isWarnEnabled()) {
                        ctx.logger().warn(
                                "Disconnected duplicate player {} — password changed from IP {}",
                                username, playerIp);
                    }
                }
            } catch (RuntimeException disconnectFailure) {
                ctx.logger().error(AUTH_MARKER,
                        "Failed to disconnect duplicate session for {} after password change",
                        username, disconnectFailure);
            }
        }
    }
}
