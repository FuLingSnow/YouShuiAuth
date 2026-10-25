package io.github.fulingsnow.youshuiauth.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.platform.TimeoutOps;
import io.github.fulingsnow.youshuiauth.platform.TransferOps;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.util.DatabaseErrorHandler;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shared context for all command implementations.
 * Provides access to common services and template methods used by extracted commands.
 */
public class CommandContext {

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");
    private static final Marker DB_MARKER = MarkerFactory.getMarker("DATABASE");
    private static final String MSG_BRUTE_FORCE_BLOCKED = "security.brute_force.blocked";
    private static final String MSG_DATABASE_QUERY_ERROR = "error.database.query";

    private final AuthServices services;
    private final DatabaseManager databaseManager;
    private final AuthCache authCache;
    private final Settings settings;
    private final Messages messages;
    private final Logger logger;
    private final IPRateLimiter ipRateLimiter;
    private final io.github.fulingsnow.youshuiauth.auth.ConflictModeService conflictModeService;
    private final io.github.fulingsnow.youshuiauth.auth.CredentialEpochService credentialEpochs;
    private final ConcurrentHashMap<UUID, io.github.fulingsnow.youshuiauth.platform.ConnectionRef> activeCommands =
            new ConcurrentHashMap<>();

    /**
     * Per-IP mutex for the {@code /register} flow. Closes the TOCTOU window between the
     * "count registrations by IP" check and the "save player" write: two concurrent /register
     * commands from the same IP can no longer both pass the {@code ip-limit-registrations}
     * gate. Caffeine-bounded (≤10k IPs) with a short TTL — far longer than any register call
     * but bounded enough to recover from leaked locks if a register handler throws past its
     * release point. */
    private final Cache<InetAddress, Object> registrationLocks = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public static CommandContext forPlatform(
            io.github.fulingsnow.youshuiauth.platform.AuthServices services,
            DatabaseManager databaseManager, AuthCache authCache,
            Settings settings, Messages messages) {
        return new CommandContext(services, databaseManager, authCache, settings, messages);
    }

    CommandContext(io.github.fulingsnow.youshuiauth.platform.AuthServices services, DatabaseManager databaseManager,
                   AuthCache authCache, Settings settings, Messages messages) {
        this.services = services;
        this.databaseManager = databaseManager;
        this.authCache = authCache;
        this.settings = settings;
        this.messages = messages;
        this.logger = services.logger();
        Settings.BruteForceSettings bruteForceSettings =
                settings.captureOperationSettings().bruteForce();
        this.ipRateLimiter = new IPRateLimiter(
                bruteForceSettings.maxAttempts(),
                bruteForceSettings.timeoutMinutes());
        authCache.setIpRateLimiter(this.ipRateLimiter);
        this.conflictModeService = new io.github.fulingsnow.youshuiauth.auth.ConflictModeService(
                databaseManager, bruteForceSettings.conflictModeTtlHours());
        this.credentialEpochs = new io.github.fulingsnow.youshuiauth.auth.CredentialEpochService(
                databaseManager, authCache, services.logger());
    }

    io.github.fulingsnow.youshuiauth.auth.CredentialEpochService credentialEpochs() {
        return credentialEpochs;
    }

    AuthServices services() { return services; }
    QqOps qqService() { return services.qq(); }
    TimeoutOps timeouts() { return services.timeouts(); }
    TransferOps transfers() { return services.transfers(); }
    DatabaseManager databaseManager() { return databaseManager; }
    AuthCache authCache() { return authCache; }
    Settings settings() { return settings; }
    Messages messages() { return messages; }
    Logger logger() { return logger; }
    IPRateLimiter ipRateLimiter() { return ipRateLimiter; }
    io.github.fulingsnow.youshuiauth.auth.ConflictModeService conflictModeService() { return conflictModeService; }

    /**
     * Releases resources held by the command layer. Currently a passthrough to
     * {@link IPRateLimiter#shutdown()} for lifecycle symmetry with other components.
     */
    void shutdown() {
        ipRateLimiter.shutdown();
    }
    io.github.fulingsnow.youshuiauth.auth.totp.TotpService totpService() { return services.totpService(); }
    io.github.fulingsnow.youshuiauth.auth.totp.PendingTotpStore pendingTotpStore() { return services.pendingTotpStore(); }
    io.github.fulingsnow.youshuiauth.auth.totp.TotpReplayGuard totpReplayGuard() { return services.totpReplayGuard(); }
    io.github.fulingsnow.youshuiauth.audit.AuditLogService auditLogService() { return services.auditLogService(); }
    io.github.fulingsnow.youshuiauth.platform.PlatformAccess platformAccess() {
        return services.platformAccess();
    }
    io.github.fulingsnow.youshuiauth.platform.ReportOps reports() { return services.reports(); }
    io.github.fulingsnow.youshuiauth.platform.ConnectionRef captureConnectionOperation(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        return player == null ? null : services.connections().capture(player);
    }
    boolean isConnectionCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
        return services.connections().isCurrent(operation);
    }

    boolean runIfConnectionCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation, Runnable effect) {
        return services.connections().runIfCurrent(operation, effect);
    }
    void retireConnectionOperation(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        services.connections().retire(player);
    }

    /**
     * Template method for common authentication pre-checks (see the
     * {@code VelocityCommandContext} shell for the Velocity-typed entry point).
     */
    AuthenticationContext validateAndAuthenticatePlayer(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String commandName,
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
        return validateAndAuthenticatePlayer(
                player, commandName, operation,
                message -> runIfConnectionCurrent(
                        operation, () -> player.sendMessage(message)));
    }

    AuthenticationContext validateAndAuthenticatePlayer(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String commandName,
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit) {
        return validateAndAuthenticatePlayer(
                player, commandName, operation,
                message -> completeRegistrationWithoutCommit(
                        operation, permit, () -> player.sendMessage(message)));
    }

    private AuthenticationContext validateAndAuthenticatePlayer(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String commandName,
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            Consumer<Component> messageSender) {
        if (!isConnectionCurrent(operation)) {
            return null;
        }
        if (credentialEpochs.isStale(player.uniqueId())) {
            messageSender.accept(messages.component(
                    "auth.credential_changed", NamedTextColor.RED));
            return null;
        }

        InetAddress playerAddress = PlayerAddressUtils.getPlayerAddress(player);

        if (playerAddress != null && authCache.isBlocked(playerAddress, player.username())) {
            messageSender.accept(messages.component(
                    MSG_BRUTE_FORCE_BLOCKED, NamedTextColor.RED));
            if (logger.isWarnEnabled()) {
                logger.warn(SECURITY_MARKER, "[BRUTE FORCE BLOCK] IP {} attempted {}", playerAddress.getHostAddress(), commandName);
            }
            return null;
        }

        String username = player.username();
        DatabaseManager.DbResult<io.github.fulingsnow.youshuiauth.model.RegisteredPlayer> dbResult;
        try {
            dbResult = databaseManager.findPlayerByNickname(username).join();
        } catch (CompletionException e) {
            logger.error(DB_MARKER, "Database error during {} for player {}", commandName, username, e);
            messageSender.accept(messages.component(MSG_DATABASE_QUERY_ERROR, NamedTextColor.RED));
            return null;
        }

        if (DatabaseErrorHandler.handleError(
                dbResult, username, commandName + " lookup for", logger, messages,
                messageSender)) {
            return null;
        }

        if (!isConnectionCurrent(operation)) {
            return null;
        }
        return new AuthenticationContext(player, username, playerAddress, dbResult.getValue(), operation);
    }

    /**
     * Checks premium status with error handling and logging.
     */
    DatabaseManager.DbResult<Boolean> checkPremiumStatus(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String operation) {
        DatabaseManager.DbResult<Boolean> result;
        try {
            result = databaseManager.isPremium(player.username()).join();
        } catch (CompletionException e) {
            logger.error(DB_MARKER, "[DATABASE ERROR] {} failed for {}", operation, player.username(), e);
            player.sendMessage(messages.component(MSG_DATABASE_QUERY_ERROR, NamedTextColor.RED));
            return DatabaseManager.DbResult.databaseError("CompletionException: " + e.getMessage());
        }
        if (result.isDatabaseError()) {
            if (logger.isErrorEnabled()) {
                logger.error(SECURITY_MARKER, "[DATABASE ERROR] {} failed for {}: {}", operation, player.username(), result.getErrorMessage());
            }
            player.sendMessage(messages.component(MSG_DATABASE_QUERY_ERROR, NamedTextColor.RED));
        }
        return result;
    }

    DatabaseManager.DbResult<Boolean> checkPremiumStatus(
            String username, io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String operation,
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef connectionOperation) {
        DatabaseManager.DbResult<Boolean> result;
        try {
            result = databaseManager.isPremium(username).join();
        } catch (RuntimeException e) {
            logger.error(DB_MARKER, "[DATABASE ERROR] {} failed for {}", operation, username, e);
            runIfConnectionCurrent(connectionOperation,
                    () -> player.sendMessage(messages.component(
                            MSG_DATABASE_QUERY_ERROR, NamedTextColor.RED)));
            return DatabaseManager.DbResult.databaseError(
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result.isDatabaseError()) {
            if (logger.isErrorEnabled()) {
                logger.error(SECURITY_MARKER, "[DATABASE ERROR] {} failed for {}: {}",
                        operation, username, result.getErrorMessage());
            }
            runIfConnectionCurrent(connectionOperation,
                    () -> player.sendMessage(messages.component(
                            MSG_DATABASE_QUERY_ERROR, NamedTextColor.RED)));
        }
        return result;
    }

    /**
     * Resets brute-force and rate-limit counters for the given IP address and username.
     */
    void resetSecurityCounters(InetAddress playerAddress, String username) {
        SecurityUtils.resetSecurityCounters(playerAddress, username, authCache, ipRateLimiter);
    }

    boolean handleDatabaseError(DatabaseManager.DbResult<?> result,
                                io.github.fulingsnow.youshuiauth.platform.AuthPlayer player,
                                String operation) {
        return DatabaseErrorHandler.handleError(result, player.username(), operation,
                logger, messages, player::sendMessage);
    }

    boolean handleDatabaseError(
            DatabaseManager.DbResult<?> result, String identifier,
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String operation,
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef connectionOperation) {
        return DatabaseErrorHandler.handleError(
                result, identifier, operation, logger, messages,
                message -> runIfConnectionCurrent(
                        connectionOperation, () -> player.sendMessage(message)));
    }

    /**
     * Sends a database error message to the player.
     */
    void sendDatabaseErrorMessage(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        player.sendMessage(messages.component(MSG_DATABASE_QUERY_ERROR, NamedTextColor.RED));
    }

    /**
     * Executes a command asynchronously using the shared command helper.
     */
    void runAsyncCommand(io.github.fulingsnow.youshuiauth.platform.AuthSender source, Runnable task, String errorKey) {
        CommandHelper.runAsyncCommand(task, messages, errorKey, source::sendMessage);
    }

    void runAsyncCommand(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            Runnable task, String errorKey) {
        CommandHelper.runAsyncCommand(task, messages, errorKey,
                message -> runIfConnectionCurrent(
                        operation, () -> player.sendMessage(message)));
    }

    /**
     * Executes a command asynchronously with timeout using the shared command helper.
     */
    void runAsyncCommandWithTimeout(io.github.fulingsnow.youshuiauth.platform.AuthSender source,
                                    Runnable task, String errorKey, String timeoutKey) {
        CommandHelper.runAsyncCommandWithTimeout(task, messages, errorKey, timeoutKey,
                source::sendMessage);
    }

    void runAsyncCommandWithTimeout(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation, Runnable task,
            String errorKey, String timeoutKey) {
        CommandHelper.runAsyncCommandWithTimeout(task, messages, errorKey, timeoutKey,
                message -> runIfConnectionCurrent(
                        operation, () -> player.sendMessage(message)));
    }

    void runRegistrationCommandWithTimeout(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit, Runnable task,
            String errorKey) {
        CommandHelper.runAsyncCommandWithTimeout(task, messages, errorKey,
                message -> runIfConnectionCurrent(
                        operation, () -> player.sendMessage(message)),
                () -> handleRegistrationTimeout(player, operation, permit));
    }

    @SuppressWarnings("java:S6916")
    DatabaseManager.RegistrationTimeoutDisposition handleRegistrationTimeout(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit) {
        DatabaseManager.RegistrationTimeoutDisposition disposition = permit.onTimeout();
        switch (disposition) {
            case CANCELLED_BEFORE_COMMIT -> sendRegistrationDeadlineMessage(
                    player, operation, "auth.registration.timeout", NamedTextColor.RED);
            case COMMIT_IN_PROGRESS, COMMIT_COMPLETED -> sendRegistrationDeadlineMessage(
                    player, operation, "auth.registration.commit_pending", NamedTextColor.YELLOW);

            case COMMIT_UNKNOWN -> {
                if (permit.claimCommitUnknownMessage()) {
                    sendRegistrationDeadlineMessage(
                            player, operation, "auth.registration.commit_unknown",
                            NamedTextColor.RED);
                }
            }
            case NO_ACTION -> {

            }
        }
        return disposition;
    }

    boolean completeRegistrationWithoutCommit(
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit, Runnable effect) {
        if (!permit.tryCompleteWithoutCommit()) {
            return false;
        }
        return runIfConnectionCurrent(operation, effect);
    }

    private void sendRegistrationDeadlineMessage(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            String messageKey, NamedTextColor color) {
        runIfConnectionCurrent(operation,
                () -> player.sendMessage(messages.component(messageKey, color)));
    }

    /**
     * Ensures the database is currently connected before continuing an admin command.
     */
    boolean ensureDatabaseConnected(io.github.fulingsnow.youshuiauth.platform.AuthSender source, String operation) {
        if (databaseManager.isConnected()) {
            return true;
        }
        if (logger.isWarnEnabled()) {
            logger.warn(SECURITY_MARKER, "[DATABASE ERROR] {} failed: database not connected", operation);
        }
        sendDatabaseErrorMessage(source);
        return false;
    }

    boolean handleDatabaseError(DatabaseManager.DbResult<?> result,
                                io.github.fulingsnow.youshuiauth.platform.AuthSender source,
                                String identifier, String operation) {
        return DatabaseErrorHandler.handleError(result, identifier, operation,
                logger, messages, source::sendMessage);
    }

    void sendDatabaseErrorMessage(io.github.fulingsnow.youshuiauth.platform.AuthSender source) {
        source.sendMessage(messages.component(MSG_DATABASE_QUERY_ERROR, NamedTextColor.RED));
    }

    /**
     * Sends the standard per-player in-progress message.
     */
    void sendCommandInProgress(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        player.sendMessage(messages.component("auth.command.in_progress", NamedTextColor.YELLOW));
    }

    boolean beginConnectionCommand(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
        if (!isConnectionCurrent(operation)) {
            return false;
        }
        if (tryAcquireCommandLock(operation.playerId(), operation)) {
            return true;
        }
        runIfConnectionCurrent(operation, () -> sendCommandInProgress(player));
        return false;
    }

    boolean beginConnectionCommand(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit) {
        if (!isConnectionCurrent(operation)) {
            return false;
        }
        if (tryAcquireCommandLock(operation.playerId(), operation)) {
            return true;
        }
        completeRegistrationWithoutCommit(
                operation, permit, () -> sendCommandInProgress(player));
        return false;
    }

    boolean rejectRateLimited(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, InetAddress address,
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
        if (address == null || !ipRateLimiter.isRateLimited(address)) {
            return false;
        }
        runIfConnectionCurrent(operation, () -> player.sendMessage(messages.component(
                MSG_BRUTE_FORCE_BLOCKED, NamedTextColor.RED)));
        return true;
    }

    boolean rejectRateLimited(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, InetAddress address,
            io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit) {
        if (address == null || !ipRateLimiter.isRateLimited(address)) {
            return false;
        }
        completeRegistrationWithoutCommit(
                operation, permit,
                () -> player.sendMessage(messages.component(
                        MSG_BRUTE_FORCE_BLOCKED, NamedTextColor.RED)));
        return true;
    }

    /**
     * Tries to acquire a per-player command lock to prevent concurrent command execution.
     *
     * @param playerId UUID of the player
     * @return true if lock acquired, false if already processing
     */
    boolean tryAcquireCommandLock(
            UUID playerId, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
        AtomicBoolean acquired = new AtomicBoolean();
        activeCommands.compute(playerId, (ignored, current) -> {
            if (current == null || !isConnectionCurrent(current)) {
                acquired.set(true);
                return operation;
            }
            return current;
        });
        return acquired.get();
    }

    /**
     * Releases the per-player command lock.
     *
     * @param playerId UUID of the player
     */
    void releaseCommandLock(
            UUID playerId, io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
        activeCommands.remove(playerId, operation);
    }

    /**
     * Tries to acquire the per-IP register lock — closes the TOCTOU window on
     * {@code ip-limit-registrations}. {@code null} address never acquires (treated as
     * not-allowed; caller surface defends with its own null-checks).
     */
    RegistrationLockLease tryAcquireRegistrationLock(InetAddress address) {
        if (address == null) {
            return null;
        }
        Object owner = new Object();
        return registrationLocks.asMap().putIfAbsent(address, owner) == null
                ? new RegistrationLockLease(address, owner)
                : null;
    }

    /** Releases the exact per-IP register lease. No-op for {@code null} or a replaced lease. */
    void releaseRegistrationLock(RegistrationLockLease lease) {
        if (lease != null) {
            registrationLocks.asMap().remove(lease.address(), lease.owner());
        }
    }

    record RegistrationLockLease(InetAddress address, Object owner) { }
}
