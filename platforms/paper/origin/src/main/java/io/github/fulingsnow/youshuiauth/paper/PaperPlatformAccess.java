package io.github.fulingsnow.youshuiauth.paper;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.PlatformAccess;
import io.github.fulingsnow.youshuiauth.platform.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PaperPlatformAccess implements PlatformAccess {

    private final PlatformScheduler scheduler;
    private final Path dataDirectory;

    public PaperPlatformAccess(PlatformScheduler scheduler, Path dataDirectory) {
        this.scheduler = scheduler;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public Optional<AuthPlayer> player(UUID uniqueId) {
        Player player = Bukkit.getPlayer(uniqueId);
        return player == null ? Optional.empty() : Optional.of(PaperAuthPlayer.of(player));
    }

    @Override
    public Optional<AuthPlayer> player(String username) {
        Player player = Bukkit.getPlayerExact(username);
        return player == null ? Optional.empty() : Optional.of(PaperAuthPlayer.of(player));
    }

    @Override
    public Collection<AuthPlayer> onlinePlayers() {
        List<AuthPlayer> players = new java.util.ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(PaperAuthPlayer.of(player));
        }
        return players;
    }

    @Override
    public int playerCount() {
        return Bukkit.getOnlinePlayers().size();
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
        return "paper";
    }
}
