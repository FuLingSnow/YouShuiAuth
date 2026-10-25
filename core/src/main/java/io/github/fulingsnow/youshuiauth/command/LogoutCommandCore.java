package io.github.fulingsnow.youshuiauth.command;

import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;

/**
 * Retires the invoking player's concrete connection before requesting its terminal disconnect.
 * The later {@code DisconnectEvent} repeats the same owner-aware cleanup idempotently.
 */
public class LogoutCommandCore {

    private static final String AUDIT_DETAILS = "connection-termination-requested";

    private final CommandContext context;

    public LogoutCommandCore(CommandContext context) {
        this.context = context;
    }

    public void execute(io.github.fulingsnow.youshuiauth.platform.AuthSender sender, String[] args) {
        CommandHelper.AuthInputs inputs = CommandHelper.requirePlayerAndArgs(
                sender, context.messages(), args, 0, "auth.logout.usage");
        if (inputs == null) {
            return;
        }

        io.github.fulingsnow.youshuiauth.platform.AuthPlayer player = inputs.player();
        if (!player.isActive()) {
            return;
        }

        String username = player.username();
        String playerIp = PlayerAddressUtils.getPlayerIp(player);
        try {
            context.retireConnectionOperation(player);
        } catch (RuntimeException cleanupFailure) {
            context.logger().error(
                    "Failed to retire logout connection for {}", username, cleanupFailure);
        }

        context.authCache().invalidatePlayerData(player.uniqueId());
        context.authCache().endSession(player.uniqueId());
        io.github.fulingsnow.youshuiauth.platform.QqOps qqAuthService = context.qqService();
        if (qqAuthService != null) {
            VirtualThreadExecutorProvider.submitTask(
                    () -> qqAuthService.revokeRememberTokens(player.uniqueId()));
        }
        try {
            player.disconnect(context.messages().component("auth.logged_out", NamedTextColor.YELLOW));
        } catch (RuntimeException disconnectFailure) {
            context.logger().error(
                    "Failed to request terminal logout disconnect for {}", username,
                    disconnectFailure);
        }

        AuditLogService auditLogService = context.auditLogService();
        if (auditLogService != null) {
            auditLogService.save(AuditEventType.LOGOUT, username, playerIp, AUDIT_DETAILS);
        }
    }
}
