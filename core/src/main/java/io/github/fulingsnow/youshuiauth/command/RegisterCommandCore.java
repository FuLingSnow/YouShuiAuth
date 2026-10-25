package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import at.favre.lib.crypto.bcrypt.BCrypt;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;

import java.net.InetAddress;
import java.util.concurrent.CompletionException;

/**
 * Handles the /register command.
 * Creates a new player account and delegates post-auth flow to {@link PostAuthFlow}.
 * <p>
 * This command is hidden from players who are already authenticated
 * and not on the auth server (via {@link #hasPermission}).
 */
public class RegisterCommandCore extends AbstractAuthCommand {

    public RegisterCommandCore(CommandContext ctx) {
        super(ctx, sender -> CommandHelper.isPlayerOnAuthServerAndNeedsAuthentication(sender, ctx));
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void execute(io.github.fulingsnow.youshuiauth.platform.AuthSender sender, String[] args) {
        if (!ctx.settings().isPasswordRegistrationEnabled()) {

            sender.sendMessage(
                    ctx.messages().component("auth.register.disabled", NamedTextColor.RED));
            return;
        }
        CommandHelper.AuthInputs inputs = CommandHelper.requirePlayerAndArgs(
                sender, ctx.messages(), args, 2, "auth.register.usage");
        if (inputs == null) {
            return;
        }
        io.github.fulingsnow.youshuiauth.platform.AuthPlayer player = inputs.player();
        String password = inputs.args()[0];
        String confirmPassword = inputs.args()[1];
        var passwordSettings = ctx.settings().captureOperationSettings().password();

        if (!CommandHelper.requireValidPassword(player, password, passwordSettings, ctx.messages())
                || !CommandHelper.requirePasswordsMatch(player, password, confirmPassword, ctx.messages())) {
            return;
        }

        ConnectionRef operation = ctx.captureConnectionOperation(player);
        if (operation == null) {
            return;
        }
        var commitPermit = new DatabaseManager.RegistrationCommitPermit();
        ctx.runRegistrationCommandWithTimeout(
                player, operation, commitPermit,
                () -> processRegistration(
                        player, password, operation, passwordSettings, commitPermit),
                ERROR_DATABASE_QUERY);
    }

    private void processRegistration(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String password,
            ConnectionRef operation,
            Settings.PasswordSettings passwordSettings,
            DatabaseManager.RegistrationCommitPermit commitPermit) {
        if (commitPermit.isCancelled()) {
            return;
        }
        if (!ctx.beginConnectionCommand(player, operation, commitPermit)) {
            return;
        }
        InetAddress playerAddress = PlayerAddressUtils.getPlayerAddress(player);
        if (!canProceedWithoutAddress(
                player, playerAddress, operation, passwordSettings, commitPermit)) {
            return;
        }
        IpLockState ipLock = tryAcquireRegistrationIpLock(
                player, playerAddress, operation, commitPermit);
        if (!ipLock.proceed()) {
            return;
        }
        try {
            executeRegistrationFlow(
                    player, password, playerAddress, operation, passwordSettings,
                    commitPermit);
        } catch (CompletionException e) {
            if (commitPermit.tryCompleteWithoutCommit()
                    || !commitPermit.isCancelled()) {
                ctx.logger().error(DB_MARKER,
                        "Database error during registration for player {}",
                        player.username(), e);
                ctx.runIfConnectionCurrent(
                        operation, () -> ctx.sendDatabaseErrorMessage(player));
            }
        } finally {
            ctx.releaseCommandLock(operation.playerId(), operation);
            ctx.releaseRegistrationLock(ipLock.lease());
        }
    }

    private boolean canProceedWithoutAddress(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, InetAddress playerAddress,
            ConnectionRef operation,
            Settings.PasswordSettings passwordSettings,
            DatabaseManager.RegistrationCommitPermit commitPermit) {
        if (playerAddress != null || passwordSettings.ipLimitRegistrations() <= 0) {
            return true;
        }
        ctx.logger().warn(DB_MARKER,
                "Refusing registration of {} — cannot resolve remote IP (ip-limit-registrations enabled)",
                player.username());
        ctx.completeRegistrationWithoutCommit(
                operation, commitPermit,
                () -> player.sendMessage(ctx.messages().component(
                        "security.brute_force.blocked", NamedTextColor.RED)));
        ctx.releaseCommandLock(operation.playerId(), operation);
        return false;
    }

    private IpLockState tryAcquireRegistrationIpLock(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, InetAddress playerAddress,
            ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit commitPermit) {
        if (playerAddress == null) {
            return new IpLockState(true, null);
        }
        CommandContext.RegistrationLockLease lease =
                ctx.tryAcquireRegistrationLock(playerAddress);
        if (lease != null) {
            return new IpLockState(true, lease);
        }
        ctx.completeRegistrationWithoutCommit(
                operation, commitPermit,
                () -> ctx.sendCommandInProgress(player));
        ctx.releaseCommandLock(operation.playerId(), operation);
        return new IpLockState(false, null);
    }

    private record IpLockState(
            boolean proceed, CommandContext.RegistrationLockLease lease) { }

    private void executeRegistrationFlow(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String password, InetAddress playerAddress,
            ConnectionRef operation,
            Settings.PasswordSettings passwordSettings,
            DatabaseManager.RegistrationCommitPermit commitPermit) {
        if (commitPermit.isCancelled()) {
            return;
        }
        if (ctx.rejectRateLimited(player, playerAddress, operation, commitPermit)) {
            return;
        }

        AuthenticationContext authContext = ctx.validateAndAuthenticatePlayer(
                player, "registration", operation, commitPermit);
        if (authContext == null) {
            return;
        }

        if (authContext.registeredPlayer() != null) {
            ctx.completeRegistrationWithoutCommit(
                    authContext.connectionOperation(), commitPermit,
                    () -> authContext.player().sendMessage(ctx.messages().component(
                            "auth.register.already_registered", NamedTextColor.RED)));
            return;
        }

        if (exceedsIpRegistrationLimit(authContext, passwordSettings)) {
            ctx.completeRegistrationWithoutCommit(
                    authContext.connectionOperation(), commitPermit,
                    () -> player.sendMessage(ctx.messages().component(
                            "register.ip_limit_reached", NamedTextColor.RED)));
            return;
        }

        if (commitPermit.isCancelled()
                || !ctx.isConnectionCurrent(authContext.connectionOperation())) {
            return;
        }

        RegisteredPlayer newPlayer = buildNewPlayer(authContext, password, passwordSettings);
        if (!persistNewPlayer(authContext, newPlayer, commitPermit)) {
            return;
        }

        if (PostAuthFlow.execute(ctx, authContext, newPlayer, "registered")) {
            ctx.runIfConnectionCurrent(authContext.connectionOperation(), () -> {
                authContext.player().sendMessage(ctx.messages().component(
                        "auth.register.success", NamedTextColor.GREEN));
                emitRegisterAudit(authContext);
            });
        }
    }

    private boolean exceedsIpRegistrationLimit(
            AuthenticationContext authContext,
            Settings.PasswordSettings passwordSettings) {
        int limit = passwordSettings.ipLimitRegistrations();

        if (limit <= 0) {
            return false;
        }
        String playerIp = PlayerAddressUtils.getPlayerIp(authContext.player());
        long ipCount = ctx.databaseManager().countRegistrationsByIp(playerIp).join();
        return ipCount >= limit;
    }

    private RegisteredPlayer buildNewPlayer(
            AuthenticationContext authContext,
            String password,
            Settings.PasswordSettings passwordSettings) {
        String hashedPassword = BCrypt.with(BCrypt.Version.VERSION_2Y)
                .hashToString(passwordSettings.bcryptCost(), password.toCharArray());
        return new RegisteredPlayer(
                authContext.username(), hashedPassword,
                PlayerAddressUtils.getPlayerIp(authContext.player()),
                authContext.player().uniqueId().toString());
    }

    private boolean persistNewPlayer(
            AuthenticationContext authContext, RegisteredPlayer newPlayer,
            DatabaseManager.RegistrationCommitPermit commitPermit) {
        if (commitPermit.isCancelled()
                || !ctx.isConnectionCurrent(authContext.connectionOperation())) {
            return false;
        }
        var saveResult = ctx.databaseManager()
                .registerPlayerIfAbsent(newPlayer, commitPermit).join();
        if (!ctx.isConnectionCurrent(authContext.connectionOperation())) {
            return false;
        }
        if (saveResult.isDatabaseError()
                && !commitPermit.tryCompleteWithoutCommit()
                && commitPermit.isCancelled()) {
            return false;
        }
        if (ctx.handleDatabaseError(saveResult, authContext.username(), authContext.player(),
                "Failed to register new player", authContext.connectionOperation())) {
            return false;
        }
        return handleRegistrationOutcome(
                authContext, saveResult.getValue(), commitPermit);
    }

    private boolean handleRegistrationOutcome(
            AuthenticationContext authContext,
            DatabaseManager.RegistrationResult outcome,
            DatabaseManager.RegistrationCommitPermit commitPermit) {
        if (outcome == null) {

            if (authContext.player().isActive()) {
                ctx.sendDatabaseErrorMessage(authContext.player());
            }
            return false;
        }
        return switch (outcome) {
            case CREATED -> true;
            case DUPLICATE -> {

                if (authContext.player().isActive()) {
                    authContext.player().sendMessage(ctx.messages().component(
                            "auth.register.already_registered", NamedTextColor.RED));
                }
                yield false;
            }
            case CANCELLED -> false;
            case COMMIT_UNKNOWN -> {
                if (commitPermit.claimCommitUnknownMessage()) {
                    ctx.runIfConnectionCurrent(authContext.connectionOperation(),
                            () -> authContext.player().sendMessage(ctx.messages().component(
                                    "auth.registration.commit_unknown", NamedTextColor.RED)));
                }
                yield false;
            }
        };
    }

    private void emitRegisterAudit(AuthenticationContext authContext) {
        AuditLogService audit = ctx.auditLogService();
        if (audit != null) {
            audit.save(AuditEventType.REGISTER, authContext.username(),
                    PlayerAddressUtils.getPlayerIp(authContext.player()));
        }
    }
}
