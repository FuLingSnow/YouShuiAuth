package io.github.fulingsnow.youshuiauth.listener;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.cache.AuthCache.PremiumCacheEntry;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import org.slf4j.Logger;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Handles post-login routing and conflict resolution logic.
 * Extracted from AuthListener to reduce complexity and improve testability.
 */
public class PostLoginHandler {

    private final AuthCache authCache;
    private final DatabaseManager databaseManager;
    private final Messages messages;
    private final Logger logger;

    /**
     * Creates a new PostLoginHandler.
     *
     * @param authCache         Cache for authorization and sessions
     * @param databaseManager   Manager for database operations
     * @param messages          i18n message system
     * @param logger            Logger instance
     */
    PostLoginHandler(AuthCache authCache,
                           DatabaseManager databaseManager,
                           Messages messages,
                           Logger logger) {
        this.authCache = requireNonNull(authCache, "authCache");
        this.databaseManager = requireNonNull(databaseManager, "databaseManager");
        this.messages = requireNonNull(messages, "messages");
        this.logger = requireNonNull(logger, "logger");
    }

    /**
     * Handles premium player post-login (authorization and session start).
     *
     * @param player   The premium player
     * @param playerIp Player's IP address
     */
    public void handlePremiumPlayer(Player player, String playerIp) {
        if (logger.isInfoEnabled()) {
            logger.info("正版玩家 {} 校验通过", player.getUsername());
        }

        PremiumAuthorizer.authorize(player, playerIp, authCache);

        if (logger.isDebugEnabled()) {
            logger.debug("正版玩家 {} 已授权——ServerPreConnectEvent 将按配置路由",
                    player.getUsername());
        }
    }

    /**
     * Handles offline player post-login (authorization check or auth server transfer).
     *
     * <p>An authorization cache entry never renews an expired session. Session TTL is a
     * security boundary: once it expires the player must authenticate again. LoginCommand
     * checks both authorization and active-session state, so this no longer causes the old
     * "already logged in" deadlock.
     *
     * @param player   The offline player
     * @param playerIp Player's IP address
     */
    public void handleOfflinePlayer(Player player, String playerIp) {
        java.util.UUID uuid = player.getUniqueId();
        String nickname = player.getUsername();
        if (!authCache.isPlayerAuthorized(uuid, playerIp)) {
            if (logger.isDebugEnabled()) {
                logger.debug("玩家 {} 未授权——ServerPreConnectEvent 将路由到认证服",
                        player.getUsername());
            }

            return;
        }
        if (!authCache.hasActiveSession(uuid, nickname, playerIp) && logger.isDebugEnabled()) {
            logger.debug("已授权玩家 {} 的会话已过期——需要密码认证",
                    nickname);
        }
    }

    /**
     * Shows conflict resolution message to premium players in conflict mode.
     *
     * @param player The premium player experiencing conflict
     */
    public void showConflictResolutionMessage(Player player) {
        Component message = Component.text()
                .append(messages.component("player.conflict.header", NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(messages.component("player.conflict.description", NamedTextColor.RED))
                .append(Component.newline())
                .append(messages.component("player.conflict.options", NamedTextColor.WHITE))
                .append(Component.newline())
                .append(messages.component("player.conflict.option1", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(messages.component("player.conflict.option2", NamedTextColor.GREEN))
                .append(Component.newline())
                .append(messages.component("player.conflict.resolution", NamedTextColor.AQUA))
                .build();

        player.sendMessage(message);
        logger.info("[CONFLICT MESSAGE] Sent conflict resolution message to premium player: {}", player.getUsername());
    }

    /**
     * Checks if player should see conflict resolution message.
     *
     * @param player The player to check
     * @return true if player is in conflict mode and is premium
     */
    public boolean shouldShowConflictMessage(Player player) {
        try {
            var dbResult = databaseManager.findPlayerWithRuntimeDetection(player.getUsername()).join();
            if (dbResult == null || dbResult.isDatabaseError()) {
                logger.debug("无法检查 {} 的冲突提示——数据库错误", player.getUsername());
                return false;
            }

            RegisteredPlayer registeredPlayer = dbResult.getValue();
            if (registeredPlayer == null || !registeredPlayer.getConflictMode()) {
                return false;
            }

            return Optional.ofNullable(authCache.getPremiumStatus(player.getUsername()))
                    .map(PremiumCacheEntry::isPremium)
                    .orElse(false);
        } catch (java.util.concurrent.CompletionException e) {
            logger.error("检查 {} 的冲突提示时发生数据库错误", player.getUsername(), e);
            return false;
        }
    }
}
