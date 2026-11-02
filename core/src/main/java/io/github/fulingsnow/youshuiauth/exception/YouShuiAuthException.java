package io.github.fulingsnow.youshuiauth.exception;

/**
 * Base exception for YouShuiAuth plugin.
 * Wraps underlying exceptions with contextual information.
 */
public class YouShuiAuthException extends RuntimeException {

    public YouShuiAuthException(String message) {
        super(message);
    }

    public YouShuiAuthException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a database-related exception.
     */
    public static YouShuiAuthException database(String operation, Throwable cause) {
        return new YouShuiAuthException("Database operation failed: " + operation, cause);
    }

    /**
     * Creates a configuration-related exception.
     */
    public static YouShuiAuthException configuration(String component, Throwable cause) {
        return new YouShuiAuthException("Configuration error in " + component, cause);
    }

    /**
     * Creates an authentication-related exception.
     */
    public static YouShuiAuthException authentication(String details, Throwable cause) {
        return new YouShuiAuthException("Authentication failed: " + details, cause);
    }
}
