package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.util.VelocityPlayerAddressUtils;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * Velocity 侧的命令工具外壳：只包含 {@code CommandSource}/{@code Invocation} 形态的入口，
 * 每个入口都转交给 {@link CommandHelper} 的平台无关实现。core 侧因此不引用 Velocity API。
 */
public final class VelocityCommandHelper {

    private VelocityCommandHelper() {
    }

    /**
     * Validates that command source is a player and returns the player instance.
     *
     * @param source   Command source to validate
     * @param messages Messages for error reporting
     * @return Player instance if valid, null if invalid (error already sent)
     */
    public static Player validatePlayerSource(CommandSource source, Messages messages) {
        if (!(source instanceof Player player)) {
            source.sendMessage(messages.component("error.player_only", NamedTextColor.RED));
            return null;
        }
        return player;
    }

    /**
     * Player-source + argument-count validation bundled into one call. Returns the player and
     * args when both checks pass; returns {@code null} when either fails (an error message has
     * already been sent to the source).
     */
    public static CommandInputs requirePlayerAndArgs(
            SimpleCommand.Invocation invocation, Messages messages, int requiredArgs, String usageKey) {
        Player player = validatePlayerSource(invocation.source(), messages);
        if (player == null) {
            return null;
        }
        String[] args = invocation.arguments();
        if (args.length != requiredArgs) {
            player.sendMessage(messages.component(usageKey, NamedTextColor.YELLOW));
            return null;
        }
        return new CommandInputs(player, args);
    }

    /**
     * Player-source check + at-least-one-arg requirement. Used by commands that dispatch on
     * a subcommand keyword (e.g. {@code /2fa setup}).
     */
    public static CommandInputs requirePlayerWithAtLeastOneArg(
            SimpleCommand.Invocation invocation, Messages messages, Component usageMessage) {
        Player player = validatePlayerSource(invocation.source(), messages);
        if (player == null) {
            return null;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            player.sendMessage(usageMessage);
            return null;
        }
        return new CommandInputs(player, args);
    }

    /** Carrier for {@link #requirePlayerAndArgs}. */
    public record CommandInputs(Player player, String[] args) {

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof CommandInputs(Player otherPlayer, String[] otherArgs)
                    && Objects.equals(player, otherPlayer)
                    && Arrays.equals(args, otherArgs);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(player) + Arrays.hashCode(args);
        }

        @Override
        public String toString() {
            return "CommandInputs[player=" + player + ", args=<redacted>, argCount=" + args.length + "]";
        }
    }

    /**
     * {@link SimpleCommand#hasPermission} helper for commands that should only be reachable
     * while the player is parked on the auth limbo. Console always returns {@code true}.
     */
    public static boolean isPlayerOnAuthServer(SimpleCommand.Invocation invocation, CommandContext ctx) {
        if (!(invocation.source() instanceof Player player)) {
            return true;
        }
        return ctx.transfers().isPlayerOnAuthServer(player.getUniqueId());
    }

    public static boolean isPlayerOnAuthServerAndNeedsAuthentication(
            SimpleCommand.Invocation invocation, CommandContext ctx) {
        if (!(invocation.source() instanceof Player player)) {
            return true;
        }
        if (!isPlayerOnAuthServer(invocation, ctx)) {
            return false;
        }
        String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);
        boolean authorized = ctx.authCache().isPlayerAuthorized(player.getUniqueId(), playerIp);
        boolean activeSession = ctx.authCache().hasActiveSession(
                player.getUniqueId(), player.getUsername(), playerIp);
        return !authorized || !activeSession;
    }

    /**
     * {@link SimpleCommand#hasPermission} helper for commands that require an already-authorized
     * player (e.g. {@code /changepassword}). Console always returns {@code true}.
     */
    public static boolean isPlayerAuthorized(SimpleCommand.Invocation invocation, CommandContext ctx) {
        if (!(invocation.source() instanceof Player player)) {
            return true;
        }
        return ctx.authCache().isPlayerAuthorized(
                player.getUniqueId(), VelocityPlayerAddressUtils.getPlayerIp(player));
    }

    public static boolean requireValidPassword(
            Player player, String password, Settings settings, Messages messages) {
        return requireValidPassword(player, password, settings.getPasswordSettings(), messages);
    }

    static boolean requireValidPassword(
            Player player, String password, Settings.PasswordSettings passwordSettings,
            Messages messages) {
        return CommandHelper.requireValidPassword(
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player),
                password, passwordSettings, messages);
    }

    public static boolean requirePasswordsMatch(
            Player player, String password, String confirm, Messages messages) {
        return CommandHelper.requirePasswordsMatch(
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.of(player),
                password, confirm, messages);
    }

    /** Carrier for {@link #requireAdmin}. */
    public record AdminCommandInputs(CommandSource source, String[] args) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof AdminCommandInputs(CommandSource otherSource, String[] otherArgs)
                    && Objects.equals(source, otherSource)
                    && Arrays.equals(args, otherArgs);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(source) + Arrays.hashCode(args);
        }

        @Override
        public String toString() {
            return "AdminCommandInputs[source=" + source + ", args=<redacted>, argCount=" + args.length + "]";
        }
    }

    public static void sendError(CommandSource source, String message) {
        source.sendMessage(ValidationUtils.createErrorComponent(message));
    }

    public static void sendError(CommandSource source, Messages messages, String key) {
        source.sendMessage(messages.component(key, NamedTextColor.RED));
    }

    public static void sendSuccess(CommandSource source, String message) {
        source.sendMessage(ValidationUtils.createSuccessComponent(message));
    }

    public static void sendSuccess(CommandSource source, Messages messages, String key) {
        source.sendMessage(messages.component(key, NamedTextColor.GREEN));
    }

    public static void sendWarning(CommandSource source, String message) {
        source.sendMessage(ValidationUtils.createWarningComponent(message));
    }

    public static void runAsyncCommand(Runnable task, Messages messages,
                                       CommandSource source, String errorKey) {
        CommandHelper.runAsyncCommand(
                task, messages, errorKey,
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender.of(source)::sendMessage);
    }

    public static void runAsyncCommandWithTimeout(Runnable task, Messages messages,
                                                  CommandSource source, String errorKey,
                                                  String timeoutKey) {
        CommandHelper.runAsyncCommandWithTimeout(
                task, messages, errorKey, timeoutKey,
                io.github.fulingsnow.youshuiauth.velocity.VelocityAuthSender.of(source)::sendMessage);
    }
}
