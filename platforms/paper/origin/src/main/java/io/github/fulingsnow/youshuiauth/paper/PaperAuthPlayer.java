package io.github.fulingsnow.youshuiauth.paper;

import net.kyori.adventure.text.Component;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ClientSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

/**
 * Paper 端玩家视图。客户端设置经 Paper 的 ClientOption API 采集
 * （particle 状态无对应 API，恒占位 unknown）。
 */
public final class PaperAuthPlayer implements AuthPlayer {

    /**
     * 连接生命周期注册表按包装实例做同一性比较，所以同一 Player 必须始终得到同一包装。
     * 用弱引用双向持有，玩家对象被回收后条目自动消失（core 的 Caffeine 在 shadow 时被重定位，
     * 平台侧不能直接引用）。
     */
    private static volatile io.github.fulingsnow.youshuiauth.platform.PlatformScheduler scheduler;

    private static final java.util.Map<Player, java.lang.ref.WeakReference<PaperAuthPlayer>> CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private final Player player;

    private PaperAuthPlayer(Player player) {
        this.player = player;
    }

    public static void bindScheduler(
            io.github.fulingsnow.youshuiauth.platform.PlatformScheduler platformScheduler) {
        scheduler = platformScheduler;
    }

    public static AuthPlayer of(Player player) {
        if (player == null) {
            return null;
        }
        synchronized (CACHE) {
            java.lang.ref.WeakReference<PaperAuthPlayer> ref = CACHE.get(player);
            PaperAuthPlayer wrapper = ref == null ? null : ref.get();
            if (wrapper == null) {
                wrapper = new PaperAuthPlayer(player);
                CACHE.put(player, new java.lang.ref.WeakReference<>(wrapper));
            }
            return wrapper;
        }
    }

    public Player handle() {
        return player;
    }

    @Override
    public UUID uniqueId() {
        return player.getUniqueId();
    }

    @Override
    public String username() {
        return player.getName();
    }

    @Override
    public InetAddress address() {
        InetSocketAddress remote = player.getAddress();
        return remote == null ? null : remote.getAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return player.getAddress();
    }

    @Override
    public Optional<InetSocketAddress> virtualHost() {
        return Optional.ofNullable(player.getVirtualHost());
    }

    @Override
    public String clientBrand() {
        return player.getClientBrandName();
    }

    @Override
    public int protocolVersion() {
        return player.getProtocolVersion();
    }

    /** 与 Velocity 的 Locale#toLanguageTag 对齐：客户端上报的 en_us 归一化为 en-US。 */
    private static String bcp47Locale(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return java.util.Locale.forLanguageTag(raw.replace('_', '-')).toLanguageTag();
    }

    @Override
    public Optional<ClientSettings> settings() {
        try {
            return Optional.of(new ClientSettings(
                    bcp47Locale(player.getClientOption(
                            com.destroystokyo.paper.ClientOption.LOCALE)),
                    String.valueOf(player.getClientOption(
                            com.destroystokyo.paper.ClientOption.CHAT_VISIBILITY)),
                    player.getClientOption(
                            com.destroystokyo.paper.ClientOption.CHAT_COLORS_ENABLED),
                    skinPartsKey(player.getClientOption(
                            com.destroystokyo.paper.ClientOption.SKIN_PARTS)),
                    String.valueOf(player.getClientOption(
                            com.destroystokyo.paper.ClientOption.MAIN_HAND)),
                    player.getClientOption(
                            com.destroystokyo.paper.ClientOption.ALLOW_SERVER_LISTINGS),
                    player.getClientOption(
                            com.destroystokyo.paper.ClientOption.TEXT_FILTERING_ENABLED),
                    String.valueOf(player.getClientOption(
                            com.destroystokyo.paper.ClientOption.PARTICLE_VISIBILITY))));
        } catch (RuntimeException e) {

            return Optional.empty();
        }
    }

    private static String skinPartsKey(com.destroystokyo.paper.SkinParts parts) {
        return io.github.fulingsnow.youshuiauth.platform.fingerprint.SkinPartsCodec.encode(
                parts.hasCapeEnabled(), parts.hasJacketEnabled(),
                parts.hasLeftSleeveEnabled(), parts.hasRightSleeveEnabled(),
                parts.hasLeftPantsEnabled(), parts.hasRightPantsEnabled(),
                parts.hasHatsEnabled());
    }

    @Override
    public boolean sendMessage(Component message) {

        PaperMessageRelay.enqueue(player, message);
        return true;
    }

    @Override
    public void disconnect(Component reason) {

        if (Bukkit.isPrimaryThread() || scheduler == null) {
            player.kick(reason);
            return;
        }
        scheduler.scheduleOnce(() -> {
            if (player.isOnline()) {
                player.kick(reason);
            }
        }, java.time.Duration.ZERO);
    }

    @Override
    public boolean isActive() {
        return player.isOnline();
    }

    @Override
    public boolean onlineMode() {

        return Bukkit.getOnlineMode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof PaperAuthPlayer paper
                && player.getUniqueId().equals(paper.player.getUniqueId());
    }

    @Override
    public int hashCode() {
        return player.getUniqueId().hashCode();
    }
}
