package io.github.fulingsnow.youshuiauth.i18n;

import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;

/**
 * 玩家消息渲染：按客户端语言选取文案（开关关闭或语言缺失时回落到默认语言）。
 *
 * <p>代理端与服务端共用同一套判定，避免一端按客户端语言、另一端只用默认语言。</p>
 */
public final class ClientMessages {

    private ClientMessages() {
    }

    public static Component of(AuthPlayer player, Settings settings, Messages messages,
                               String key, NamedTextColor color, Object... args) {
        if (settings.isDetectClientLanguage()) {
            return messages.componentForLocale(clientLocale(player), key, color, args);
        }
        return messages.component(key, color, args);
    }

    static Locale clientLocale(AuthPlayer player) {
        if (player == null) {
            return null;
        }
        return player.settings()
                .map(io.github.fulingsnow.youshuiauth.platform.ClientSettings::locale)
                .filter(locale -> locale != null && !locale.isBlank())
                .map(Locale::forLanguageTag)
                .orElse(null);
    }
}
