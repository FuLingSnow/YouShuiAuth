package io.github.fulingsnow.youshuiauth.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Utility class for extracting IP addresses from players and connection events.
 * Provides consistent address extraction across different event types.
 * <p>
 * Thread-safe: stateless utility methods.
 */
public final class PlayerAddressUtils {

    private static final Logger logger = LoggerFactory.getLogger(PlayerAddressUtils.class);
    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");

    private PlayerAddressUtils() {

    }

    @javax.annotation.Nonnull
    public static String getPlayerIp(
            @javax.annotation.Nullable io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        InetAddress address = getPlayerAddress(player);
        if (address == null) {
            return "unknown";
        }
        String hostAddress = address.getHostAddress();
        return hostAddress != null ? hostAddress : "unknown";
    }

    @javax.annotation.Nullable
    public static InetAddress getPlayerAddress(
            @javax.annotation.Nullable io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
        if (player == null) {
            return null;
        }
        return extractAddressFromRemote(player.remoteAddress(), player.username());
    }

    /**
     * Extracts InetAddress from remote socket address with logging.
     * Shared logic for both Player and PreLoginEvent address extraction.
     *
     * @param address   Remote socket address
     * @param identifier Identifier for logging (username)
     * @return InetAddress or null if unavailable
     */
    @javax.annotation.Nullable
    static InetAddress extractAddressFromRemote(
            @javax.annotation.Nullable InetSocketAddress address,
            String identifier) {
        if (address == null) {
            if (logger.isWarnEnabled()) {
                logger.warn(AUTH_MARKER, "Player {} has null remote address", identifier);
            }
            return null;
        }
        return address.getAddress();
    }

}
