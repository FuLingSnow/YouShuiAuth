package io.github.fulingsnow.youshuiauth.platform;

import net.kyori.adventure.text.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

public interface AuthPlayer {

    UUID uniqueId();

    String username();

    InetAddress address();

    InetSocketAddress remoteAddress();

    Optional<InetSocketAddress> virtualHost();

    String clientBrand();

    int protocolVersion();

    Optional<ClientSettings> settings();

    boolean sendMessage(Component message);

    void disconnect(Component reason);

    boolean isActive();

    boolean onlineMode();
}
