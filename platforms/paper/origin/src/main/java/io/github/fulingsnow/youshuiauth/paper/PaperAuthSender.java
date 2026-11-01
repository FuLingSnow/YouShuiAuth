package io.github.fulingsnow.youshuiauth.paper;

import net.kyori.adventure.text.Component;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Optional;

/**
 * Paper 端命令源视图；控制台没有玩家视角，回退到 "CONSOLE" 与默认 Locale。
 */
public final class PaperAuthSender implements AuthSender {

    private final CommandSender sender;

    private PaperAuthSender(CommandSender sender) {
        this.sender = sender;
    }

    public static AuthSender of(CommandSender sender) {
        return sender == null ? null : new PaperAuthSender(sender);
    }

    @Override
    public String name() {
        return sender.getName();
    }

    @Override
    public boolean hasPermission(String node) {
        return sender.hasPermission(node);
    }

    @Override
    public boolean sendMessage(Component message) {
        sender.sendMessage(message);
        return true;
    }

    @Override
    public Optional<AuthPlayer> asPlayer() {
        return sender instanceof Player player
                ? Optional.of(PaperAuthPlayer.of(player))
                : Optional.empty();
    }

    @Override
    public Locale effectiveLocale() {
        return sender instanceof Player player ? player.locale() : Locale.getDefault();
    }
}
