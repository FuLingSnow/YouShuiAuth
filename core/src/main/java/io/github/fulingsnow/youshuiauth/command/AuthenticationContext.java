package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;

import java.net.InetAddress;

/**
 * Immutable context for authentication operations.
 * Carries validated player data through the command pipeline,
 * reducing parameter passing between methods.
 */
public record AuthenticationContext(
        AuthPlayer player,
        String username,
        InetAddress playerAddress,
        RegisteredPlayer registeredPlayer,
        io.github.fulingsnow.youshuiauth.platform.ConnectionRef connectionOperation
) {
}
