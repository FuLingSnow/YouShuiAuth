package net.rafalohaki.veloauth.qq;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Restart-scoped configuration for the QQ binding / quick-login bridge.
 *
 * <p>The bridge is opt-in: {@code enabled=false} must leave every existing authentication
 * path untouched (no HTTP listener, no commands, no scheduled work).
 *
 * @param enabled              master switch
 * @param bindAddress          loopback address the internal HTTP bridge binds to
 * @param port                 internal HTTP bridge port
 * @param sharedSecret         HMAC secret shared with the AstrBot plugin (>= 32 chars)
 * @param allowedGroups        QQ group ids allowed to bind / confirm logins; empty denies all
 * @param bindCodeTtlSeconds   lifetime of a {@code /qqbind} code
 * @param loginCodeTtlSeconds  lifetime of a limbo quick-login code
 * @param maxAttemptsPerMinute per-QQ attempt ceiling for the bridge endpoints
 */
public record QqSettings(
        boolean enabled,
        String bindAddress,
        int port,
        String sharedSecret,
        Set<String> allowedGroups,
        int bindCodeTtlSeconds,
        int loginCodeTtlSeconds,
        int maxAttemptsPerMinute) {

    // Loopback default; PMD's hard-coded-IP rule does not apply to a bind address default.
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final int DEFAULT_PORT = 8765;
    public static final int DEFAULT_BIND_CODE_TTL_SECONDS = 300;
    public static final int DEFAULT_LOGIN_CODE_TTL_SECONDS = 120;
    public static final int DEFAULT_MAX_ATTEMPTS_PER_MINUTE = 5;

    public QqSettings() {
        this(false, DEFAULT_BIND_ADDRESS, DEFAULT_PORT, "", Set.of(),
                DEFAULT_BIND_CODE_TTL_SECONDS, DEFAULT_LOGIN_CODE_TTL_SECONDS,
                DEFAULT_MAX_ATTEMPTS_PER_MINUTE);
    }

    public QqSettings {
        bindAddress = bindAddress == null || bindAddress.isBlank()
                ? DEFAULT_BIND_ADDRESS
                : bindAddress.trim();
        sharedSecret = sharedSecret == null ? "" : sharedSecret;
        allowedGroups = allowedGroups == null
                ? Set.of()
                : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(allowedGroups));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public Set<String> getAllowedGroups() {
        return allowedGroups;
    }

    public int getBindCodeTtlSeconds() {
        return bindCodeTtlSeconds;
    }

    public int getLoginCodeTtlSeconds() {
        return loginCodeTtlSeconds;
    }

    public int getMaxAttemptsPerMinute() {
        return maxAttemptsPerMinute;
    }
}
