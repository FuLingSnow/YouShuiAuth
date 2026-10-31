package io.github.fulingsnow.youshuiauth.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.util.PlayerLocaleUtils;

import java.util.Locale;
import java.util.Optional;

public final class VelocityAuthSender implements AuthSender {

    private static final String CONSOLE_NAME = "CONSOLE";

    private final CommandSource source;

    private VelocityAuthSender(CommandSource source) {
        this.source = source;
    }

    public static AuthSender of(CommandSource source) {
        return source == null ? null : new VelocityAuthSender(source);
    }

    public CommandSource handle() {
        return source;
    }

    public static CommandSource unwrapSource(AuthSender sender) {
        return sender instanceof VelocityAuthSender velocity ? velocity.handle() : null;
    }

    @Override
    public String name() {
        return source instanceof Player player ? player.getUsername() : CONSOLE_NAME;
    }

    @Override
    public boolean hasPermission(String node) {
        return source.hasPermission(node);
    }

    @Override
    public boolean sendMessage(Component message) {
        source.sendMessage(message);
        return true;
    }

    @Override
    public Optional<AuthPlayer> asPlayer() {
        return source instanceof Player player
                ? Optional.of(VelocityAuthPlayer.of(player))
                : Optional.empty();
    }

    @Override
    public Locale effectiveLocale() {
        if (source instanceof Player player) {
            Locale locale = PlayerLocaleUtils.clientLocale(player);
            return locale == null ? Locale.getDefault() : locale;
        }
        return Locale.getDefault();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof VelocityAuthSender other && source.equals(other.source);
    }

    @Override
    public int hashCode() {
        return source.hashCode();
    }
}
