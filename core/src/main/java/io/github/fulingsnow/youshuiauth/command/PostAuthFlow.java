package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.util.UuidUtils;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.UUID;

/**
 * Shared post-authentication flow used by both LoginCommand and RegisterCommand.
 * Handles: premium check → cache update → session start → security reset → transfer.
 * <p>
 * Extracted to eliminate duplication between login and registration success paths.
 */
public final class PostAuthFlow {

    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");

    private PostAuthFlow() {}

    /**
     * Executes the post-authentication flow after successful login or registration.
     *
     * @param ctx           shared command context
     * @param authContext   validated authentication context
     * @param player        the registered player entity to cache
     * @param operationName human-readable name for logging (e.g. "login", "registration")
     * @return true if the flow completed successfully, false on database error
     */
    public static boolean execute(CommandContext ctx, AuthenticationContext authContext,
                           RegisteredPlayer player, String operationName) {
        var connectionOperation = authContext.connectionOperation();
        if (!ctx.isConnectionCurrent(connectionOperation)) {
            return false;
        }

        try {
            player.updateLoginData(PlayerAddressUtils.getPlayerIp(authContext.player()));
            ctx.databaseManager().savePlayer(player).join();
        } catch (RuntimeException ignored) {

        }

        DatabaseManager.DbResult<Boolean> premiumResult =
                ctx.checkPremiumStatus(authContext.username(), authContext.player(),
                        "Premium status check during " + operationName, connectionOperation);
        if (premiumResult.isDatabaseError()) {
            return false;
        }
        if (!ctx.isConnectionCurrent(connectionOperation)) {
            return false;
        }

        boolean isPremium = Boolean.TRUE.equals(premiumResult.getValue());

        CachedAuthUser cachedUser = CachedAuthUser.fromRegisteredPlayer(
                player, isPremium, storedPremiumUuid(player, isPremium));

        io.github.fulingsnow.youshuiauth.platform.AuthPlayer p = authContext.player();
        boolean authorized = ctx.runIfConnectionCurrent(connectionOperation, () -> {
            ctx.authCache().authorize(p.uniqueId(), cachedUser, authContext.username(),
                    PlayerAddressUtils.getPlayerIp(p));
            ctx.resetSecurityCounters(authContext.playerAddress(), authContext.username());

            ctx.timeouts().cancel(p.uniqueId());
        });
        if (!authorized) {
            return false;
        }
        ctx.credentialEpochs().record(p.uniqueId(),
                ctx.databaseManager().findCredentialEpoch(p.uniqueId()).join().orElse(0L));

        io.github.fulingsnow.youshuiauth.platform.QqOps qqAuthService = ctx.qqService();
        if (qqAuthService != null) {
            qqAuthService.rememberDevice(p);
        }

        if (ctx.logger().isDebugEnabled()) {
            ctx.logger().debug(AUTH_MARKER, "Player {} {} successfully from IP {}",
                    authContext.username(), operationName, PlayerAddressUtils.getPlayerIp(p));
        }

        if (!ctx.isConnectionCurrent(connectionOperation)) {
            return false;
        }
        boolean transferred = ctx.transfers().transferToBackend(p);
        if (!transferred) {

            ctx.runIfConnectionCurrent(connectionOperation, () -> {
                ctx.authCache().removeAuthorizedPlayer(p.uniqueId());
                ctx.authCache().endSession(p.uniqueId());
            });
            if (ctx.logger().isWarnEnabled()) {
                ctx.logger().warn(AUTH_MARKER,
                        "{} succeeded for {} but backend transfer failed - rolled back cache/session",
                        operationName, authContext.username());
            }
            return false;
        }

        if (!ctx.isConnectionCurrent(connectionOperation)) {
            return false;
        }

        ctx.conflictModeService().clearIfPresent(player, operationName)
                .exceptionally(throwable -> {
                    ctx.logger().error(AUTH_MARKER,
                            "Unexpected conflict-state cleanup failure for {} after {}",
                            authContext.username(), operationName, throwable);
                    return false;
        });
        return true;
    }

    private static UUID storedPremiumUuid(RegisteredPlayer player, boolean isPremium) {
        if (!isPremium) {
            return null;
        }
        return UuidUtils.parseUuidSafely(player.getPremiumUuid());
    }
}
