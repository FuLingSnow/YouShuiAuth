package io.github.fulingsnow.youshuiauth.listener;

import com.velocitypowered.api.proxy.Player;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.cache.AuthCache.PremiumCacheEntry;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PremiumAuthorizer} — shared premium authorization path
 * used by both AuthListener (cache-expiry refresh) and PostLoginHandler (initial PostLogin).
 *
 * <p>Security-critical invariants under test:
 * <ul>
 *   <li>The cached premium UUID (from the premium-status cache) wins when present</li>
 *   <li>Fallback to the Mojang-verified connection UUID when no cache entry exists</li>
 *   <li>The resulting {@link CachedAuthUser} is always marked premium and registered
 *       under the connection UUID</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PremiumAuthorizerTest {

    private static final String USERNAME = "PremiumSteve";
    private static final String PLAYER_IP = "192.0.2.10";
    private static final long CACHE_TTL_MILLIS = 60_000L;
    private static final double REFRESH_THRESHOLD = 0.8;

    @Mock
    private Player player;

    @Mock
    private AuthCache authCache;

    private UUID connectionUuid;

    @BeforeEach
    void setUp() {
        connectionUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(connectionUuid);
        when(player.getUsername()).thenReturn(USERNAME);
    }

    @Test
    void testAuthorize_PremiumCacheEntryPresent_UsesCachedPremiumUuid() {

        UUID cachedPremiumUuid = UUID.randomUUID();
        PremiumCacheEntry entry =
                new PremiumCacheEntry(true, cachedPremiumUuid, CACHE_TTL_MILLIS, REFRESH_THRESHOLD);
        when(authCache.getPremiumStatus(USERNAME)).thenReturn(entry);

        PremiumAuthorizer.authorize(player, PLAYER_IP, authCache);

        CachedAuthUser cachedUser = captureAuthorizedUser();
        assertEquals(cachedPremiumUuid, cachedUser.getPremiumUuid(),
                "Premium UUID must come from the premium-status cache when present");
        assertNotEquals(connectionUuid, cachedUser.getPremiumUuid(),
                "Cached premium UUID must not be overwritten by the connection UUID");
    }

    @Test
    void testAuthorize_NoPremiumCacheEntry_FallsBackToConnectionUuid() {

        when(authCache.getPremiumStatus(USERNAME)).thenReturn(null);

        PremiumAuthorizer.authorize(player, PLAYER_IP, authCache);

        CachedAuthUser cachedUser = captureAuthorizedUser();
        assertEquals(connectionUuid, cachedUser.getPremiumUuid(),
                "Without a cache entry the Mojang-verified connection UUID is authoritative");
    }

    @Test
    void testAuthorize_CacheEntryWithNullPremiumUuid_FallsBackToConnectionUuid() {

        PremiumCacheEntry entryWithoutUuid =
                new PremiumCacheEntry(true, null, CACHE_TTL_MILLIS, REFRESH_THRESHOLD);
        when(authCache.getPremiumStatus(USERNAME)).thenReturn(entryWithoutUuid);

        PremiumAuthorizer.authorize(player, PLAYER_IP, authCache);

        CachedAuthUser cachedUser = captureAuthorizedUser();
        assertEquals(connectionUuid, cachedUser.getPremiumUuid(),
                "Null premium UUID in cache entry must fall back to the connection UUID");
    }

    @Test
    void testAuthorize_PremiumPlayer_RegistersPremiumUserUnderConnectionUuid() {

        when(authCache.getPremiumStatus(USERNAME)).thenReturn(null);

        PremiumAuthorizer.authorize(player, PLAYER_IP, authCache);

        ArgumentCaptor<CachedAuthUser> userCaptor = ArgumentCaptor.forClass(CachedAuthUser.class);
        verify(authCache).authorize(eq(connectionUuid), userCaptor.capture(), eq(USERNAME), eq(PLAYER_IP));

        CachedAuthUser cachedUser = userCaptor.getValue();
        assertEquals(connectionUuid, cachedUser.getUuid(), "Cache key UUID must be the connection UUID");
        assertEquals(USERNAME, cachedUser.getNickname(), "Nickname must match the connecting player");
        assertEquals(PLAYER_IP, cachedUser.getLoginIp(), "Login IP must be recorded for session checks");
        assertTrue(cachedUser.isPremium(), "PremiumAuthorizer must always produce a premium-flagged user");
    }

    private CachedAuthUser captureAuthorizedUser() {
        ArgumentCaptor<CachedAuthUser> userCaptor = ArgumentCaptor.forClass(CachedAuthUser.class);
        verify(authCache).authorize(eq(connectionUuid), userCaptor.capture(), any(), any());
        return userCaptor.getValue();
    }
}
