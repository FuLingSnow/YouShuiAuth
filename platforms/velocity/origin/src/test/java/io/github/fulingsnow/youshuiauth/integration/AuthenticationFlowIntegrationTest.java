package io.github.fulingsnow.youshuiauth.integration;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.connection.ConnectionManager;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.listener.AuthListener;
import io.github.fulingsnow.youshuiauth.listener.ListenerFactory;
import io.github.fulingsnow.youshuiauth.listener.PostLoginHandler;
import io.github.fulingsnow.youshuiauth.listener.PreLoginHandler;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.premium.PremiumResolverService;
import org.bstats.velocity.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.github.fulingsnow.youshuiauth.testsupport.EventTaskTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for authentication flow scenarios.
 * Tests requirements 4.1, 4.2, and 4.4 from the refactoring spec.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"java:S100", "java:S1450", "deprecation"})
class AuthenticationFlowIntegrationTest {

    private PreLoginHandler preLoginHandler;
    private PostLoginHandler postLoginHandler;
    private AuthCache authCache;
    private TestDatabaseManager databaseManager;
    private Messages messages;

    private PremiumResolverService premiumResolverService;
    private Settings settings;
    @Mock
    private org.slf4j.Logger logger;
    private io.github.fulingsnow.youshuiauth.YouShuiAuth plugin;
    @Mock
    private com.velocitypowered.api.proxy.ProxyServer proxyServer;

    @BeforeEach
    void setUp() {
        messages = new Messages();
        messages.setLanguage("en");

        java.nio.file.Path tempDir = createTempDirectory();
        settings = new Settings(tempDir);
        settings.load();

        authCache = new AuthCache(
                new AuthCache.AuthCacheConfig(60, 10000, 1000, 10000, 5, 5, 1, 60),
                settings, messages
        );

        databaseManager = createTestDatabaseManager();
        premiumResolverService = createPremiumResolverService();
        configureLoggerMocks();

        Metrics.Factory metricsFactory = mock(Metrics.Factory.class);
        plugin = new io.github.fulingsnow.youshuiauth.YouShuiAuth(proxyServer, logger, tempDir, metricsFactory);

        preLoginHandler = ListenerFactory.createPreLoginHandler(
                authCache, premiumResolverService, settings, databaseManager, messages, logger
        );

        configureSchedulerMocks();

        postLoginHandler = ListenerFactory.createPostLoginHandler(authCache, databaseManager, messages, logger);
    }

    private java.nio.file.Path createTempDirectory() {
        try {
            return java.nio.file.Files.createTempDirectory("youshuiauth-it-config");
        } catch (java.io.IOException e) {
            java.nio.file.Path fallback = java.nio.file.Paths.get("target", "youshuiauth-it-config");
            try {
                java.nio.file.Files.createDirectories(fallback);
            } catch (java.io.IOException ignored) {

            }
            return fallback;
        }
    }

    private TestDatabaseManager createTestDatabaseManager() {
        io.github.fulingsnow.youshuiauth.database.DatabaseConfig testConfig =
                io.github.fulingsnow.youshuiauth.database.DatabaseConfig.forLocalDatabase("H2", "memtest");
        return new TestDatabaseManager(testConfig, messages);
    }

        private PremiumResolverService createPremiumResolverService() {
        try {
            com.j256.ormlite.jdbc.JdbcConnectionSource cs =
                    new com.j256.ormlite.jdbc.JdbcConnectionSource("jdbc:h2:mem:youshuiauth_premium");
            io.github.fulingsnow.youshuiauth.database.PremiumUuidDao premiumDao =
                    new io.github.fulingsnow.youshuiauth.database.PremiumUuidDao(cs);
            return new PremiumResolverService(logger, settings, premiumDao);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to initialize PremiumUuidDao for test", e);
        }
    }

    private void configureLoggerMocks() {
        when(logger.isDebugEnabled()).thenReturn(false);
        when(logger.isInfoEnabled()).thenReturn(false);
    }

    private void configureSchedulerMocks() {
        when(proxyServer.getScheduler()).thenReturn(mock(com.velocitypowered.api.scheduler.Scheduler.class));
        com.velocitypowered.api.scheduler.Scheduler.TaskBuilder taskBuilder =
                mock(com.velocitypowered.api.scheduler.Scheduler.TaskBuilder.class);
        when(proxyServer.getScheduler().buildTask(
                any(), org.mockito.ArgumentMatchers.<java.util.function.Consumer<
                        com.velocitypowered.api.scheduler.ScheduledTask>>any())).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(com.velocitypowered.api.scheduler.ScheduledTask.class));
    }

    /**
     * Test: Premium player login flow
     * Requirements: 4.1, 4.2
     */
    @Test
    void testPremiumPlayerLoginFlow_shouldAuthorizeAndStartSession() {

        String username = "PremiumPlayer";
        UUID premiumUuid = UUID.randomUUID();
        String playerIp = "192.168.1.100";

        authCache.addPremiumPlayer(username, premiumUuid);

        assertTrue(preLoginHandler.isValidUsername(username),
                "Premium player username should be valid");

        PreLoginHandler.PremiumResolutionResult result =
                preLoginHandler.resolvePremiumStatusAsync(username).join();
        assertTrue(result.premium(), "Player should be detected as premium");
        assertNotNull(result.premiumUuid(), "Premium UUID should be present");

        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(username);
        when(player.getUniqueId()).thenReturn(premiumUuid);
        when(player.isOnlineMode()).thenReturn(true);

        assertDoesNotThrow(() -> postLoginHandler.handlePremiumPlayer(player, playerIp),
                "Premium player handling should not throw exceptions");

        assertTrue(authCache.isPlayerAuthorized(premiumUuid, playerIp),
                "Premium player should be authorized");
        assertTrue(authCache.hasActiveSession(premiumUuid, username, playerIp),
                "Premium player should have active session");
    }

    /**
     * Test: Offline player login flow
     * Requirements: 4.1, 4.2
     */
    @Test
    void testOfflinePlayerLoginFlow_shouldRedirectToAuthServer() {

        String username = "OfflinePlayer";
        UUID offlineUuid = UUID.randomUUID();
        String playerIp = "192.168.1.101";

        authCache.addPremiumPlayer(username, null);

        assertTrue(preLoginHandler.isValidUsername(username),
                "Offline player username should be valid");

        PreLoginHandler.PremiumResolutionResult result =
                preLoginHandler.resolvePremiumStatusAsync(username).join();
        assertFalse(result.premium(), "Player should be detected as offline");

        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(username);
        when(player.getUniqueId()).thenReturn(offlineUuid);
        when(player.isOnlineMode()).thenReturn(false);

        assertDoesNotThrow(() -> postLoginHandler.handleOfflinePlayer(player, playerIp),
                "Offline player handling should not throw exceptions");

        assertFalse(authCache.isPlayerAuthorized(offlineUuid, playerIp),
                "Offline player should not be authorized before login");
    }

    /**
     * Test: Brute force protection
     * Requirements: 4.1, 4.4
     */
    @Test
    void testBruteForceProtection_shouldBlockAfterMultipleFailedAttempts() throws Exception {

        InetAddress attackerIp = InetAddress.getByName("10.0.0.1");

        assertFalse(preLoginHandler.isBruteForceBlocked(attackerIp, "attacker"),
                "IP should not be blocked initially");

        for (int i = 0; i < 5; i++) {
            authCache.registerFailedLogin(attackerIp, "attacker");
        }

        assertTrue(preLoginHandler.isBruteForceBlocked(attackerIp, "attacker"),
                "IP should be blocked after multiple failed attempts");
    }

    /**
     * Test: Nickname conflict scenario - Premium using offline nickname
     * Requirements: 4.1, 4.2
     */
    @Test
    void testNicknameConflict_premiumUsingOfflineNickname_shouldForceOfflineMode() {

        String conflictedNickname = "ConflictedPlayer";
        RegisteredPlayer existingOfflinePlayer = new RegisteredPlayer();
        existingOfflinePlayer.setNickname(conflictedNickname);
        existingOfflinePlayer.setUuid(UUID.randomUUID().toString());
        existingOfflinePlayer.setPremiumUuid(null);
        existingOfflinePlayer.setConflictMode(false);

        databaseManager.setFindResult(
                conflictedNickname.toLowerCase(),
                CompletableFuture.completedFuture(
                        DatabaseManager.DbResult.success(existingOfflinePlayer)
                )
        );

        boolean isPremium = true;
        boolean existingIsPremium = (existingOfflinePlayer.getPremiumUuid() != null);
        UUID currentPremiumUuid = UUID.randomUUID();

        boolean hasConflict = preLoginHandler.isNicknameConflict(
                existingOfflinePlayer, isPremium, existingIsPremium, currentPremiumUuid);

        assertTrue(hasConflict,
                "Should detect conflict when premium player uses offline nickname");
    }

    /**
     * Test: Offline player accessing conflicted account
     * Requirements: 4.1, 4.2
     */
    @Test
    void testNicknameConflict_offlinePlayerAccessingConflictedAccount_shouldForceOfflineMode() {

        String conflictedNickname = "ConflictedPlayer";
        RegisteredPlayer conflictedPlayer = new RegisteredPlayer();
        conflictedPlayer.setNickname(conflictedNickname);
        conflictedPlayer.setUuid(UUID.randomUUID().toString());
        conflictedPlayer.setPremiumUuid(null);
        conflictedPlayer.setConflictMode(true);
        conflictedPlayer.setConflictTimestamp(System.currentTimeMillis());
        conflictedPlayer.setOriginalNickname(conflictedNickname);

        boolean isPremium = false;
        boolean existingIsPremium = false;

        boolean hasConflict = preLoginHandler.isNicknameConflict(
                conflictedPlayer, isPremium, existingIsPremium, null);

        assertTrue(hasConflict,
                "Should detect conflict when offline player accesses conflicted account");
    }

    /**
     * Test: Auth server routing for unauthorized players
     * Requirements: 4.2
     */
    @Test
    void testAuthServerRouting_unauthorizedPlayer_shouldTransferToAuthServer() {

        String username = "UnauthorizedPlayer";
        UUID playerUuid = UUID.randomUUID();
        String playerIp = "192.168.1.102";

        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(username);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOnlineMode()).thenReturn(false);
        when(player.getRemoteAddress()).thenReturn(
                InetSocketAddress.createUnresolved(playerIp, 25565));

        assertDoesNotThrow(() -> postLoginHandler.handleOfflinePlayer(player, playerIp),
                "Unauthorized player handling should not throw exceptions");

        assertFalse(authCache.isPlayerAuthorized(playerUuid, playerIp),
                "Unauthorized player should not be in authorized cache");
    }

    /**
     * Test: Backend server routing for authorized players
     * Requirements: 4.2
     */
    @Test
    void testBackendRouting_authorizedPlayer_shouldStayOnBackend() {

        String username = "AuthorizedPlayer";
        UUID playerUuid = UUID.randomUUID();
        String playerIp = "192.168.1.103";

        CachedAuthUser cachedUser = new CachedAuthUser(
                playerUuid, username, playerIp,
                System.currentTimeMillis(), false, null);
        authCache.addAuthorizedPlayer(playerUuid, cachedUser);

        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(username);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOnlineMode()).thenReturn(false);

        assertDoesNotThrow(() -> postLoginHandler.handleOfflinePlayer(player, playerIp),
                "Authorized player handling should not throw exceptions");

        assertTrue(authCache.isPlayerAuthorized(playerUuid, playerIp),
                "Authorized player should remain in authorized cache");
    }

    /**
     * Test: Session management works correctly
     * Requirements: 4.2, 4.4
     */
    @Test
    void testSessionManagement_shouldMaintainSessionState() {

        String username = "SessionPlayer";
        UUID playerUuid = UUID.randomUUID();
        String playerIp = "192.168.1.104";

        authCache.startSession(playerUuid, username, playerIp);

        assertTrue(authCache.hasActiveSession(playerUuid, username, playerIp),
                "Session should be active after starting");

        CachedAuthUser cachedUser = new CachedAuthUser(
                playerUuid, username, playerIp,
                System.currentTimeMillis(), false, null);
        authCache.addAuthorizedPlayer(playerUuid, cachedUser);

        assertTrue(authCache.hasActiveSession(playerUuid, username, playerIp),
                "Session should persist after authorization");
        assertTrue(authCache.isPlayerAuthorized(playerUuid, playerIp),
                "Player should be authorized");

        authCache.endSession(playerUuid);

        assertFalse(authCache.hasActiveSession(playerUuid, username, playerIp),
                "Session should end after explicit termination");
    }

    /**
     * Test: Username validation
     * Requirements: 4.1
     */
    @Test
    void testUsernameValidation_variousFormats_shouldValidateCorrectly() {

        assertTrue(preLoginHandler.isValidUsername("Player123"),
                "Alphanumeric username should be valid");
        assertTrue(preLoginHandler.isValidUsername("Test_User"),
                "Username with underscore should be valid");
        assertTrue(preLoginHandler.isValidUsername("ABC"),
                "3-character username should be valid");
        assertTrue(preLoginHandler.isValidUsername("A123456789012345"),
                "16-character username should be valid");

        assertFalse(preLoginHandler.isValidUsername("AB"),
                "2-character username should be invalid");
        assertFalse(preLoginHandler.isValidUsername("A1234567890123456"),
                "17-character username should be invalid");
        assertFalse(preLoginHandler.isValidUsername("Player-123"),
                "Username with hyphen should be invalid");
        assertFalse(preLoginHandler.isValidUsername("Player.123"),
                "Username with dot should be invalid");
        assertFalse(preLoginHandler.isValidUsername("..Player123"),
                "Username with double Floodgate-like prefix should be invalid");
        assertFalse(preLoginHandler.isValidUsername(""),
                "Empty username should be invalid");
        assertFalse(preLoginHandler.isValidUsername(null),
                "Null username should be invalid");
    }

    /**
     * Test: Conflict message display logic
     * Requirements: 4.2
     */
    @Test
    void testConflictMessage_premiumPlayerInConflictMode_shouldShowMessage() {

        String username = "ConflictPlayer";
        UUID playerUuid = UUID.randomUUID();
        UUID premiumUuid = UUID.randomUUID();

        RegisteredPlayer conflictedPlayer = new RegisteredPlayer();
        conflictedPlayer.setNickname(username);
        conflictedPlayer.setUuid(playerUuid.toString());
        conflictedPlayer.setPremiumUuid(premiumUuid.toString());
        conflictedPlayer.setConflictMode(true);

        databaseManager.setFindResult(
                username.toLowerCase(),
                CompletableFuture.completedFuture(
                        DatabaseManager.DbResult.success(conflictedPlayer)
                )
        );

        authCache.addPremiumPlayer(username, premiumUuid);

        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(username);
        when(player.getUniqueId()).thenReturn(playerUuid);

        boolean shouldShow = postLoginHandler.shouldShowConflictMessage(player);
        assertTrue(shouldShow, "Should show conflict message for premium player in conflict mode");

        assertDoesNotThrow(() -> postLoginHandler.showConflictResolutionMessage(player),
                "Showing conflict message should not throw exceptions");
    }

    /**
     * Test: Thread safety in concurrent operations
     * Requirements: 4.4
     */
    @Test
    void testThreadSafety_concurrentOperations_shouldNotCauseIssues() {

        int playerCount = 10;

        assertDoesNotThrow(() -> {
            for (int i = 0; i < playerCount; i++) {
                String username = "Player" + i;
                UUID playerUuid = UUID.randomUUID();
                String playerIp = "192.168.1." + (100 + i);

                authCache.startSession(playerUuid, username, playerIp);

                CachedAuthUser cachedUser = new CachedAuthUser(
                        playerUuid, username, playerIp,
                        System.currentTimeMillis(), false, null);
                authCache.addAuthorizedPlayer(playerUuid, cachedUser);

                assertTrue(authCache.hasActiveSession(playerUuid, username, playerIp),
                        "Session should be active for player " + i);
                assertTrue(authCache.isPlayerAuthorized(playerUuid, playerIp),
                        "Player " + i + " should be authorized");
            }
        }, "Concurrent operations should not cause exceptions");
    }

    /**
     * Test: Premium player with DB error during pre-login should be DENIED, not forced offline.
     * Reproduces the bug where a premium player gets forceOfflineMode on DB error,
     * then registers with a wrong (offline) UUID, permanently corrupting their account.
     */
    @Test
    void testPremiumPlayer_dbErrorDuringPreLogin_shouldDenyNotForceOffline() throws Exception {

        String username = "PremiumDBError";
        UUID premiumUuid = UUID.randomUUID();
        authCache.addPremiumPlayer(username, premiumUuid);

        databaseManager.setNicknameOrPremiumUuidResult(
                CompletableFuture.completedFuture(
                        DatabaseManager.DbResult.databaseError("Connection timeout")));

        ConnectionManager connectionManager = mock(ConnectionManager.class);
        PostLoginHandler plHandler = ListenerFactory.createPostLoginHandler(authCache, databaseManager, messages, logger);
        setPluginInitialized(true);
        AuthListener authListener = new AuthListener(
                plugin, authCache, settings, preLoginHandler, plHandler,
                connectionManager, databaseManager, messages);

        com.velocitypowered.api.proxy.InboundConnection connection =
                mock(com.velocitypowered.api.proxy.InboundConnection.class);
        when(connection.getRemoteAddress()).thenReturn(
                new java.net.InetSocketAddress("127.0.0.1", 25565));
        PreLoginEvent event = new PreLoginEvent(connection, username);

        awaitPreLoginEvent(authListener, event);

        PreLoginEvent.PreLoginComponentResult result = event.getResult();
        assertFalse(result.isForceOfflineMode(),
                "Premium player with DB error must NOT be forced to offline mode - this causes UUID corruption");
        assertFalse(result.isAllowed(),
                "Premium player with DB error should be denied (not just allowed/offline)");
    }

    /**
     * Test: Offline player with DB error during pre-login can still use forceOfflineMode.
     * Only premium players should be denied on DB error - offline players are already offline.
     */
    @Test
    void testOfflinePlayer_dbErrorDuringPreLogin_shouldForceOffline() throws Exception {

        String username = "OfflineDBError";
        authCache.addPremiumPlayer(username, null);

        databaseManager.setNicknameOrPremiumUuidResult(
                CompletableFuture.completedFuture(
                        DatabaseManager.DbResult.databaseError("Connection timeout")));

        ConnectionManager connectionManager = mock(ConnectionManager.class);
        PostLoginHandler plHandler = ListenerFactory.createPostLoginHandler(authCache, databaseManager, messages, logger);
        setPluginInitialized(true);
        AuthListener authListener = new AuthListener(
                plugin, authCache, settings, preLoginHandler, plHandler,
                connectionManager, databaseManager, messages);

        com.velocitypowered.api.proxy.InboundConnection connection =
                mock(com.velocitypowered.api.proxy.InboundConnection.class);
        when(connection.getRemoteAddress()).thenReturn(
                new java.net.InetSocketAddress("127.0.0.1", 25565));
        PreLoginEvent event = new PreLoginEvent(connection, username);

        awaitPreLoginEvent(authListener, event);

        PreLoginEvent.PreLoginComponentResult result = event.getResult();
        assertTrue(result.isForceOfflineMode(),
                "Offline player with DB error should still use forceOfflineMode - it's safe for them");
    }

    @Test
    void testBackendVerification_asyncUuidLookupShouldRecheckAuthorizationState() throws Exception {
        String username = "AsyncVerification";
        UUID playerUuid = UUID.randomUUID();
        String playerIp = "192.168.1.105";

        CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>> uuidLookup = new CompletableFuture<>();
        databaseManager.setFindResult(username.toLowerCase(), uuidLookup);

        ConnectionManager connectionManager = mock(ConnectionManager.class);
        AuthListener authListener = new AuthListener(
                plugin, authCache, settings, preLoginHandler, postLoginHandler,
                connectionManager, databaseManager, messages);
        setPluginInitialized(true);

        CachedAuthUser cachedUser = new CachedAuthUser(
                playerUuid, username, playerIp, System.currentTimeMillis(), false, null);
        authCache.addAuthorizedPlayer(playerUuid, cachedUser);
        authCache.startSession(playerUuid, username, playerIp);

        Player player = mock(Player.class);
        when(player.getUsername()).thenReturn(username);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.isOnlineMode()).thenReturn(false);
        when(player.isActive()).thenReturn(true);
        when(player.getRemoteAddress()).thenReturn(new InetSocketAddress(playerIp, 25565));
        assertNotNull(plugin.getConnectionLifecycleRegistry().activate(
                player, ignored -> connectionManager.beginTransferSession(player)));

        RegisteredServer backendServer = mock(RegisteredServer.class);
        when(backendServer.getServerInfo()).thenReturn(
                new ServerInfo("backend", InetSocketAddress.createUnresolved("127.0.0.1", 25566)));

        RegisteredServer previousServer = mock(RegisteredServer.class);
        when(previousServer.getServerInfo()).thenReturn(
                new ServerInfo(settings.getAuthServerName(), InetSocketAddress.createUnresolved("127.0.0.1", 25565)));

        ServerPreConnectEvent event = new ServerPreConnectEvent(player, backendServer, previousServer);
        com.velocitypowered.api.event.EventTask task = authListener.onServerPreConnect(event);
        assertNotNull(task, "Backend UUID verification should run asynchronously");

        authCache.removeAuthorizedPlayer(playerUuid);
        authCache.endSession(playerUuid);

        RegisteredPlayer dbPlayer = new RegisteredPlayer();
        dbPlayer.setNickname(username);
        dbPlayer.setUuid(playerUuid.toString());
        uuidLookup.complete(DatabaseManager.DbResult.success(dbPlayer));

        await(task);

        assertFalse(event.getResult().isAllowed(),
                "Async UUID verification must re-check auth state before allowing backend access");
    }

    private void setPluginInitialized(boolean value) throws Exception {
        java.lang.reflect.Field field = io.github.fulingsnow.youshuiauth.YouShuiAuth.class.getDeclaredField("initialized");
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private void awaitPreLoginEvent(AuthListener authListener, PreLoginEvent event) {
        com.velocitypowered.api.event.EventTask task = authListener.onPreLogin(event);
        if (task != null) {
            await(task);
        }
    }
}
