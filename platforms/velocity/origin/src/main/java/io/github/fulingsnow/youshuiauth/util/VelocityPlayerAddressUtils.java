package io.github.fulingsnow.youshuiauth.util;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Velocity 侧的地址解析入口；逻辑复用 {@link PlayerAddressUtils} 的 AuthPlayer 实现。
 */
public final class VelocityPlayerAddressUtils {

    private static final Logger logger = LoggerFactory.getLogger(VelocityPlayerAddressUtils.class);
    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");

    private VelocityPlayerAddressUtils() {
    }

    public static String getPlayerIp(Player player) {
        return PlayerAddressUtils.getPlayerIp(VelocityAuthPlayer.of(player));
    }

    public static InetAddress getPlayerAddress(Player player) {
        return PlayerAddressUtils.getPlayerAddress(VelocityAuthPlayer.of(player));
    }

    /**
     * PreLoginEvent 阶段还没有 Player 实例，只能从连接信息提取。
     */
    public static InetAddress getAddressFromPreLogin(PreLoginEvent event) {
        if (event == null) {
            return null;
        }
        try {
            com.velocitypowered.api.proxy.InboundConnection connection = event.getConnection();
            if (connection == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug(AUTH_MARKER, "PreLoginEvent has null connection");
                }
                return null;
            }
            java.net.SocketAddress socketAddress = connection.getRemoteAddress();
            if (socketAddress instanceof InetSocketAddress inetSocketAddress) {
                return PlayerAddressUtils.extractAddressFromRemote(
                        inetSocketAddress, event.getUsername());
            }
            return null;
        } catch (Exception e) {
            if (logger.isErrorEnabled()) {
                logger.error(AUTH_MARKER, "Error extracting address from PreLoginEvent", e);
            }
            return null;
        }
    }

    public static boolean hasValidAddress(Player player) {
        return player != null && player.getRemoteAddress() != null;
    }
}
