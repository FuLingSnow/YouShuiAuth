package io.github.fulingsnow.youshuiauth.util;

import com.velocitypowered.api.proxy.Player;

import java.util.Locale;

/** Reads the Minecraft client locale reported during the settings handshake. */
public final class PlayerLocaleUtils {

    private PlayerLocaleUtils() {
    }

    /**
     * @return the client's locale, or {@code null} when the player or their settings are
     *         unavailable (early login phase, test doubles, broken implementation)
     */
    @javax.annotation.Nullable
    public static Locale clientLocale(@javax.annotation.Nullable Player player) {
        if (player == null) {
            return null;
        }
        try {
            return player.getEffectiveLocale();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @javax.annotation.Nullable
    public static Locale clientLocale(
            @javax.annotation.Nullable io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
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
