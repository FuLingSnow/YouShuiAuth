package io.github.fulingsnow.youshuiauth.command;

import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.util.DatabaseErrorHandler;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.UUID;

/**
 * Handles the admin /unregister command.
 * Deletes a player account from the database and cleans up all caches.
 */
public class UnregisterCommandCore {

    private static final String ERROR_DATABASE_QUERY = "error.database.query";
    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");
    private static final Marker DB_MARKER = MarkerFactory.getMarker("DATABASE");

    private final CommandContext ctx;

    public UnregisterCommandCore(CommandContext ctx) {
        this.ctx = ctx;
    }

    public void execute(AuthSender sender, String[] args) {
        if (!CommandHelper.checkAdminPermission(sender, ctx.messages())) {
            return;
        }
        if (args.length != 1) {
            CommandHelper.sendError(sender, ctx.messages(), "admin.unregister.usage");
            return;
        }
        String nickname = args[0];
        CommandHelper.runAsyncCommand(() -> processAdminUnregistration(sender, nickname),
                ctx.messages(), sender, ERROR_DATABASE_QUERY);
    }

    private void processAdminUnregistration(AuthSender sender, String nickname) {
        try {
            var dbResult = ctx.databaseManager().findPlayerByNickname(nickname).join();

            if (handleDatabaseError(dbResult, sender, nickname, "Admin unregistration failed for")) {
                return;
            }

            RegisteredPlayer registeredPlayer = dbResult.getValue();
            if (registeredPlayer == null) {
                sender.sendMessage(ctx.messages().component(
                        "admin.player_not_found", NamedTextColor.RED, nickname));
                return;
            }

            UUID playerUuid = parsePlayerUuid(registeredPlayer, nickname, sender);
            if (playerUuid == null) {
                return;
            }

            var deleteResult = ctx.databaseManager().deletePlayer(nickname).join();

            if (handleDatabaseError(deleteResult, sender, nickname,
                    "Admin unregistration delete failed for")) {
                return;
            }

            boolean deleted = Boolean.TRUE.equals(deleteResult.getValue());
            if (deleted) {
                ctx.authCache().removeAuthorizedPlayer(playerUuid);
                ctx.authCache().endSession(playerUuid);
                ctx.authCache().removePremiumPlayer(nickname);

                ctx.platformAccess().player(nickname).ifPresent(player -> {
                    player.disconnect(ctx.messages().component(
                            "general.kick.message", NamedTextColor.YELLOW));
                    ctx.logger().info("Disconnected player {} — account deleted by admin", nickname);
                });

                sender.sendMessage(ctx.messages().component(
                        "admin.account_deleted", NamedTextColor.GREEN, nickname));
                String adminName = sender.name();
                ctx.logger().info(AUTH_MARKER, "Admin {} deleted player account: {}",
                        adminName, nickname);

                AuditLogService audit = ctx.auditLogService();
                if (audit != null) {
                    String adminIp = sender.asPlayer()
                            .map(PlayerAddressUtils::getPlayerIp)
                            .orElse(null);
                    audit.save(AuditEventType.UNREGISTER, nickname, adminIp, "admin=" + adminName);
                }

            } else {
                CommandHelper.sendError(sender, ctx.messages(), ERROR_DATABASE_QUERY);
                ctx.logger().error(DB_MARKER,
                        "Failed to delete player account: {} (admin action)", nickname);
            }

        } catch (Exception e) {
            ctx.logger().error(DB_MARKER, "Error during admin account deletion: {}", nickname, e);
            CommandHelper.sendError(sender, ctx.messages(), ERROR_DATABASE_QUERY);
        }
    }

    private boolean handleDatabaseError(DatabaseManager.DbResult<?> result, AuthSender sender,
                                        String nickname, String operation) {
        return DatabaseErrorHandler.handleError(result, nickname, operation,
                ctx.logger(), ctx.messages(), sender::sendMessage);
    }

    private UUID parsePlayerUuid(RegisteredPlayer registeredPlayer, String nickname,
                                 AuthSender sender) {
        try {
            return UUID.fromString(registeredPlayer.getUuid());
        } catch (IllegalArgumentException e) {
            ctx.logger().warn("Invalid UUID for player {}: {}", nickname, registeredPlayer.getUuid());
            sender.sendMessage(ctx.messages().component("admin.uuid_invalid", NamedTextColor.RED));
            return null;
        }
    }
}
