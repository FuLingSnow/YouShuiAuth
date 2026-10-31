package io.github.fulingsnow.youshuiauth.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.player.SkinParts;
import net.kyori.adventure.text.Component;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ClientSettings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

public final class VelocityAuthPlayer implements AuthPlayer {

    private final Player player;

    /**
     * 连接生命周期注册表按包装实例做同一性比较，所以同一 Player 必须始终得到同一包装。
     */
    private static final com.github.benmanes.caffeine.cache.Cache<Player, VelocityAuthPlayer> CACHE =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .weakKeys()
                    .weakValues()
                    .maximumSize(10_000)
                    .build();

    private VelocityAuthPlayer(Player player) {
        this.player = player;
    }

    public static AuthPlayer of(Player player) {
        return player == null ? null : CACHE.get(player, VelocityAuthPlayer::new);
    }

    public Player handle() {
        return player;
    }

    public static Player unwrap(AuthPlayer authPlayer) {
        return authPlayer instanceof VelocityAuthPlayer velocity ? velocity.handle() : null;
    }

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String username() {
        return player.getUsername();
    }

    @Override
    public InetAddress address() {
        InetSocketAddress remote = player.getRemoteAddress();
        return remote == null ? null : remote.getAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return player.getRemoteAddress();
    }

    @Override
    public Optional<InetSocketAddress> virtualHost() {
        return player.getVirtualHost();
    }

    @Override
    public String clientBrand() {
        return player.getClientBrand();
    }

    @Override
    public int protocolVersion() {
        return player.getProtocolVersion().getProtocol();
    }

    @Override
    public Optional<ClientSettings> settings() {
        if (!player.hasSentPlayerSettings()) {
            return Optional.empty();
        }
        PlayerSettings settings = player.getPlayerSettings();
        if (settings == null) {
            return Optional.empty();
        }
        return Optional.of(new ClientSettings(
                settings.getLocale() == null ? null : settings.getLocale().toLanguageTag(),
                String.valueOf(settings.getChatMode()),
                settings.hasChatColors(),
                io.github.fulingsnow.youshuiauth.util.DeviceFingerprints.skinPartsKey(settings.getSkinParts()),
                String.valueOf(settings.getMainHand()),
                settings.isClientListingAllowed(),
                settings.isTextFilteringEnabled(),
                String.valueOf(settings.getParticleStatus())));
    }

    @Override
    public boolean sendMessage(Component message) {
        player.sendMessage(message);
        return true;
    }

    @Override
    public void disconnect(Component reason) {
        player.disconnect(reason);
    }

    @Override
    public boolean isActive() {
        return player.isActive();
    }

    @Override
    public boolean onlineMode() {
        return player.isOnlineMode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VelocityAuthPlayer other)) {
            return false;
        }
        return player.getUniqueId().equals(other.player.getUniqueId());
    }

    @Override
    public int hashCode() {
        return player.getUniqueId().hashCode();
    }
}
