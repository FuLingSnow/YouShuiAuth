package io.github.fulingsnow.youshuiauth.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager.DbResult;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Utility class for consistent database error handling across the application.
 * Provides standardized error logging and user messaging for database operations.
 * <p>
 * Thread-safe: stateless utility methods.
 */
public final class DatabaseErrorHandler {

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private DatabaseErrorHandler() {

    }

    private static final String DEFAULT_ERROR_KEY = "error.database.query";

    /** Handles an owner-fenced DB error using an identifier captured before asynchronous work. */
    public static boolean handleError(
            DbResult<?> result, String identifier, String operation, Logger logger,
            Messages messages, java.util.function.Consumer<Component> messageSender) {
        return handleErrorCore(result, identifier, operation, logger, messages,
                DEFAULT_ERROR_KEY, messageSender);
    }

    /**
     * Handles database errors with custom error message key.
     *
     * @param result    Database result to check for errors
     * @param player    Player to send error message to
     * @param operation Description of the operation being performed
     * @param logger    Logger instance for error logging
     * @param messages  Messages instance for localized error messages
     * @param errorKey  Custom message key for error message
     * @return true if there was a database error (handled), false if operation can continue
     */
    public static boolean handleErrorWithKey(DbResult<?> result, String identifier, String operation,
                                            Logger logger, Messages messages, String errorKey,
                                            java.util.function.Consumer<Component> messageSender) {
        return handleErrorCore(result, identifier, operation, logger, messages, errorKey,
                messageSender);
    }

    /**
     * Core error handling logic shared by all handleError variants.
     */
    private static boolean handleErrorCore(DbResult<?> result, String identifier, String operation,
                                          Logger logger, Messages messages, String errorKey,
                                          java.util.function.Consumer<Component> messageSender) {
        if (!result.isDatabaseError()) {
            return false;
        }
        logDatabaseError(logger, operation, identifier, result.getErrorMessage());
        messageSender.accept(messages.component(errorKey, NamedTextColor.RED));
        return true;
    }

    /**
     * Logs database error with standardized format.
     */
    private static void logDatabaseError(Logger logger, String operation, String identifier, String errorMessage) {
        if (logger.isErrorEnabled()) {
            logger.error(SECURITY_MARKER, "[DATABASE ERROR] {} failed for {}: {}",
                    operation, identifier, errorMessage);
        }
    }
}
