package io.github.fulingsnow.youshuiauth.util;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager.DbResult;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import org.slf4j.Logger;

/**
 * Velocity 侧的数据库错误处理入口；实际逻辑在 {@link DatabaseErrorHandler}，
 * core 因此不需要引用 Velocity API。
 */
public final class VelocityDatabaseErrorHandler {

    private static final String DEFAULT_ERROR_KEY = "error.database.query";

    private VelocityDatabaseErrorHandler() {
    }

    public static boolean handleError(DbResult<?> result, Player player, String operation,
                                      Logger logger, Messages messages) {
        return handleErrorWithKey(result, player, operation, logger, messages, DEFAULT_ERROR_KEY);
    }

    public static boolean handleError(
            DbResult<?> result, Player player, String operation, Logger logger, Messages messages,
            java.util.function.Consumer<Component> messageSender) {
        if (!result.isDatabaseError()) {
            return false;
        }
        return DatabaseErrorHandler.handleError(
                result, player.getUsername(), operation, logger, messages, messageSender);
    }

    public static boolean handleError(DbResult<?> result, CommandSource source, String identifier,
                                      String operation, Logger logger, Messages messages) {
        return DatabaseErrorHandler.handleError(
                result, identifier, operation, logger, messages, source::sendMessage);
    }

    public static boolean handleErrorWithKey(DbResult<?> result, Player player, String operation,
                                             Logger logger, Messages messages, String errorKey) {
        return DatabaseErrorHandler.handleErrorWithKey(
                result, player.getUsername(), operation, logger, messages, errorKey,
                player::sendMessage);
    }
}
