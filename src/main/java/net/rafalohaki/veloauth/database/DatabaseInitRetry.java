package net.rafalohaki.veloauth.database;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.slf4j.Logger;
import org.slf4j.Marker;

import java.util.function.BooleanSupplier;

/**
 * Runs a database initialization action with bounded backoff while PostgreSQL refuses new
 * sessions ("remaining connection slots are reserved"): a proxy restart can race with the
 * previous instance's connection pool still draining.
 */
final class DatabaseInitRetry {

    private DatabaseInitRetry() {}

    @FunctionalInterface
    interface InitializingAction {
        boolean run() throws SQLException;
    }

    static boolean run(int maxAttempts, long baseDelayMillis, BooleanSupplier closed,
            Runnable cleanup, Logger logger, Marker marker, InitializingAction action) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.run();
            } catch (SQLException e) {
                cleanup.run();
                if (closed.getAsBoolean()) {
                    logger.warn(marker, "Database initialization aborted by shutdown");
                    return false;
                }
                if (attempt == maxAttempts || !isConnectionExhausted(e)) {
                    logger.error(marker, "Error during database initialization", e);
                    return false;
                }
                long delay = baseDelayMillis * attempt;
                logger.warn(marker, "Database refused connections ({}); retry {}/{} in {} ms",
                        deepestMessage(e), attempt, maxAttempts, delay);
                if (!sleep(delay)) {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean sleep(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean isConnectionExhausted(SQLException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("remaining connection slots")
                    || message.contains("too many clients"))) {
                return true;
            }
        }
        return false;
    }

    static String deepestMessage(SQLException failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        return message == null ? deepest.getClass().getSimpleName() : message;
    }
}
