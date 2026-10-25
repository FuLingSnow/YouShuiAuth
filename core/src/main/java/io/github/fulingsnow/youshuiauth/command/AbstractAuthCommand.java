package io.github.fulingsnow.youshuiauth.command;

import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.function.Predicate;

/**
 * 命令逻辑类的公共骨架：上下文、日志标记、可见性判据与「开关 + 参数」校验。
 * 判据由子类在构造时注入，避免每个命令重复实现 hasPermission。
 */
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
abstract class AbstractAuthCommand {

    protected static final String ERROR_DATABASE_QUERY = "error.database.query";
    protected static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");
    protected static final Marker DB_MARKER = MarkerFactory.getMarker("DATABASE");

    protected final CommandContext ctx;
    private final Predicate<AuthSender> visibility;

    AbstractAuthCommand(CommandContext ctx, Predicate<AuthSender> visibility) {
        this.ctx = ctx;
        this.visibility = visibility;
    }

    /**
     * 平台侧按此决定命令是否对来源可见。
     */
    public final boolean hasPermission(AuthSender sender) {
        return visibility.test(sender);
    }

    /**
     * 功能被配置关闭时通知玩家并返回 {@code false}。
     */
    protected boolean requireEnabled(AuthSender sender, boolean enabled, String messageKey) {
        if (enabled) {
            return true;
        }
        sender.sendMessage(ctx.messages().component(messageKey, NamedTextColor.RED));
        return false;
    }

    /**
     * 校验来源是玩家且参数个数正确；失败时已向来源发送提示。
     */
    protected CommandHelper.AuthInputs requireArgs(AuthSender sender, String[] args,
                                                   int expected, String usageKey) {
        return CommandHelper.requirePlayerAndArgs(
                sender, ctx.messages(), args, expected, usageKey);
    }

    /**
     * 「功能开关 + 参数校验」组合入口，供形态相同的密码类命令复用。
     */
    protected CommandHelper.AuthInputs beginPasswordCommand(
            AuthSender sender, String[] args, boolean enabled, String disabledKey, String usageKey) {
        if (!requireEnabled(sender, enabled, disabledKey)) {
            return null;
        }
        return requireArgs(sender, args, 2, usageKey);
    }
}
