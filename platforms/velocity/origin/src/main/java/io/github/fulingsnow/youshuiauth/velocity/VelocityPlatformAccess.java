package io.github.fulingsnow.youshuiauth.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.PlatformAccess;
import io.github.fulingsnow.youshuiauth.platform.PlatformScheduler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class VelocityPlatformAccess implements PlatformAccess {

    private static final String PLATFORM_ID = "velocity";

    private final ProxyServer server;
    private final Path dataDirectory;
    private final PlatformScheduler scheduler;

    public VelocityPlatformAccess(Object plugin, ProxyServer server, Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.scheduler = new VelocityScheduler(plugin, server);
    }

    @Override
    public Optional<AuthPlayer> player(UUID uniqueId) {
        if (uniqueId == null) {
            return Optional.empty();
        }
        return server.getPlayer(uniqueId).map(VelocityAuthPlayer::of);
    }

    @Override
    public Optional<AuthPlayer> player(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return server.getPlayer(username).map(VelocityAuthPlayer::of);
    }

    @Override
    public Collection<AuthPlayer> onlinePlayers() {
        List<AuthPlayer> players = new ArrayList<>();
        for (Player player : server.getAllPlayers()) {
            players.add(VelocityAuthPlayer.of(player));
        }
        return players;
    }

    @Override
    public int playerCount() {
        return server.getPlayerCount();
    }

    @Override
    public PlatformScheduler scheduler() {
        return scheduler;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public String platformId() {
        return PLATFORM_ID;
    }
}
