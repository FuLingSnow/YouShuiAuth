package io.github.fulingsnow.youshuiauth.lifecycle;

import com.velocitypowered.api.proxy.Player;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer;

import java.util.function.Consumer;

/**
 * Velocity 侧的连接生命周期入口：把 {@link Player} 换成同一性的 {@link AuthPlayer} 包装后交给 core 实现。
 */
public final class VelocityConnectionLifecycleRegistry extends ConnectionLifecycleRegistry {

    public ConnectionRef activate(Player player, Consumer<AuthPlayer> afterPublication) {
        return activate(VelocityAuthPlayer.of(player), afterPublication);
    }

    public boolean markRetired(Player player, Runnable cleanup) {
        return markRetired(VelocityAuthPlayer.of(player), cleanup);
    }

    public boolean retire(Player player, boolean allowUnowned, Runnable cleanup) {
        return retire(VelocityAuthPlayer.of(player), allowUnowned, cleanup);
    }

    public ConnectionRef capture(Player player) {
        return capture(VelocityAuthPlayer.of(player));
    }
}
