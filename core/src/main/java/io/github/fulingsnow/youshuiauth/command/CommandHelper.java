package io.github.fulingsnow.youshuiauth.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Utility class for common command operations to reduce duplication.
 * Provides reusable methods for validation, messaging, and async execution.
 */
public final class CommandHelper {

    private static final String MSG_KEY_SERVER_SHUTTING_DOWN = "system.shutting_down";
    private static final String MSG_KEY_SERVER_OVERLOADED = "system.overloaded";

    private CommandHelper() {

    }

    /**
     * Repaints {@code command} inside {@code line} with {@code highlight} so players can spot the
     * exact text they must send. The optional click event is applied to every segment.
     */
    static Component withHighlightedCommand(Component line, String command, NamedTextColor base,
            NamedTextColor highlight, ClickEvent click) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        int index = plain.indexOf(command);
        if (index < 0) {
            return click == null ? line : line.clickEvent(click);
        }
        Component before = Component.text(plain.substring(0, index), base);
        Component middle = Component.text(command, highlight);
        Component after = Component.text(plain.substring(index + command.length()), base);
        if (click != null) {
            before = before.clickEvent(click);
            middle = middle.clickEvent(click);
            after = after.clickEvent(click);
        }
        return before.append(middle).append(after);
    }

    public static AuthInputs requirePlayerWithAtLeastOneArg(
            io.github.fulingsnow.youshuiauth.platform.AuthSender sender, Messages messages,
            String[] args, net.kyori.adventure.text.Component usageMessage) {
        io.github.fulingsnow.youshuiauth.platform.AuthPlayer player = validatePlayerSource(sender, messages);
        if (player == null) {
            return null;
        }
        if (args.length == 0) {
            player.sendMessage(usageMessage);
            return null;
        }
        return new AuthInputs(player, args);
    }

    /**
     * Carrier for the result of {@link #requirePlayerAndArgs(SimpleCommand.Invocation, Messages, int, String)}
     * — keeps the (player, args) pair as one return value so callers don't repeat the early-return guard.
     */
    public static AuthInputs requirePlayerAndArgs(
            io.github.fulingsnow.youshuiauth.platform.AuthSender sender, Messages messages,
            String[] args, int requiredArgs, String usageKey) {
        io.github.fulingsnow.youshuiauth.platform.AuthPlayer player = validatePlayerSource(sender, messages);
        if (player == null) {
            return null;
        }
        if (args.length != requiredArgs) {
            player.sendMessage(messages.component(usageKey, NamedTextColor.YELLOW));
            return null;
        }
        return new AuthInputs(player, args);
    }

    public record AuthInputs(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String[] args) {
    }

    /**
     * Permission gate for authentication commands. An authorized player with a matching active
     * session no longer needs password commands even if a delayed transfer still leaves them on
     * limbo. An authorized player whose session expired keeps the commands visible so recovery
     * remains possible.
     */
    public static boolean isPlayerOnAuthServerAndNeedsAuthentication(
            io.github.fulingsnow.youshuiauth.platform.AuthSender sender, CommandContext ctx) {
        java.util.Optional<io.github.fulingsnow.youshuiauth.platform.AuthPlayer> opt = sender.asPlayer();
        if (opt.isEmpty()) {
            return true;
        }
        io.github.fulingsnow.youshuiauth.platform.AuthPlayer player = opt.get();
        if (!ctx.transfers().isPlayerOnAuthServer(player.uniqueId())) {
            return false;
        }
        String playerIp = PlayerAddressUtils.getPlayerIp(player);
        boolean authorized = ctx.authCache().isPlayerAuthorized(player.uniqueId(), playerIp);
        boolean activeSession = ctx.authCache().hasActiveSession(
                player.uniqueId(), player.username(), playerIp);
        return !authorized || !activeSession;
    }

    /**
     * {@link SimpleCommand#hasPermission} helper for commands that require an already-authorized
     * player (e.g. {@code /changepassword}). Console always returns {@code true}.
     */
    public static boolean isPlayerAuthorized(
            io.github.fulingsnow.youshuiauth.platform.AuthSender sender, CommandContext ctx) {
        java.util.Optional<io.github.fulingsnow.youshuiauth.platform.AuthPlayer> player = sender.asPlayer();
        if (player.isEmpty()) {
            return true;
        }
        return ctx.authCache().isPlayerAuthorized(
                player.get().uniqueId(),
                PlayerAddressUtils.getPlayerIp(player.get()));
    }

    /**
     * Runs {@link ValidationUtils#validatePassword} and short-circuits the caller with a player
     * error message when the password is rejected.
     *
     * @return {@code true} when the password passes policy; {@code false} after sending the error.
     */
    static boolean requireValidPassword(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player,
            String password,
            Settings.PasswordSettings passwordSettings,
            Messages messages) {
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePassword(password, passwordSettings, messages);
        if (!result.valid()) {
            player.sendMessage(messages.componentFromResolvedText(
                    result.getErrorMessage(), NamedTextColor.RED));
            return false;
        }
        return true;
    }

    /**
     * Runs {@link ValidationUtils#validatePasswordMatch} and short-circuits the caller with a
     * player error message when the two passwords disagree.
     */
    public static boolean requirePasswordsMatch(
            io.github.fulingsnow.youshuiauth.platform.AuthPlayer player, String password, String confirm,
            Messages messages) {
        ValidationUtils.ValidationResult result =
                ValidationUtils.validatePasswordMatch(password, confirm, messages);
        if (!result.valid()) {
            player.sendMessage(messages.componentFromResolvedText(
                    result.getErrorMessage(), NamedTextColor.RED));
            return false;
        }
        return true;
    }

    /**
     * Convenience wrapper: gate by admin permission, then run {@code body} with the unpacked
     * {@code (source, args)}. Callers that need the typical "permission check → bail on null →
     * unpack inputs" boilerplate use this directly instead of repeating those 6-7 lines.
     */
    public static void runAsAdmin(io.github.fulingsnow.youshuiauth.platform.AuthSender sender, String[] args, Messages messages,
            java.util.function.BiConsumer<io.github.fulingsnow.youshuiauth.platform.AuthSender, String[]> body) {
        if (!checkAdminPermission(sender, messages)) {
            return;
        }
        body.accept(sender, args);
    }

    /**
     * Handles async command exceptions (PMD CPD fix - extracted duplicate error handling).
     *
     * @param throwable Exception that occurred
     * @param source    Command source for error messages
     * @param messages  Messages for error reporting
     * @param errorKey  Message key for database errors
     */
    private static void handleAsyncCommandException(
            Throwable throwable, Messages messages, String errorKey,
            java.util.function.Consumer<net.kyori.adventure.text.Component> messageSender) {
        if (throwable instanceof java.util.concurrent.RejectedExecutionException) {
            messageSender.accept(messages.component(MSG_KEY_SERVER_OVERLOADED, NamedTextColor.RED));
        } else {
            messageSender.accept(messages.component(errorKey, NamedTextColor.RED));
        }
    }

    /**
     * Executes a command asynchronously with standard exception handling.
     *
     * @param task     Command task to execute
     * @param messages Messages for error reporting
     * @param source   Command source for error messages
     * @param errorKey Message key for database errors
     */
    public static io.github.fulingsnow.youshuiauth.platform.AuthPlayer validatePlayerSource(
            io.github.fulingsnow.youshuiauth.platform.AuthSender sender, Messages messages) {
        java.util.Optional<io.github.fulingsnow.youshuiauth.platform.AuthPlayer> player = sender.asPlayer();
        if (player.isEmpty()) {
            sender.sendMessage(messages.component("error.player_only", NamedTextColor.RED));
            return null;
        }
        return player.get();
    }

    /**
     * 管理权限判定：控制台/RCON 没有玩家身份，必须视为运维管理员——
     * 各平台对「非玩家发送者的 hasPermission」实现不一致（Paper 的 RCON 会返回 false），
     * 靠权限节点判断会让运维在远程控制台被拒。
     */
    public static boolean isAdmin(io.github.fulingsnow.youshuiauth.platform.AuthSender sender) {
        if (sender == null) {
            return false;
        }
        return sender.asPlayer().isEmpty() || sender.hasPermission("youshuiauth.admin");
    }

    public static boolean checkAdminPermission(
            io.github.fulingsnow.youshuiauth.platform.AuthSender sender, Messages messages) {
        if (!isAdmin(sender)) {
            sender.sendMessage(messages.component("error.permission", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    public static void sendError(io.github.fulingsnow.youshuiauth.platform.AuthSender sender,
                                 Messages messages, String key) {
        sender.sendMessage(messages.component(key, NamedTextColor.RED));
    }

    public static void sendSuccess(io.github.fulingsnow.youshuiauth.platform.AuthSender sender,
                                   Messages messages, String key) {
        sender.sendMessage(messages.component(key, NamedTextColor.GREEN));
    }

    public static void sendWarning(io.github.fulingsnow.youshuiauth.platform.AuthSender sender,
                                   String message) {
        sender.sendMessage(ValidationUtils.createWarningComponent(message));
    }

    public static void runAsyncCommand(Runnable task, Messages messages,
                                       io.github.fulingsnow.youshuiauth.platform.AuthSender sender,
                                       String errorKey) {
        runAsyncCommand(task, messages, errorKey, sender::sendMessage);
    }

    static void runAsyncCommand(
            Runnable task, Messages messages, String errorKey,
            java.util.function.Consumer<net.kyori.adventure.text.Component> messageSender) {
        CompletableFuture<Void> future = submitAsyncCommand(task, messages, messageSender);
        if (future == null) {
            return;
        }

        future.exceptionally(throwable -> {
            handleAsyncCommandException(throwable, messages, errorKey, messageSender);
            return null;
        });
    }

    /**
     * Executes a command asynchronously with timeout and standard exception handling.
     *
     * @param task       Command task to execute
     * @param messages   Messages for error reporting
     * @param source     Command source for error messages
     * @param errorKey   Message key for database errors
     * @param timeoutKey Message key for timeout errors
     */
    static void runAsyncCommandWithTimeout(
            Runnable task, Messages messages, String errorKey,
            String timeoutKey,
            java.util.function.Consumer<net.kyori.adventure.text.Component> messageSender) {
        runAsyncCommandWithTimeout(
                task, messages, errorKey, messageSender,
                () -> messageSender.accept(
                        messages.component(timeoutKey, NamedTextColor.RED)));
    }

    static void runAsyncCommandWithTimeout(
            Runnable task, Messages messages, String errorKey,
            java.util.function.Consumer<net.kyori.adventure.text.Component> messageSender,
            Runnable timeoutHandler) {
        CompletableFuture<Void> future = submitAsyncCommand(task, messages, messageSender);
        if (future == null) {
            return;
        }

        future.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(throwable -> {
                    if (throwable instanceof java.util.concurrent.TimeoutException) {
                        timeoutHandler.run();
                    } else {
                        handleAsyncCommandException(throwable, messages, errorKey, messageSender);
                    }
                    return null;
                });
    }

    private static CompletableFuture<Void> submitAsyncCommand(
            Runnable task, Messages messages,
            java.util.function.Consumer<net.kyori.adventure.text.Component> messageSender) {
        if (VirtualThreadExecutorProvider.isShutdown()) {
            messageSender.accept(messages.component(MSG_KEY_SERVER_SHUTTING_DOWN, NamedTextColor.RED));
            return null;
        }
        try {
            return CompletableFuture.runAsync(
                    task, VirtualThreadExecutorProvider.getVirtualExecutor());
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            messageSender.accept(messages.component(MSG_KEY_SERVER_SHUTTING_DOWN, NamedTextColor.RED));
            return null;
        }
    }
}
