package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender;

/**
 * Velocity 侧的上下文外壳：只承载 {@link CommandContext} 中依赖 Velocity 类型的入口，
 * 每个方法都是一行委托，不含有任何认证逻辑。core 侧的 {@link CommandContext}
 * 因此保持与 Velocity API 无关。
 */
class VelocityCommandContext extends CommandContext {

    VelocityCommandContext(AuthServices services, DatabaseManager databaseManager,
                           AuthCache authCache, Settings settings, Messages messages) {
        super(services, databaseManager, authCache, settings, messages);
    }

    ConnectionRef captureConnectionOperation(Player player) {
        return captureConnectionOperation(VelocityAuthPlayer.of(player));
    }

    void retireConnectionOperation(Player player) {
        retireConnectionOperation(VelocityAuthPlayer.of(player));
    }

    AuthenticationContext validateAndAuthenticatePlayer(CommandSource source, String commandName) {
        Player velocity = VelocityCommandHelper.validatePlayerSource(source, messages());
        if (velocity == null) {
            return null;
        }
        AuthPlayer player = VelocityAuthPlayer.of(velocity);
        ConnectionRef operation = captureConnectionOperation(player);
        if (operation == null) {
            return null;
        }
        return validateAndAuthenticatePlayer(player, commandName, operation);
    }

    AuthenticationContext validateAndAuthenticatePlayer(
            Player player, String commandName, ConnectionRef operation) {
        return validateAndAuthenticatePlayer(VelocityAuthPlayer.of(player), commandName, operation);
    }

    AuthenticationContext validateAndAuthenticatePlayer(
            Player player, String commandName, ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit) {
        return validateAndAuthenticatePlayer(
                VelocityAuthPlayer.of(player), commandName, operation, permit);
    }

    boolean handleDatabaseError(DatabaseManager.DbResult<?> result, Player player, String operation) {
        return handleDatabaseError(result, VelocityAuthPlayer.of(player), operation);
    }

    boolean handleDatabaseError(
            DatabaseManager.DbResult<?> result, String identifier, Player player, String operation,
            ConnectionRef connectionOperation) {
        return handleDatabaseError(
                result, identifier, VelocityAuthPlayer.of(player), operation, connectionOperation);
    }

    boolean handleDatabaseError(DatabaseManager.DbResult<?> result, CommandSource source,
                                String identifier, String operation) {
        return handleDatabaseError(
                result, VelocityAuthSender.of(source), identifier, operation);
    }

    void sendDatabaseErrorMessage(Player player) {
        sendDatabaseErrorMessage(VelocityAuthPlayer.of(player));
    }

    void sendDatabaseErrorMessage(CommandSource source) {
        sendDatabaseErrorMessage(VelocityAuthSender.of(source));
    }

    void runAsyncCommand(CommandSource source, Runnable task, String errorKey) {
        runAsyncCommand(VelocityAuthSender.of(source), task, errorKey);
    }

    void runAsyncCommand(
            Player player, ConnectionRef operation, Runnable task, String errorKey) {
        runAsyncCommand(VelocityAuthPlayer.of(player), operation, task, errorKey);
    }

    void runAsyncCommandWithTimeout(CommandSource source, Runnable task,
                                    String errorKey, String timeoutKey) {
        runAsyncCommandWithTimeout(VelocityAuthSender.of(source), task, errorKey, timeoutKey);
    }

    void runAsyncCommandWithTimeout(
            Player player, ConnectionRef operation, Runnable task,
            String errorKey, String timeoutKey) {
        runAsyncCommandWithTimeout(
                VelocityAuthPlayer.of(player), operation, task, errorKey, timeoutKey);
    }

    void runRegistrationCommandWithTimeout(
            Player player, ConnectionRef operation,
            DatabaseManager.RegistrationCommitPermit permit, Runnable task,
            String errorKey) {
        runRegistrationCommandWithTimeout(
                VelocityAuthPlayer.of(player), operation, permit, task, errorKey);
    }

    boolean ensureDatabaseConnected(CommandSource source, String operation) {
        return ensureDatabaseConnected(VelocityAuthSender.of(source), operation);
    }

    void sendCommandInProgress(Player player) {
        sendCommandInProgress(VelocityAuthPlayer.of(player));
    }
}
