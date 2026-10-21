package net.rafalohaki.veloauth.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rafalohaki.veloauth.i18n.Messages;
import net.rafalohaki.veloauth.qq.QqResult;

/**
 * {@code /qqbind} — issues a single-use code that binds the current account to a QQ identity.
 *
 * <p>Usable from any server, which matters for Bedrock players: Floodgate lets them bypass the
 * auth server, so they never see limbo and must be able to bind from the backend.
 */
class QqBindCommand implements SimpleCommand {

    private final QqAuthService service;
    private final Messages messages;

    QqBindCommand(QqAuthService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = CommandHelper.validatePlayerSource(invocation.source(), messages);
        if (player == null) {
            return;
        }

        QqAuthService.BindCodeIssue issue = service.createBindCode(player);
        QqResult result = issue.result();
        switch (result) {
            case OK -> sendCode(player, issue);
            case DISABLED -> player.sendMessage(messages.component("qq.bind.disabled", NamedTextColor.RED));
            case NOT_AUTHENTICATED ->
                    player.sendMessage(messages.component("qq.bind.not_authenticated", NamedTextColor.RED));
            case ACCOUNT_EXISTS ->
                    player.sendMessage(messages.component("qq.bind.account_exists", NamedTextColor.RED));
            case RATE_LIMITED ->
                    player.sendMessage(messages.component("qq.bind.rate_limited", NamedTextColor.YELLOW));
            case ALREADY_BOUND ->
                    player.sendMessage(messages.component("qq.bind.already_bound", NamedTextColor.YELLOW));
            default -> player.sendMessage(messages.component("qq.bind.failed", NamedTextColor.RED));
        }
    }

    private void sendCode(Player player, QqAuthService.BindCodeIssue issue) {
        int minutes = Math.max(1, issue.ttlSeconds() / 60);
        String key = issue.createsAccount() ? "qq.bind.register_created" : "qq.bind.created";
        Component message = messages.component(key, NamedTextColor.GREEN, issue.code(), minutes);

        // The AstrBot command is the Chinese "/绑定"; copy exactly what the player must send.
        String copyCommand = "/绑定 " + issue.code();
        ClickEvent copyEvent = ClickEvent.copyToClipboard(copyCommand);
        Component copy = messages.component("qq.bind.copy", NamedTextColor.AQUA)
                .clickEvent(copyEvent);

        if (issue.createsAccount()) {
            Component line = messages.component("qq.bind.register_header", NamedTextColor.GREEN)
                    .append(Component.newline())
                    .append(CommandHelper.withHighlightedCommand(
                            message, copyCommand, NamedTextColor.GREEN, NamedTextColor.GOLD,
                            copyEvent))
                    .append(Component.space())
                    .append(withJoinGroup(copy));
            player.sendMessage(line);
            return;
        }

        player.sendMessage(message);
        Component manual = messages.component("qq.bind.manual", NamedTextColor.YELLOW, issue.code());
        Component manualLine = CommandHelper.withHighlightedCommand(
                manual, copyCommand, NamedTextColor.YELLOW, NamedTextColor.GOLD, copyEvent);
        player.sendMessage(manualLine.append(Component.space()).append(withJoinGroup(copy)));
    }

    private Component withJoinGroup(Component copy) {
        String joinUrl = service.getJoinUrl();
        if (joinUrl.isBlank()) {
            return copy;
        }
        return copy.append(Component.space()).append(
                messages.component("qq.bind.join_group", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.openUrl(joinUrl)));
    }
}
