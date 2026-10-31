package io.github.fulingsnow.youshuiauth.util;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.i18n.Messages;

/**
 * Player-facing message resolution honoring client-language detection (issues #50/#52):
 * the player's Minecraft locale when enabled and translated, otherwise the configured
 * default language (with per-key English fallback inside {@link Messages}).
 */
public final class PlayerMessages {

    private PlayerMessages() {
    }

    public static Component playerMessage(@javax.annotation.Nullable Player player,
                                          Settings settings, Messages messages,
                                          String key, NamedTextColor color, Object... args) {
        if (player == null) {
            return messages.component(key, color, args);
        }
        return playerMessage(io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player),
                settings, messages, key, color, args);
    }

    public static Component playerMessage(
            @javax.annotation.Nullable io.github.fulingsnow.youshuiauth.platform.AuthPlayer player,
            Settings settings, Messages messages,
            String key, NamedTextColor color, Object... args) {
        return io.github.fulingsnow.youshuiauth.i18n.ClientMessages.of(
                player, settings, messages, key, color, args);
    }
}
