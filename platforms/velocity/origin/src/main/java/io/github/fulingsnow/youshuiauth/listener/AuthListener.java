package io.github.fulingsnow.youshuiauth.listener;

import io.github.fulingsnow.youshuiauth.util.VelocityPlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import static com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.YouShuiAuth;
import io.github.fulingsnow.youshuiauth.auth.ConflictModeService;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.command.QqAuthService;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.connection.ConnectionManager;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager.DbResult;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.lifecycle.VelocityConnectionLifecycleRegistry;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.util.DeviceFingerprints;
import io.github.fulingsnow.youshuiauth.runtime.AuthRuntime;
import io.github.fulingsnow.youshuiauth.util.FloodgateDetector;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import static io.github.fulingsnow.youshuiauth.util.PlayerMessages.playerMessage;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Listener eventów autoryzacji YouShuiAuth.
 * Obsługuje połączenia graczy i kieruje ich na odpowiednie serwery.
 *
 * <p><b>Flow eventów:</b>
 * <ol>
 *   <li>PreLoginEvent → sprawdź premium i force online mode</li>
 *   <li>LoginEvent → sprawdź brute force</li>
 *   <li>PostLoginEvent → ustanów autoryzację premium lub stan offline</li>
 *   <li>ServerPreConnectEvent → zastosuj routing auth/backend i blokuj nieautoryzowany backend</li>
 *   <li>ServerConnectedEvent → loguj transfery</li>
 * </ol>
 *
 * <p><b>Initialization Safety (v2.0.0):</b>
 * Handlers (PreLoginHandler, PostLoginHandler) are now initialized before AuthListener
 * construction and passed via constructor, preventing NullPointerException during event
 * processing. Defense-in-depth null checks are included in event handlers as additional safety.
 *
 * <p><b>Thread Safety:</b> All event handlers are thread-safe and can process concurrent events.
 *
 * @since 1.0.0
 * @see PreLoginHandler
 * @see PostLoginHandler
 */
public class AuthListener {

    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");
    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");
    private static final Marker PREMIUM_MARKER = MarkerFactory.getMarker("PREMIUM");
    private static final String MSG_CONNECTION_ERROR_DATABASE = "connection.error.database";

    private final Cache<String, PendingLoginAttempt> pendingLogins = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    /** Fail-secure hand-off from GameProfileRequestEvent to LoginEvent. */
    private final Cache<String, Boolean> rejectedProfileBindings = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    private final YouShuiAuth plugin;
    private final AuthCache authCache;
    private final Settings settings;
    private final Logger logger;
    private final Messages messages;
    private final DatabaseManager databaseManager;

    private final PreLoginHandler preLoginHandler;
    private final PostLoginHandler postLoginHandler;
    private final ConnectionManager connectionManager;
    private final VelocityConnectionLifecycleRegistry connectionLifecycleRegistry;
    private final UuidVerificationHandler uuidVerificationHandler;

    /**
     * Tworzy nowy AuthListener.
     *
     * @param plugin            YouShuiAuth plugin instance
     * @param authCache         Cache autoryzacji
     * @param settings          Ustawienia pluginu
     * @param preLoginHandler   Handler for pre-login logic
     * @param postLoginHandler  Handler for post-login logic
     * @param connectionManager Manager połączeń i transferów
     * @param databaseManager   Manager bazy danych
     * @param messages          System wiadomości i18n
     */
    @Inject
    public AuthListener(YouShuiAuth plugin,
            AuthCache authCache,
            Settings settings,
            PreLoginHandler preLoginHandler,
            PostLoginHandler postLoginHandler,
            ConnectionManager connectionManager,
            DatabaseManager databaseManager,
            Messages messages) {
        this.plugin = plugin;
        this.authCache = authCache;
        this.settings = settings;
        this.logger = plugin.getLogger();
        this.databaseManager = databaseManager;
        this.messages = messages;
        this.connectionManager = java.util.Objects.requireNonNull(connectionManager,
            "ConnectionManager cannot be null - initialization failed");
        this.connectionLifecycleRegistry = plugin.getConnectionLifecycleRegistry();
        this.preLoginHandler = java.util.Objects.requireNonNull(preLoginHandler,
            "PreLoginHandler cannot be null - initialization failed");
        this.postLoginHandler = java.util.Objects.requireNonNull(postLoginHandler,
            "PostLoginHandler cannot be null - initialization failed");
        this.uuidVerificationHandler = new UuidVerificationHandler(databaseManager, authCache, logger,
                plugin::getAuditLogService,
                new ConflictModeService(databaseManager, settings.getConflictModeTtlHours()));

        if (logger.isDebugEnabled()) {
            logger.debug(messages.get("connection.listener.registered"));
        }
    }

    /**
     * Resolves the block reason for unauthorized connections.
     *
     * @param isAuthorized     Whether player is authorized
     * @param hasActiveSession Whether player has active session
     * @return Human-readable reason string (English for logs)
     */
    private static String resolveBlockReason(boolean isAuthorized, boolean hasActiveSession) {
        if (!isAuthorized) {
            return "unauthorized";
        }
        if (!hasActiveSession) {
            return "no active session";
        }
        return "UUID mismatch";
    }

    private static boolean isFloodgatePlayer(
            Player player,
            Settings.FloodgateSettings floodgateSettings) {
        return floodgateSettings.isEnabled()
                && FloodgateDetector.isBedrockPlayer(player.getUniqueId());
    }

    private static boolean shouldBypassAuthServer(
            Player player,
            Settings.FloodgateSettings floodgateSettings) {
        return floodgateSettings.isBypassAuthServer()
                && isFloodgatePlayer(player, floodgateSettings);
    }

    private static boolean isFloodgatePreLogin(
            String username,
            Settings.FloodgateSettings floodgateSettings) {
        return floodgateSettings.isEnabled()
                && FloodgateDetector.isBedrockUsername(username);
    }

    /**
     * Re-authorizes a premium player whose cache entry expired.
     * Premium players are cryptographically verified by Velocity (Mojang handshake),
     * so {@code player.isOnlineMode()} is trustworthy and we can safely re-create the cache entry.
     */
    private void refreshPremiumAuthorization(Player player, String playerIp) {
        io.github.fulingsnow.youshuiauth.listener.PremiumAuthorizer.authorize(player, playerIp, authCache);
        if (logger.isDebugEnabled()) {
            logger.debug(AUTH_MARKER, "Refreshed premium authorization for {} (expired cache re-created)",
                    player.getUsername());
        }
    }

    private boolean refreshPremiumAuthorizationIfCurrent(
            Player player, String playerIp, ConnectionRef operation) {
        return connectionLifecycleRegistry.runIfCurrent(operation,
                () -> refreshPremiumAuthorization(player, playerIp));
    }

    private boolean prepareAuthServerConnectionIfCurrent(
            ServerPreConnectEvent event, Player player, RegisteredServer targetServer,
            ConnectionRef operation) {
        AtomicBoolean prepared = new AtomicBoolean();
        boolean executed = connectionLifecycleRegistry.runIfCurrent(operation, () -> {
            if (connectionManager.prepareAuthServerConnection(player)) {
                prepared.set(true);
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(targetServer));
            }
        });
        if (!executed || !prepared.get()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
        return executed && prepared.get();
    }

    private boolean allowServerIfCurrent(
            ServerPreConnectEvent event, RegisteredServer targetServer, ConnectionRef operation) {
        boolean allowed = connectionLifecycleRegistry.runIfCurrent(operation,
                () -> event.setResult(ServerPreConnectEvent.ServerResult.allowed(targetServer)));
        if (!allowed) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
        return allowed;
    }

    /**
     * ✅ KLUCZOWY EVENT - PreLoginEvent
     * Tutaj sprawdzamy premium PRZED weryfikacją UUID!
     * Jeśli premium → forceOnlineMode() = Velocity zweryfikuje
     * <p>
     * KRYTYCZNE: Używamy maksymalny priorytet dla bezpieczeństwa.
     * <p>
     * ASYNC: Zwraca EventTask aby NIE blokować wątku Netty IO.
     * Validation checks (fast, sync) wykonują się natychmiast.
     * Premium resolution + DB lookup wykonują się asynchronicznie.
     * Velocity wstrzymuje event processing do zakończenia EventTask.
     */
    @Subscribe(priority = Short.MAX_VALUE)
    public EventTask onPreLogin(PreLoginEvent event) {
        String username = event.getUsername();
        Settings.OperationSettings operationSettings = settings.captureOperationSettings();
        Settings.PremiumSettings premiumSettings = operationSettings.premium();
        Settings.FloodgateSettings floodgateSettings = operationSettings.floodgate();
        String pendingLoginKey = createPendingLoginKey(event, username);
        PendingLoginAttempt pendingLoginAttempt = acquirePendingLogin(pendingLoginKey, event.getConnection());
        if (logger.isDebugEnabled()) {
            logger.debug("PreLogin：{}", username);
        }

        if (pendingLoginAttempt == null) {
            logger.warn(SECURITY_MARKER, "[DUPLICATE PRELOGIN] {} from {} - already connecting, denying",
                    username, pendingLoginKey);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    messages.component("connection.already_connecting", NamedTextColor.RED)));
            return null;
        }

        if (!validatePreLoginConditions(event, username)) {
            releasePendingLogin(pendingLoginKey, pendingLoginAttempt);
            return null;
        }

        if (floodgateSettings.isEnabled()) {
            return EventTask.resumeWhenComplete(handleFloodgateAwarePreLoginAsync(
                    event, username, premiumSettings, floodgateSettings)
                    .whenComplete((result, throwable) ->
                            releasePendingLogin(pendingLoginKey, pendingLoginAttempt)));
        }

        if (!premiumSettings.isCheckEnabled()) {
            logger.debug("配置中已禁用正版校验——{} 强制离线模式", username);
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            releasePendingLogin(pendingLoginKey, pendingLoginAttempt);
            return null;
        }

        return EventTask.resumeWhenComplete(handlePremiumDetectionAsync(event, username, premiumSettings)
                .whenComplete((result, throwable) -> releasePendingLogin(pendingLoginKey, pendingLoginAttempt)));
    }

    private CompletableFuture<Void> handleFloodgateAwarePreLoginAsync(
            PreLoginEvent event,
            String username,
            Settings.PremiumSettings premiumSettings,
            Settings.FloodgateSettings floodgateSettings) {
        return isFloodgatePreLoginAsync(username, floodgateSettings)
                .thenCompose(floodgatePlayer -> {

                    if (Boolean.TRUE.equals(floodgatePlayer)) {
                        logger.info(AUTH_MARKER,
                                "Floodgate player {} detected during pre-login - skipping Mojang resolution",
                                username);
                        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                        return CompletableFuture.completedFuture(null);
                    }
                    if (!premiumSettings.isCheckEnabled()) {
                        logger.debug("配置中已禁用正版校验——{} 强制离线模式", username);
                        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                        return CompletableFuture.completedFuture(null);
                    }
                    return handlePremiumDetectionAsync(event, username, premiumSettings);
                });
    }

    private CompletableFuture<Boolean> isFloodgatePreLoginAsync(
            String username,
            Settings.FloodgateSettings floodgateSettings) {
        if (!floodgateSettings.isEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(
                () -> isFloodgatePreLogin(username, floodgateSettings),
                VirtualThreadExecutorProvider.getVirtualExecutor());
    }

    private PendingLoginAttempt acquirePendingLogin(String key, InboundConnection connection) {
        PendingLoginAttempt candidate = new PendingLoginAttempt(connection);
        AtomicBoolean acquired = new AtomicBoolean();
        pendingLogins.asMap().compute(key, (ignored, existing) -> {
            if (existing == null || !existing.connection().isActive()) {
                acquired.set(true);
                return candidate;
            }
            return existing;
        });
        return acquired.get() ? candidate : null;
    }

    private void releasePendingLogin(String key, PendingLoginAttempt attempt) {
        pendingLogins.asMap().remove(key, attempt);
    }

    private String createPendingLoginKey(PreLoginEvent event, String username) {
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        InetAddress address = VelocityPlayerAddressUtils.getAddressFromPreLogin(event);
        if (address != null) {
            return normalizedUsername + '|' + address.getHostAddress();
        }

        SocketAddress remoteAddress = event.getConnection() != null ? event.getConnection().getRemoteAddress() : null;
        if (remoteAddress != null) {
            return normalizedUsername + '|' + remoteAddress;
        }

        return normalizedUsername + "|connection:" + System.identityHashCode(event.getConnection());
    }

    private boolean validatePreLoginConditions(PreLoginEvent event, String username) {
        if (!validatePluginInitialized(event, username)) {
            return false;
        }
        if (!validateHandlerInitialized(event, username)) {
            return false;
        }
        if (!validateUsername(event, username)) {
            return false;
        }
        return !checkBruteForceBlocked(event);
    }

    private boolean validatePluginInitialized(PreLoginEvent event, String username) {
        if (!plugin.isInitialized()) {
            logger.warn("启动拦截：玩家 {} 在 YouShuiAuth 完成初始化前尝试连接——PreLogin 已拦截", username);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(localizedOrFallback(
                    "system.starting", "YouShuiAuth is starting. Please wait.", NamedTextColor.RED)));
            return false;
        }
        return true;
    }

    private boolean validateHandlerInitialized(PreLoginEvent event, String username) {
        if (preLoginHandler == null) {
            logger.error("严重错误：处理玩家 {} 的事件时 PreLoginHandler 为 null", username);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(localizedOrFallback(
                    "system.init_error", "System initialization error.", NamedTextColor.RED)));
            return false;
        }
        return true;
    }

    private boolean validateUsername(PreLoginEvent event, String username) {
        if (!preLoginHandler.isValidUsername(username)) {
            logger.warn(SECURITY_MARKER, "[USERNAME VALIDATION FAILED] {} - invalid format", username);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    messages.component("validation.username.invalid", NamedTextColor.RED)));
            return false;
        }
        return true;
    }

    private boolean checkBruteForceBlocked(PreLoginEvent event) {
        InetAddress playerAddress = VelocityPlayerAddressUtils.getAddressFromPreLogin(event);
        if (preLoginHandler.isBruteForceBlocked(playerAddress, event.getUsername())) {
            if (logger.isWarnEnabled()) {
                String playerIp = playerAddress != null ? playerAddress.getHostAddress() : "unknown";
                logger.warn(SECURITY_MARKER, "[BRUTE FORCE BLOCK] IP {} blocked", playerIp);
            }
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    messages.component("security.brute_force.blocked", NamedTextColor.RED)));
            return true;
        }
        return false;
    }

    /**
     * Async premium detection — chains premium resolution and DB lookup without blocking Netty IO.
     * Uses CompletableFuture composition: resolve premium → DB lookup → set event result.
     *
     * @param event    PreLoginEvent to set result on
     * @param username Player username
     * @return CompletableFuture that completes when event result is set
     */
    private CompletableFuture<Void> handlePremiumDetectionAsync(
            PreLoginEvent event,
            String username,
            Settings.PremiumSettings premiumSettings) {
        InetAddress sourceAddress = VelocityPlayerAddressUtils.getAddressFromPreLogin(event);
        return preLoginHandler.resolvePremiumStatusAsync(username, sourceAddress)
                .thenCompose(result -> handlePremiumResolutionResult(
                        event, username, result, premiumSettings))
                .exceptionally(throwable -> {
                    logger.error(SECURITY_MARKER,
                            "[ASYNC] Error during premium detection for {} - denying login for safety",
                            username, throwable);
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                            messages.component(MSG_CONNECTION_ERROR_DATABASE, NamedTextColor.RED)));
                    return null;
                });
    }

    private CompletableFuture<Void> handlePremiumResolutionResult(
            PreLoginEvent event,
            String username,
            PreLoginHandler.PremiumResolutionResult result,
            Settings.PremiumSettings premiumSettings) {
        if (result == null) {
            denyLoginOnApiFailure(event, username);
            return CompletableFuture.completedFuture(null);
        }

        return databaseManager.findPlayerByNicknameOrPremiumUuidReadOnly(username, result.premiumUuid())
                .thenAccept(dbResult -> applyPremiumDetectionResult(
                        event, username, result, dbResult, premiumSettings));
    }

    private void denyLoginOnApiFailure(PreLoginEvent event, String username) {
        logger.error("[SECURITY] Login DENIED for {} - cannot verify premium status (all API resolvers failed)",
                username);
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                messages.component("security.api_failure.denied", NamedTextColor.RED)));
    }

    private void applyPremiumDetectionResult(
            PreLoginEvent event,
            String username,
            PreLoginHandler.PremiumResolutionResult result,
            DbResult<RegisteredPlayer> dbResult,
            Settings.PremiumSettings premiumSettings) {
        if (dbResult == null || dbResult.isDatabaseError()) {
            handlePremiumLookupDatabaseError(event, username, result.premium(), dbResult);
            return;
        }

        if (hasNicknameConflict(event, result, dbResult.getValue())) {
            return;
        }

        boolean playerExistsInDb = dbResult.getValue() != null;
        setPremiumLoginMode(event, username, result.premium(), playerExistsInDb, premiumSettings);
    }

    private void handlePremiumLookupDatabaseError(
            PreLoginEvent event, String username, boolean isPremium, DbResult<RegisteredPlayer> dbResult) {
        logger.error("[DATABASE] Premium detection DB lookup failed for {}: {}",
                username, dbResult != null ? dbResult.getErrorMessage() : "null result");
        if (isPremium) {
            logger.warn("[SECURITY] Denying premium player {} - DB error would corrupt UUID in offline mode",
                    username);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    messages.component(MSG_CONNECTION_ERROR_DATABASE, NamedTextColor.RED)));
        } else {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    private boolean hasNicknameConflict(
            PreLoginEvent event,
            PreLoginHandler.PremiumResolutionResult result,
            RegisteredPlayer existingPlayer) {
        if (existingPlayer == null) {
            return false;
        }

        boolean existingIsPremium = databaseManager.isPlayerPremiumRuntime(existingPlayer);
        if (!preLoginHandler.isNicknameConflict(existingPlayer, result.premium(), existingIsPremium,
                result.premiumUuid())) {
            return false;
        }

        preLoginHandler.handleNicknameConflict(event, existingPlayer, result.premium(), result.premiumUuid());
        return true;
    }

    private void setPremiumLoginMode(
            PreLoginEvent event,
            String username,
            boolean premium,
            boolean playerExistsInDb,
            Settings.PremiumSettings premiumSettings) {
        if (!premium) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            return;
        }

        if (!playerExistsInDb && premiumSettings.isAllowCrackedOnPremiumNicks()) {
            logger.warn(SECURITY_MARKER,
                    "[PREMIUM BYPASS] Forcing offline mode for premium nick {} (no DB record, allow-cracked-on-premium-nicks=true)",
                    username);
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            return;
        }

        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
    }

    /**
     * Applies the UUID compatibility contract after Mojang has verified the profile.
     * Resolver results from PreLogin are deliberately not trusted for persistence or
     * nickname migration; this event carries the session-server authenticated identity.
     */
    @Subscribe(priority = Short.MAX_VALUE)
    public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
        Settings.OperationSettings operationSettings = settings.captureOperationSettings();
        Settings.PremiumSettings premiumSettings = operationSettings.premium();
        Settings.FloodgateSettings floodgateSettings = operationSettings.floodgate();
        if (!premiumSettings.isCheckEnabled() || !event.isOnlineMode()) {
            return null;
        }

        UUID verifiedPremiumUuid = event.getOriginalProfile().getId();
        if (verifiedPremiumUuid == null || isBedrockProfile(verifiedPremiumUuid, floodgateSettings)) {
            return null;
        }

        String bindingKey = profileBindingKey(event.getUsername(), event.getConnection());
        CompletableFuture<Void> reconciliation = isFloodgatePreLoginAsync(
                event.getUsername(), floodgateSettings)
                .thenCompose(floodgatePlayer -> Boolean.TRUE.equals(floodgatePlayer)
                        ? CompletableFuture.completedFuture(null)
                        : reconcileVerifiedProfile(event, verifiedPremiumUuid, bindingKey));
        return EventTask.resumeWhenComplete(reconciliation);
    }

    private CompletableFuture<Void> reconcileVerifiedProfile(
            GameProfileRequestEvent event, UUID verifiedPremiumUuid, String bindingKey) {
        return databaseManager.reconcileVerifiedPremiumProfile(event.getUsername(), verifiedPremiumUuid)
                .handle((dbResult, throwable) -> {
                    if (throwable != null || dbResult == null || dbResult.isDatabaseError()
                            || dbResult.getValue() == null) {
                        rejectedProfileBindings.put(bindingKey, Boolean.TRUE);
                        logger.error(PREMIUM_MARKER,
                                "Unable to bind Mojang-verified profile for {} - login will be denied",
                                event.getUsername(), throwable);
                        return null;
                    }

                    DatabaseManager.PremiumProfileBinding binding = dbResult.getValue();
                    authCache.addPremiumPlayer(event.getUsername(), binding.verifiedPremiumUuid());
                    event.setGameProfile(event.getOriginalProfile().withId(binding.backendUuid()));
                    rejectedProfileBindings.invalidate(bindingKey);
                    return null;
                });
    }

    private static boolean isBedrockProfile(
            UUID profileUuid,
            Settings.FloodgateSettings floodgateSettings) {
        return floodgateSettings.isEnabled()
                && FloodgateDetector.isBedrockPlayer(profileUuid);
    }

    private String profileBindingKey(String username, InboundConnection connection) {
        SocketAddress remoteAddress = connection != null ? connection.getRemoteAddress() : null;
        return username.toLowerCase(Locale.ROOT) + '|' + remoteAddress;
    }

    /**
     * Obsługuje event logowania gracza.
     * Sprawdza brute force i premium status SYNCHRONICZNIE.
     * <p>
     * KRYTYCZNE: Używamy async = false + maksymalny priorytet dla bezpieczeństwa
     * Zapobiega race conditions w procesie autoryzacji
     */
    @Subscribe(priority = Short.MAX_VALUE)
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getUsername();
        UUID playerUuid = player.getUniqueId();
        String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);

        if (!plugin.isInitialized()) {
            logger.warn(
                "STARTUP BLOCK: Player {} tried to login before YouShuiAuth fully initialized - login block",
                playerName);

            event.setResult(ComponentResult.denied(localizedOrFallback(
                    "system.starting", "YouShuiAuth is starting. Please wait.", NamedTextColor.RED)));
            return;
        }

        String profileBindingKey = profileBindingKey(playerName, player);
        if (rejectedProfileBindings.getIfPresent(profileBindingKey) != null) {
            rejectedProfileBindings.invalidate(profileBindingKey);
            logger.warn(SECURITY_MARKER,
                    "Denying {} because the Mojang profile could not be safely bound to AUTH.UUID",
                    playerName);
            event.setResult(ComponentResult.denied(
                    messages.component(MSG_CONNECTION_ERROR_DATABASE, NamedTextColor.RED)));
            return;
        }

        logger.debug("玩家 {} 的 LoginEvent（UUID：{}），来源 IP {}",
            playerName, playerUuid, playerIp);

        InetAddress playerAddress = VelocityPlayerAddressUtils.getPlayerAddress(player);
        if (preLoginHandler.isBruteForceBlocked(playerAddress, playerName)) {
            String playerAddressText = playerAddress != null ? playerAddress.getHostAddress() : "unknown";
            logger.warn(SECURITY_MARKER,
                    "Blocked connection for player {} from {} - too many failed login attempts",
                    playerName, playerAddressText);

            event.setResult(ComponentResult.denied(
                messages.component("security.brute_force.blocked", NamedTextColor.RED)));
            return;
        }

        event.setResult(ComponentResult.allowed());
    }

    /**
     * Invalidates every connection-bound authentication state on disconnect. Offline UUIDs and
     * public IP addresses are not unique client credentials (multiple users can share one NAT),
     * so retaining authorization would let a new connection inherit a previous player's login.
     */
    @Subscribe(priority = 0)
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        rejectedProfileBindings.invalidate(profileBindingKey(player.getUsername(), player));

        boolean allowUnownedCleanup = allowsUnownedDisconnectCleanup(event, player);
        if (!connectionLifecycleRegistry.retire(
                player, allowUnownedCleanup,
                () -> {
                    plugin.clearConnectionBoundState(player, authCache, connectionManager);
                    QqAuthService qqAuthService = plugin.getQqAuthService();
                    if (qqAuthService != null) {
                        qqAuthService.cancelDeviceApproval(player);
                    }
                })) {
            logger.debug(SECURITY_MARKER,
                    "Ignoring {} disconnect cleanup for non-owning connection {} ({})",
                    event.getLoginStatus(), player.getUsername(), playerUuid);
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug(SECURITY_MARKER,
                    "Player {} disconnected - authorization and pending authentication state invalidated",
                    player.getUsername());
        }
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private boolean allowsUnownedDisconnectCleanup(DisconnectEvent event, Player player) {
        DisconnectEvent.LoginStatus status = event.getLoginStatus();
        if (status != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
                && status != DisconnectEvent.LoginStatus.PRE_SERVER_JOIN) {
            return false;
        }

        return plugin.getServer().getPlayer(player.getUniqueId())
                .map(active -> active == player)
                .orElse(true);
    }

    /**
     * Obsługuje event po zalogowaniu gracza.
     * Kieruje gracza na odpowiedni serwer (auth server lub backend).
     */
    @Subscribe(priority = 0)
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getUsername();
        ConnectionRef operation;
        try {
            operation = connectionLifecycleRegistry.activate(player,
                    previousConnection -> publishConnectionGeneration(
                            player, playerId, playerName,
                            io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(
                                    previousConnection)));
        } catch (RuntimeException publicationFailure) {
            handlePostLoginPublicationFailure(player, playerName, publicationFailure);
            return;
        }
        if (operation == null) {
            logger.debug(SECURITY_MARKER,
                    "Ignoring late PostLoginEvent for {} after lifecycle shutdown",
                    playerName);
            return;
        }
        String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);

        logger.debug("玩家 {} 的 PostLoginEvent，IP {}",
                player.getUsername(), playerIp);

        if (postLoginHandler == null) {
            logger.error("严重错误：处理玩家 {} 的事件时 PostLoginHandler 为 null",
                player.getUsername());
            connectionLifecycleRegistry.runIfCurrent(operation, () -> player.disconnect(localizedOrFallback(
                    "system.init_error", "System initialization error.", NamedTextColor.RED)));
            return;
        }

        try {

            checkConflictMessageAsync(player, operation);

            if (player.isOnlineMode()) {
                connectionLifecycleRegistry.runIfCurrent(operation,
                        () -> postLoginHandler.handlePremiumPlayer(player, playerIp));
                return;
            }

            connectionLifecycleRegistry.runIfCurrent(operation,
                    () -> postLoginHandler.handleOfflinePlayer(player, playerIp));

        } catch (RuntimeException e) {
            logger.error("处理玩家 PostLoginEvent 出错：{}", event.getPlayer().getUsername(), e);

            connectionLifecycleRegistry.runIfCurrent(operation,
                    () -> event.getPlayer().disconnect(messages.component(
                            "connection.error.generic", NamedTextColor.RED)));
        }
    }

    private void publishConnectionGeneration(
            Player player, UUID playerId, String playerName, Player previousConnection) {
        try {
            connectionManager.beginTransferSession(player);
            if (previousConnection != null) {

                plugin.clearConnectionBoundState(
                        previousConnection, playerId, authCache, connectionManager);
                logger.debug(SECURITY_MARKER,
                        "Replaced stale connection owner for {} - previous auth state invalidated",
                        playerName);
            }
        } catch (RuntimeException publicationFailure) {

            try {
                plugin.clearConnectionBoundState(player, playerId, authCache, connectionManager);
            } catch (RuntimeException cleanupFailure) {
                publicationFailure.addSuppressed(cleanupFailure);
            }
            throw publicationFailure;
        }
    }

    private void handlePostLoginPublicationFailure(
            Player player, String playerName, RuntimeException publicationFailure) {
        logger.error(SECURITY_MARKER,
                "Failed to publish connection lifecycle for {} - disconnecting fail-secure",
                playerName, publicationFailure);
        try {
            player.disconnect(messages.component("connection.error.generic", NamedTextColor.RED));
        } catch (RuntimeException disconnectFailure) {
            logger.error(SECURITY_MARKER,
                    "Failed to disconnect {} after PostLogin publication failure",
                    playerName, disconnectFailure);
        }
    }

    CompletableFuture<Void> checkConflictMessageAsync(Player player, ConnectionRef operation) {
        try {
            return CompletableFuture.runAsync(() -> {
                if (!connectionLifecycleRegistry.isCurrent(operation)) {
                    return;
                }
                try {
                    if (postLoginHandler.shouldShowConflictMessage(player)) {
                        connectionLifecycleRegistry.runIfCurrent(operation,
                                () -> postLoginHandler.showConflictResolutionMessage(player));
                    }
                } catch (java.util.concurrent.CompletionException exception) {
                    logger.error("检查 {} 的冲突提示出错", player.getUsername(), exception);
                }
            }, VirtualThreadExecutorProvider.getVirtualExecutor());
        } catch (RejectedExecutionException exception) {
            logger.debug("执行器关闭期间跳过 {} 的冲突查询", player.getUsername());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Obsługuje event przed połączeniem z serwerem.
     * Blokuje nieautoryzowane połączenia z serwerami backend.
     * <p>
     * KRYTYCZNE: Używamy maksymalny priorytet dla bezpieczeństwa.
     * Zapobiega obejściu autoryzacji przez race conditions.
     * <p>
     * FLOW dla nowych graczy (pierwszego połączenia):
     * - Velocity próbuje połączyć z pierwszym serwerem z listy try (np. 2b2t)
     * - Domyślnie przechwytujemy i przekierowujemy na auth server
     * - Mojang-verified premium może zachować target, gdy operator włączy jawny opt-in
     * - Po połączeniu z auth server, onServerConnected uruchomi auto-transfer
     * <p>
     * ASYNC: Returns {@link EventTask} (or null for synchronous fast-paths) to avoid
     * blocking Netty IO threads. Backend connection verification performs a database
     * UUID lookup and is therefore executed asynchronously via
     * {@link EventTask#resumeWhenComplete(java.util.concurrent.CompletableFuture)}.
     * Velocity suspends event processing until the returned future completes.
     */
    @Subscribe(priority = Short.MAX_VALUE)
    public EventTask onServerPreConnect(ServerPreConnectEvent event) {
        try {
            Player player = event.getPlayer();
            ConnectionRef operation = connectionLifecycleRegistry.capture(player);
            if (operation == null) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                return null;
            }
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            RegisteredServer targetServer = event.getOriginalServer();
            String targetServerName = targetServer.getServerInfo().getName();
            RegisteredServer previousServer = event.getPreviousServer();
            Settings.OperationSettings operationSettings = settings.captureOperationSettings();
            Settings.PremiumSettings premiumSettings = operationSettings.premium();
            Settings.FloodgateSettings floodgateSettings = operationSettings.floodgate();

            logger.debug("玩家 {} 的 ServerPreConnectEvent -> 服务器 {}",
                    player.getUsername(), targetServerName);

            if (previousServer == null) {
                return handleFirstConnection(
                        event,
                        player,
                        targetServer,
                        targetServerName,
                        premiumSettings,
                        floodgateSettings,
                        operation);
            }

            if (handleAuthServerConnection(event, player, targetServer, operation)) {
                return null;
            }

            return EventTask.resumeWhenComplete(verifyBackendConnectionAsync(
                    event, player, targetServerName, floodgateSettings, operation));

        } catch (RuntimeException e) {
            logger.error("ServerPreConnect 处理出错", e);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return null;
        }
    }

    private EventTask handleFirstConnection(
            ServerPreConnectEvent event,
            Player player,
            RegisteredServer targetServer,
            String targetServerName,
            Settings.PremiumSettings premiumSettings,
            Settings.FloodgateSettings floodgateSettings,
            ConnectionRef operation) {
        boolean targetIsAuthServer = connectionManager.isAuthServer(targetServer);

        if (shouldBypassAuthServerForPremium(player, premiumSettings)) {
            if (targetIsAuthServer) {
                return EventTask.resumeWhenComplete(selectInitialBackendAsync(
                        event, player, () -> shouldBypassAuthServerForPremium(player, premiumSettings),
                        PREMIUM_MARKER, "Premium", operation));
            }
            logger.info(PREMIUM_MARKER, "Premium player {} -> {} (skipping auth server)",
                    player.getUsername(), targetServerName);
            allowServerIfCurrent(event, targetServer, operation);
            return null;
        }

        if (shouldBypassAuthServer(player, floodgateSettings)) {
            if (targetIsAuthServer) {
                return EventTask.resumeWhenComplete(selectInitialBackendAsync(
                        event, player, () -> shouldBypassAuthServer(player, floodgateSettings),
                        AUTH_MARKER, "Floodgate", operation));
            }
            logger.info("[FLOODGATE] Bedrock player {} -> {} (skipping auth server)",
                    player.getUsername(), targetServerName);
            allowServerIfCurrent(event, targetServer, operation);
            return null;
        }

        if (targetIsAuthServer) {
            if (!prepareAuthServerConnectionIfCurrent(
                    event, player, targetServer, operation)) {
                return null;
            }
            logger.debug("首次连接 {} -> 认证服——放行", player.getUsername());
            return null;
        }

        connectionLifecycleRegistry.runIfCurrent(operation,
                () -> connectionManager.setForcedHostTarget(player, targetServerName));

        Optional<RegisteredServer> authServer = connectionManager.resolveAuthServer();
        if (authServer.isPresent()) {
            if (!prepareAuthServerConnectionIfCurrent(
                    event, player, authServer.get(), operation)) {
                return null;
            }
            logger.debug("首次连接 {} -> {}——重定向到认证服（已保存强制主机目标）",
                    player.getUsername(), targetServerName);
        } else {
            logger.error("认证服 '{}' 不存在！玩家 {} 无法连接。",
                    connectionManager.getAuthServerName(), player.getUsername());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
        return null;
    }

    private static boolean shouldBypassAuthServerForPremium(
            Player player,
            Settings.PremiumSettings premiumSettings) {
        return premiumSettings.isBypassAuthServer() && player.isOnlineMode();
    }

    private CompletableFuture<Void> selectInitialBackendAsync(
            ServerPreConnectEvent event, Player player, BooleanSupplier bypassStillAllowed,
            Marker marker, String bypassType, ConnectionRef operation) {
        return connectionManager.findAvailableBackendServerForInitialConnectionAsync()
                .handle((backend, throwable) -> {
                    boolean applied = connectionLifecycleRegistry.runIfCurrent(operation, () -> {
                        if (throwable != null) {
                            logger.error(marker, "Failed to select initial backend for {} player {}",
                                    bypassType, player.getUsername(), throwable);
                            event.setResult(ServerPreConnectEvent.ServerResult.denied());
                        } else if (!player.isActive()
                                || !isInitialBypassStillAllowed(bypassStillAllowed)) {
                            event.setResult(ServerPreConnectEvent.ServerResult.denied());
                        } else if (backend != null && backend.isPresent()) {
                            RegisteredServer target = backend.get();
                            logger.info(marker, "{} player {} -> {} (skipping auth server)",
                                    bypassType, player.getUsername(), target.getServerInfo().getName());
                            event.setResult(ServerPreConnectEvent.ServerResult.allowed(target));
                        } else {
                            logger.error(marker,
                                    "No backend available for {} player {} while bypassing auth server",
                                    bypassType, player.getUsername());
                            event.setResult(ServerPreConnectEvent.ServerResult.denied());
                        }
                    });
                    if (!applied) {
                        event.setResult(ServerPreConnectEvent.ServerResult.denied());
                    }
                    return null;
                });
    }

    private boolean isInitialBypassStillAllowed(BooleanSupplier bypassStillAllowed) {
        try {
            return bypassStillAllowed.getAsBoolean();
        } catch (RuntimeException exception) {
            logger.error(SECURITY_MARKER,
                    "Failed to revalidate auth-server bypass after backend selection", exception);
            return false;
        }
    }

    private boolean handleAuthServerConnection(
            ServerPreConnectEvent event, Player player, RegisteredServer targetServer,
            ConnectionRef operation) {
        if (!connectionManager.isAuthServer(targetServer)) {
            return false;
        }
        if (!connectionLifecycleRegistry.isCurrent(operation)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return true;
        }

        String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);
        if (isOfflineSessionValid(player, playerIp)) {

            logger.debug("已授权玩家 {} 试图前往认证服——改为重定向到后端",
                    player.getUsername());
            triggerAutoTransfer(player, operation);
            return true;
        }
        if (player.isOnlineMode()) {

            if (!refreshPremiumAuthorizationIfCurrent(player, playerIp, operation)) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                return true;
            }
            logger.debug("正版玩家 {} 重新授权（缓存过期）——重定向到后端",
                    player.getUsername());
            triggerAutoTransfer(player, operation);
            return true;
        }
        if (prepareAuthServerConnectionIfCurrent(event, player, targetServer, operation)) {
            logger.debug("认证服——放行未认证玩家");
        }
        return true;
    }

    /**
     * Full offline authentication state: an authorization cache entry alone is not enough —
     * the session TTL is the re-authentication boundary, so an expired session must send the
     * player back through the normal auth-server flow instead of auto-transferring them.
     */
    private boolean isOfflineSessionValid(Player player, String playerIp) {
        return AuthRuntime.shouldRestoreSession(settings, authCache,
                VelocityAuthPlayer.of(player), playerIp);
    }

    private CompletableFuture<Void> verifyBackendConnectionAsync(
            ServerPreConnectEvent event,
            Player player,
            String targetServerName,
            Settings.FloodgateSettings floodgateSettings,
            ConnectionRef operation) {
        if (!connectionLifecycleRegistry.isCurrent(operation)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return CompletableFuture.completedFuture(null);
        }
        if (shouldBypassAuthServer(player, floodgateSettings)) {
            logger.info("[FLOODGATE] Bedrock player {} -> {} (skipping auth server)",
                    player.getUsername(), targetServerName);
            allowServerIfCurrent(event, event.getOriginalServer(), operation);
            return CompletableFuture.completedFuture(null);
        }

        if (player.isOnlineMode()) {
            String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);

            boolean authorized = settings.isSessionCacheEnabled()
                    && authCache.isPlayerAuthorized(player.getUniqueId(), playerIp);
            boolean refreshed = authorized
                    || refreshPremiumAuthorizationIfCurrent(player, playerIp, operation);
            if (refreshed) {
                allowServerIfCurrent(event, event.getOriginalServer(), operation);
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
            return CompletableFuture.completedFuture(null);
        }

        String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);

        return uuidVerificationHandler.verifyPlayerUuid(player,
                        effect -> connectionLifecycleRegistry.runIfCurrent(operation, effect))
                .thenAccept(uuidMatches -> {
                    boolean decided = connectionLifecycleRegistry.runIfCurrent(operation, () ->
                            decideOfflineBackendConnection(
                                    event, player, targetServerName, playerIp, uuidMatches, operation));
                    if (!decided) {
                        event.setResult(ServerPreConnectEvent.ServerResult.denied());
                    }
                });
    }

    /** Runs inside the lifecycle guard; applies the authorization verdict to the event. */
    private void decideOfflineBackendConnection(
            ServerPreConnectEvent event, Player player, String targetServerName,
            String playerIp, boolean uuidMatches, ConnectionRef operation) {
        if (!player.isActive()) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        UUID playerUuid = player.getUniqueId();
        boolean isAuthorized = authCache.isPlayerAuthorized(playerUuid, playerIp);
        boolean hasActiveSession =
                authCache.hasActiveSession(playerUuid, player.getUsername(), playerIp);
        if (!isAuthorized || !hasActiveSession || !uuidMatches) {
            handleUnauthorizedConnection(event, player, targetServerName,
                    new AuthorizationChecks(isAuthorized, hasActiveSession, uuidMatches),
                    playerIp, operation);
        } else {

            logger.debug("已授权玩家 {} 前往 {}（会话正常，UUID 正常）",
                    player.getUsername(), targetServerName);
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(
                    event.getOriginalServer()));
        }
    }

    /** The three independent verdicts a backend connection must pass. */
    private record AuthorizationChecks(
            boolean authorized, boolean activeSession, boolean uuidMatches) {
    }

    private void handleUnauthorizedConnection(
            ServerPreConnectEvent event, Player player, String targetServerName,
            AuthorizationChecks checks, String playerIp,
            ConnectionRef operation) {

        String reason = resolveBlockReason(checks.authorized(), checks.activeSession());

        if (logger.isDebugEnabled()) {
            logger.debug("已阻止 {} 的未授权后端访问 -> {}（原因：{}，IP：{}）",
                    player.getUsername(), targetServerName, reason, playerIp);
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        connectionLifecycleRegistry.runIfCurrent(operation, () -> {
            player.sendMessage(Component.text()
                    .content("❌ ")
                    .color(NamedTextColor.RED)
                    .append(playerMessage(player, settings, messages, "auth.must_login", NamedTextColor.RED))
                    .build());

            if (!checks.uuidMatches()) {
                authCache.removeAuthorizedPlayer(player.getUniqueId());
                authCache.endSession(player.getUniqueId());
            }
        });
    }

    /**
     * Handles server connected event.
     * Logs player transfers between servers and sends appropriate messages.
     * For verified players connecting to auth server, triggers auto-transfer to backend.
     */
    @Subscribe(priority = -200)
    public void onServerConnected(ServerConnectedEvent event) {
        try {
            Player player = event.getPlayer();
            ConnectionRef operation = connectionLifecycleRegistry.capture(player);
            if (operation == null) {
                logger.debug(SECURITY_MARKER,
                        "Ignoring ServerConnectedEvent for retired connection {}",
                        player.getUsername());
                return;
            }
            String serverName = event.getServer().getServerInfo().getName();

            logger.debug("玩家 {} 的 ServerConnectedEvent -> 服务器 {}",
                    player.getUsername(), serverName);

            if (!connectionManager.isAuthServer(event.getServer())) {
                handleBackendConnection(player, serverName, operation);
            } else {
                handleAuthServerConnection(player, operation);
            }
        } catch (RuntimeException e) {
            logger.error("ServerConnected 处理出错", e);
        }
    }

    private void handleBackendConnection(Player player, String serverName, ConnectionRef operation) {
        connectionLifecycleRegistry.runIfCurrent(operation, () -> {
            if (logger.isDebugEnabled()) {
                logger.debug(AUTH_MARKER, messages.get("player.connected.backend"),
                        player.getUsername(), serverName);
            }
            player.sendMessage(messages.component("general.welcome.full", NamedTextColor.GREEN));
        });
    }

    private void handleAuthServerConnection(Player player, ConnectionRef operation) {
        if (logger.isDebugEnabled()) {
            logger.debug(AUTH_MARKER, "ServerConnected to auth server: {}", player.getUsername());
        }

        String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);
        if (isOfflineSessionValid(player, playerIp)) {
            triggerAutoTransfer(player, operation);
            return;
        }

        if (player.isOnlineMode()) {
            if (refreshPremiumAuthorizationIfCurrent(player, playerIp, operation)) {
                triggerAutoTransfer(player, operation);
            }
            return;
        }

        if (settings.isAutoLoginEnabled()) {
            attemptAutoLogin(player, operation);
            return;
        }
        startLimboFlow(player, operation);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private void attemptFingerprintAutoLogin(
            Player player, ConnectionRef operation, QqAuthService service, long startedAt) {
        String fingerprint = service == null ? null
                : DeviceFingerprints.fingerprint(
                        player, settings.isAutoLoginRequireSameSubnet());
        if (service == null) {
            startLimboFlow(player, operation);
            return;
        }
        io.github.fulingsnow.youshuiauth.auth.AutoLoginService.Result autoLogin =
                plugin.getAutoLoginService().attempt(VelocityAuthPlayer.of(player));
        if (!autoLogin.authorized()) {

            if (autoLogin.outcome()
                    == io.github.fulingsnow.youshuiauth.auth.AutoLoginService.Outcome.DEVICE_MISMATCH
                    && attemptDeviceApproval(player, operation, service, fingerprint)) {
                return;
            }
            startLimboFlow(player, operation);
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("{} 的免密设备校验耗时 {} ms",
                    player.getUsername(), elapsedMillis(startedAt));
        }
        connectionLifecycleRegistry.runIfCurrent(operation,
                () -> connectionManager.autoTransferFromAuthServerToBackend(player));
        if (logger.isDebugEnabled()) {
            logger.debug("{} 免密成功，{} ms 后安排后端传送",
                    player.getUsername(), elapsedMillis(startedAt));
        }
    }

    /** Skips limbo when the last successful login matches device fingerprint and subnet. */
    private void attemptAutoLogin(Player player, ConnectionRef operation) {
        long startedAt = System.nanoTime();
        databaseManager.findPlayerByNickname(player.getUsername())
                .thenAccept(dbResult -> {
                    RegisteredPlayer account = (dbResult != null && !dbResult.isDatabaseError())
                            ? dbResult.getValue() : null;
                    if (account == null) {
                        startLimboFlow(player, operation);
                        return;
                    }
                    QqAuthService service = plugin.getQqAuthService();

                    attemptFingerprintAutoLogin(player, operation, service, startedAt);
                })
                .exceptionally(e -> {
                    logger.warn("{} 的免密查询失败", player.getUsername(), e);
                    startLimboFlow(player, operation);
                    return null;
                });
    }

    /**
     * Why device auto-login is (not) available. {@code SUBNET_MISMATCH} is the one reason that
     * may be resolved by a QQ confirmation instead of the login lobby; the others must keep the
     * established fallback.
     */

    /**
     * Starts the QQ confirmation for a known account on an unrecognised device.
     *
     * @return true when the login is waiting for the group reply; false means "fall back to limbo"
     */
    private boolean attemptDeviceApproval(
            Player player, ConnectionRef operation, QqAuthService service) {
        return attemptDeviceApproval(player, operation, service,
                service == null ? null
                        : DeviceFingerprints.fingerprint(
                                player, settings.isAutoLoginRequireSameSubnet()));
    }

    private boolean attemptDeviceApproval(
            Player player, ConnectionRef operation, QqAuthService service, String fingerprint) {
        if (service == null) {
            return false;
        }
        Runnable onTimeout = () -> connectionLifecycleRegistry.runIfCurrent(operation, () -> {
            if (!player.isActive() || !connectionManager.isPlayerOnAuthServer(player)) {
                return;
            }
            String playerIp = VelocityPlayerAddressUtils.getPlayerIp(player);
            if (io.github.fulingsnow.youshuiauth.runtime.AuthRuntime.isAuthorizedWithSession(
                    authCache, VelocityAuthPlayer.of(player), playerIp)) {
                return;
            }
            startLimboFlow(player, operation);
        });
        return service.requestDeviceApproval(player, operation, fingerprint, onTimeout);
    }

    private void startLimboFlow(Player player, ConnectionRef operation) {

        connectionLifecycleRegistry.runIfCurrent(operation, () -> {
            plugin.getAuthTimeoutScheduler().schedule(player);

            plugin.getAuthTimeoutScheduler().useReminderPrompt(
                    player.getUniqueId(), genericPromptKey(), NamedTextColor.YELLOW);
        });
        sendAuthInstructions(player, operation);
    }

    private void triggerAutoTransfer(Player player, ConnectionRef operation) {
        connectionLifecycleRegistry.runIfCurrent(operation, () -> {
            if (logger.isDebugEnabled()) {
                logger.debug("玩家 {} 已在缓存中通过验证——开始自动传送到后端",
                        player.getUsername());
            }
            connectionManager.autoTransferFromAuthServerToBackend(player);
        });
    }

    private void sendAuthInstructions(Player player, ConnectionRef operation) {
        AtomicReference<String> username = new AtomicReference<>();
        if (!connectionLifecycleRegistry.runIfCurrent(operation, () -> {
            username.set(player.getUsername());
            player.sendMessage(playerMessage(player, settings, messages, "auth.header", NamedTextColor.GOLD));
        })) {
            return;
        }

        databaseManager.findPlayerByNickname(username.get())
                .thenAccept(dbResult -> {
                    QqAuthService qqService = plugin.getQqAuthService();
                    boolean orphanedQqOnly = qqService != null
                            && qqService.isOrphanedQqOnlyAccount(player);
                    connectionLifecycleRegistry.runIfCurrent(operation,
                            () -> sendAuthPrompt(player, dbResult, orphanedQqOnly));
                })
                .exceptionally(e -> {
                    logger.error("向 {} 发送认证提示出错", username.get(), e);

                    String fallbackKey = genericPromptKey();
                    connectionLifecycleRegistry.runIfCurrent(operation, () ->
                            plugin.getAuthTimeoutScheduler().useReminderPrompt(
                                    player.getUniqueId(), fallbackKey, NamedTextColor.YELLOW));
                    return null;
                });
    }

    private void sendAuthPrompt(Player player, DbResult<RegisteredPlayer> dbResult,
            boolean orphanedQqOnly) {
        if (dbResult.isDatabaseError()) {

            plugin.getAuthTimeoutScheduler().useReminderPrompt(
                    player.getUniqueId(), genericPromptKey(), NamedTextColor.YELLOW);
            return;
        }

        RegisteredPlayer registeredPlayer = dbResult.getValue();
        if (registeredPlayer != null && orphanedQqOnly) {
            plugin.getAuthTimeoutScheduler().useReminderPrompt(player.getUniqueId(),
                    "auth.stale_qq_account", NamedTextColor.GREEN, "/qqbind");
            return;
        }
        if (registeredPlayer != null) {
            boolean qqOnly = QqAuthService.QQ_ONLY_HASH.equals(registeredPlayer.getHash());
            String promptKey = qqOnly ? "auth.account_exists_qq" : "auth.account_exists";
            String command = qqOnly ? "/login" : null;
            plugin.getAuthTimeoutScheduler().useReminderPrompt(
                    player.getUniqueId(), promptKey, NamedTextColor.GREEN, command);
        } else {
            String promptKey = firstTimePromptKey();
            String command = "auth.first_time_qq".equals(promptKey) ? "/qqbind" : null;

            plugin.getAuthTimeoutScheduler().useReminderPrompt(
                    player.getUniqueId(), promptKey, NamedTextColor.GREEN, command);
        }
    }

    /**
     * Picks the first-join instruction that matches the current registration policy. When
     * password registration is disabled the player must be pointed at QQ registration (or at
     * an administrator), never at a command that will only reject them.
     */
    private String firstTimePromptKey() {
        return io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy
                .firstTimePromptKey(settings.isPasswordRegistrationEnabled(), isQqEnabled());
    }

    private boolean isQqEnabled() {
        return settings.getQqSettings() != null && settings.getQqSettings().isEnabled();
    }

    /**
     * Fallback prompt for a failed/unknown account lookup. It must not advertise a command
     * that the current policy disabled (for example /register).
     */
    private String genericPromptKey() {
        return io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy
                .promptKey(settings.isPasswordRegistrationEnabled(), isQqEnabled());
    }

    private Component localizedOrFallback(String key, String fallback, NamedTextColor fallbackColor) {
        if (messages == null) {
            return Component.text(fallback, fallbackColor);
        }
        return messages.component(key, fallbackColor);
    }

    private record PendingLoginAttempt(InboundConnection connection) {
    }

}
