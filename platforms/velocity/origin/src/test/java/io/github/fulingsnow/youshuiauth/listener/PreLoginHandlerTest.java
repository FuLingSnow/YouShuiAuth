package io.github.fulingsnow.youshuiauth.listener;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.cache.AuthCache.PremiumCacheEntry;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.premium.PremiumResolverService;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PreLoginHandler - Velocity plugin authentication logic.
 * Tests focus on:
 * 1. Username validation (Minecraft format rules)
 * 2. Brute force protection (IP blocking)
 * 3. Premium status resolution (caching, TTL, background refresh)
 * 4. Nickname conflict detection (offline vs premium hijacking)
 *
 * Best Practices Applied (from Exa research):
 * - Mock all external dependencies (AuthCache, PremiumResolverService, DatabaseManager)
 * - Use @MockitoSettings(strictness = Strictness.LENIENT) for common setUp()
 * - Test business logic separately from Velocity API interactions
 * - Use parameterized tests for multiple input scenarios
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreLoginHandlerTest {

    @Mock
    private AuthCache authCache;

    @Mock
    private PremiumResolverService premiumResolverService;

    @Mock
    private DatabaseManager databaseManager;

    @Mock
    private Messages messages;

    @Mock
    private Logger logger;

    @Mock
    private Settings settings;

    private PreLoginHandler handler;

    @BeforeEach
    void setUp() {
        FloodgateApi.clear();
        when(settings.isFloodgateIntegrationEnabled()).thenReturn(true);
        when(settings.getFloodgateUsernamePrefix()).thenReturn(".");
        when(settings.getFloodgateSettings()).thenAnswer(ignored -> new Settings.FloodgateSettings(
                settings.isFloodgateIntegrationEnabled(),
                settings.getFloodgateUsernamePrefix(),
                false));
        when(databaseManager.findPlayerByNickname(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(DatabaseManager.DbResult.success(null)));

        handler = new PreLoginHandler(
                authCache,
                premiumResolverService,
                settings,
                databaseManager,
                messages,
                logger
        );
    }

    @AfterEach
    void tearDown() {
        FloodgateApi.clear();
    }

    @ParameterizedTest(name = "shouldAcceptValidUsername: {0}")
    @ValueSource(strings = {
            "Steve",
            "Alex",
            "Notch",
            "jeb_",
            "Player123",
            "A_B_C_1_2_3",
            "TestUser16Char",
            ".Steve"
    })
    void shouldAcceptValidUsername(String username) {

        boolean result = handler.isValidUsername(username);

        assertTrue(result, "Should accept valid username: " + username);
    }

    @ParameterizedTest(name = "shouldRejectInvalidUsername: {0}")
    @ValueSource(strings = {
            "",
            "AB",
            "ThisNameIsTooLong17",
            "User-Name",
            "User.Name",
            "User Name",
            "User@Name",
            "User#123",
            ".AB",
            ".ThisNameIsTooLong17",
            "..Steve",

    })
    void shouldRejectInvalidUsername(String username) {

        boolean result = handler.isValidUsername(username);

        assertFalse(result, "Should reject invalid username: " + username);
    }

    @Test
    void shouldRejectNullUsername() {

        boolean result = handler.isValidUsername(null);

        assertFalse(result, "Should reject null username");
    }

    @Test
    void shouldAcceptCustomFloodgatePrefixWhenConfigured() {
        when(settings.getFloodgateUsernamePrefix()).thenReturn("+");

        boolean result = handler.isValidUsername("+Alex");

        assertTrue(result, "Should accept username with configured custom Floodgate prefix");
    }

    @Test
    void shouldUseLiveFloodgatePrefixWhenConfigIsStale() {
        FloodgateApi.install("+", Set.of(), List.of());

        assertTrue(handler.isValidUsername("+Alex"),
                "Live Floodgate prefix should be accepted even when config still contains the old prefix");
        assertFalse(handler.isValidUsername(".Alex"),
                "Stale configured prefix must not override the running Floodgate instance");
    }

    @Test
    void shouldRejectFloodgatePrefixWhenIntegrationDisabled() {
        when(settings.isFloodgateIntegrationEnabled()).thenReturn(false);

        boolean result = handler.isValidUsername(".Steve");

        assertFalse(result, "Should reject Floodgate-prefixed username when integration is disabled");
    }

    @Test
    void shouldBlockBruteForceIP() throws UnknownHostException {

        InetAddress blockedIP = InetAddress.getByName("192.168.1.100");
        when(authCache.isBlocked(blockedIP, null)).thenReturn(true);

        boolean result = handler.isBruteForceBlocked(blockedIP, null);

        assertTrue(result, "Should block brute force IP");
        verify(authCache, times(1)).isBlocked(blockedIP, null);
    }

    @Test
    void shouldAllowNonBlockedIP() throws UnknownHostException {

        InetAddress allowedIP = InetAddress.getByName("10.0.0.1");
        when(authCache.isBlocked(allowedIP, null)).thenReturn(false);

        boolean result = handler.isBruteForceBlocked(allowedIP, null);

        assertFalse(result, "Should allow non-blocked IP");
        verify(authCache, times(1)).isBlocked(allowedIP, null);
    }

    @Test
    void shouldHandleNullIPAddress() {

        boolean result = handler.isBruteForceBlocked(null, null);

        assertTrue(result, "Should block null IP (fail-secure)");
        verify(authCache, never()).isBlocked(null, null);
    }

    @Test
    void shouldReturnCachedPremiumStatus() {

        String username = "Notch";
        UUID premiumUuid = UUID.randomUUID();
        PremiumCacheEntry cachedEntry = new PremiumCacheEntry(true, premiumUuid, System.currentTimeMillis(), 600000L);
        when(authCache.getPremiumStatus(username)).thenReturn(cachedEntry);

        PreLoginHandler.PremiumResolutionResult result = handler.resolvePremiumStatusAsync(username).join();
        assertNotNull(result, "Result should not be null");
        assertTrue(result.premium(), "Should be premium");
        assertEquals(premiumUuid, result.premiumUuid(), "Should return cached UUID");
    }

    @Test
    void resolvePremiumStatusAsync_legacyLimboAuthPremiumRowShouldForceOnlineWithoutCacheUuid() {
        String username = "LegacyPremium";
        RegisteredPlayer player = new RegisteredPlayer(
                username, null, "127.0.0.1", UUID.randomUUID().toString());
        when(databaseManager.findPlayerByNickname(username)).thenReturn(
                CompletableFuture.completedFuture(DatabaseManager.DbResult.success(player)));
        when(databaseManager.isPlayerPremiumRuntime(player)).thenReturn(true);

        PreLoginHandler.PremiumResolutionResult result = handler.resolvePremiumStatusAsync(username).join();

        assertNotNull(result);
        assertTrue(result.premium());
        assertNull(result.premiumUuid(), "Mojang UUID is unknown until the authenticated profile event");
        verify(premiumResolverService, never()).resolve(username);
    }

    @Test
    void resolvePremiumStatusAsync_existingOfflineAuthOwnerShouldIgnoreStalePremiumCache() {
        String username = "ReassignedName";
        RegisteredPlayer offlineOwner = new RegisteredPlayer(
                username,
                "$2a$10$offlinehashvalueofflinehashvalueofflinehashval",
                "127.0.0.1",
                UUID.randomUUID().toString());
        when(databaseManager.findPlayerByNickname(username)).thenReturn(
                CompletableFuture.completedFuture(
                        DatabaseManager.DbResult.success(offlineOwner)));
        when(databaseManager.isPlayerPremiumRuntime(offlineOwner)).thenReturn(false);
        when(authCache.getPremiumStatus(username)).thenReturn(new PremiumCacheEntry(
                true, UUID.randomUUID(), System.currentTimeMillis(), 600_000L));

        PreLoginHandler.PremiumResolutionResult result =
                handler.resolvePremiumStatusAsync(username).join();

        assertNotNull(result);
        assertFalse(result.premium(),
                "An offline AUTH owner must not inherit the previous nickname owner's cache");
        assertNull(result.premiumUuid());
        verify(authCache, never()).getPremiumStatus(username);
        verify(premiumResolverService, never()).resolve(username);
    }

    @Test
    void resolvePremiumStatusAsync_authDatabaseErrorShouldFailClosed() {
        String username = "DatabaseFailure";
        when(databaseManager.findPlayerByNickname(username)).thenReturn(
                CompletableFuture.completedFuture(DatabaseManager.DbResult.databaseError("database.error")));

        PreLoginHandler.PremiumResolutionResult result = handler.resolvePremiumStatusAsync(username).join();

        assertNull(result);
        verify(premiumResolverService, never()).resolve(username);
    }

    @Test
    void shouldDetectConflictWhenPremiumTriesToUseOfflineAccount() {

        RegisteredPlayer offlinePlayer = new RegisteredPlayer();
        offlinePlayer.setNickname("TestPlayer");
        offlinePlayer.setUuid(UUID.randomUUID().toString());
        offlinePlayer.setPremiumUuid(null);
        offlinePlayer.setConflictMode(false);

        boolean result = handler.isNicknameConflict(offlinePlayer, true, false, UUID.randomUUID());

        assertTrue(result, "Should detect conflict when premium uses offline nickname");
    }

    @Test
    void shouldNotDetectConflictForSamePlayer() {

        RegisteredPlayer premiumPlayer = new RegisteredPlayer();
        premiumPlayer.setNickname("Notch");
        premiumPlayer.setPremiumUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        premiumPlayer.setConflictMode(false);

        UUID sameUuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        boolean result = handler.isNicknameConflict(premiumPlayer, true, true, sameUuid);

        assertFalse(result, "Should not detect conflict for same premium player");
    }

    @Test
    void shouldDetectConflictWhenOfflineAccessesConflictedAccount() {

        RegisteredPlayer conflictedPlayer = new RegisteredPlayer();
        conflictedPlayer.setNickname("TestPlayer");
        conflictedPlayer.setUuid(UUID.randomUUID().toString());
        conflictedPlayer.setConflictMode(true);

        boolean result = handler.isNicknameConflict(conflictedPlayer, false, false, null);

        assertTrue(result, "Should detect conflict for offline accessing conflicted account");
    }

    @ParameterizedTest(name = "shouldHandleUsernameCase: length={0}")
    @CsvSource({
            "3,   ABC",
            "16,  A234567890123456"
    })
    void shouldHandleBoundaryUsernameLengths(int expectedLength, String username) {

        boolean result = handler.isValidUsername(username);

        assertTrue(result, "Should accept username with length: " + expectedLength);
        assertEquals(expectedLength, username.length(), "Username length mismatch");
    }

    @Test
    void shouldAcceptFloodgatePrefixedUsernameAtExtendedBoundaryLength() {
        String username = ".A123456789012345";

        boolean result = handler.isValidUsername(username);

        assertTrue(result, "Should accept Floodgate username with 16-char base name");
        assertEquals(17, username.length(), "Floodgate-prefixed username length mismatch");
    }

    @Test
    void shouldHandlePremiumStatusWithStaleCache() {

        String username = "TestPlayer";
        UUID premiumUuid = UUID.randomUUID();
        long staleTimestamp = System.currentTimeMillis() - 700000L;
        PremiumCacheEntry staleEntry = new PremiumCacheEntry(true, premiumUuid, staleTimestamp, 600000L);

        when(authCache.getPremiumStatus(username)).thenReturn(staleEntry);

        PreLoginHandler.PremiumResolutionResult result = handler.resolvePremiumStatusAsync(username).join();
        assertNotNull(result, "Result should not be null");
        assertTrue(result.premium(), "Should return stale premium status");
        assertEquals(premiumUuid, result.premiumUuid(), "Should return stale UUID");

    }
}
