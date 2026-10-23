package io.github.fulingsnow.youshuiauth.runtime;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 未认证会话到期后的收尾：已授权则跳过，否则清缓存、结束会话并踢出。
 *
 * <p>代理端与服务端共用，避免两端各写一套「判定授权 → 清缓存 → 踢人」的链路。</p>
 */
public final class SessionExpiryFlow {

    private final AuthCache cache;
    private final Messages messages;

    public SessionExpiryFlow(AuthCache cache, Messages messages) {
        this.cache = cache;
        this.messages = messages;
    }

    /**
     * @return true 表示已经处理（踢出或已授权跳过），false 表示玩家不可用
     */
    public boolean expire(AuthPlayer player, int timeoutSeconds) {
        if (player == null || !player.isActive()) {
            return false;
        }
        String ip = PlayerAddressUtils.getPlayerIp(player);
        if (cache.isPlayerAuthorized(player.uniqueId(), ip)) {
            return true;
        }
        cache.invalidatePlayerData(player.uniqueId());
        cache.endSession(player.uniqueId());
        player.disconnect(messages.component("auth.timeout.kick", NamedTextColor.RED, timeoutSeconds));
        return true;
    }
}
