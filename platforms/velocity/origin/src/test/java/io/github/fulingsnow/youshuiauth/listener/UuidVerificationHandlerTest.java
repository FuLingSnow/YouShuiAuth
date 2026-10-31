package io.github.fulingsnow.youshuiauth.listener;

import com.velocitypowered.api.proxy.Player;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.auth.ConflictModeService;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager.DbResult;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UuidVerificationHandler} — security-critical UUID verification
 * for cracked (offline-mode) players against the AUTH table.
 *
 * <p>Security invariants under test:
 * <ul>
 *   <li>Premium (online-mode) players skip verification entirely — no DB round-trip</li>
 *   <li>Database error is fail-secure: verification fails AND the session is invalidated
 *       (distinct from "player not found", which fails without an error path)</li>
 *   <li>UUID mismatch denies access, invalidates cache/session and emits an audit event</li>
 *   <li>CONFLICT_MODE allows access despite a UUID mismatch (conflict resolution window)</li>
 *   <li>Any unexpected exception resolves to {@code false} (fail-secure), never propagates</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UuidVerificationHandlerTest {

    private static final String USERNAME = "CrackedSteve";
    private static final String PLAYER_IP = "192.0.2.77";

    @Mock
    private DatabaseManager databaseManager;

    @Mock
    private AuthCache authCache;

    @Mock
    private Logger logger;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private Player player;

    private UuidVerificationHandler handler;

    private UUID playerUuid;

    /** Conflict TTL in hours used by the handler under test. Non-zero so the TTL-expiry
     *  path is exercised; matches the production default of 168h (7 days). */
    private static final int CONFLICT_TTL_HOURS = 168;

    @BeforeEach
    void setUp() {
        playerUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getUsername()).thenReturn(USERNAME);
        when(player.isOnlineMode()).thenReturn(false);
        when(player.getRemoteAddress()).thenReturn(new InetSocketAddress(PLAYER_IP, 25565));

        handler = new UuidVerificationHandler(databaseManager, authCache, logger, () -> auditLogService,
                new ConflictModeService(databaseManager, CONFLICT_TTL_HOURS));
    }

    @Test
    void testVerifyPlayerUuid_OnlineModePremiumPlayer_SkipsDatabaseAndReturnsTrue() {

        when(player.isOnlineMode()).thenReturn(true);

        boolean result = handler.verifyPlayerUuid(player).join();

        assertTrue(result, "Premium players are Mojang-verified - verification must pass");
        verify(databaseManager, never()).findPlayerByNickname(anyString());
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
    }

    @Test
    void testVerifyPlayerUuid_DatabaseError_FailSecureReturnsFalseAndInvalidatesSession() {

        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.databaseError("connection refused")));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result, "DB error must never be treated as a successful verification");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
        verify(authCache).endSession(playerUuid);
    }

    @Test
    void testVerifyPlayerUuid_PlayerNotRegistered_ReturnsFalseWithoutErrorHandling() {

        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(null)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result, "Unregistered cracked player must not pass UUID verification");
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
        verify(authCache, never()).endSession(any(UUID.class));
    }

    @Test
    void testVerifyPlayerUuid_UuidMatchesStoredUuid_ReturnsTrue() {

        RegisteredPlayer dbPlayer = registeredPlayer(playerUuid.toString(), null, false);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertTrue(result, "Connection UUID matching the stored UUID must pass");
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
    }

    @Test
    void testVerifyPlayerUuid_UuidMatchesStoredPremiumUuid_ReturnsTrue() {

        RegisteredPlayer dbPlayer =
                registeredPlayer(UUID.randomUUID().toString(), playerUuid.toString(), false);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertTrue(result, "Connection UUID matching PREMIUMUUID must pass");
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
    }

    @Test
    void testVerifyPlayerUuid_UuidMismatch_ReturnsFalseInvalidatesCacheAndAudits() {

        RegisteredPlayer dbPlayer =
                registeredPlayer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), false);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result, "UUID mismatch on a cracked connection must be denied");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
        verify(authCache).endSession(playerUuid);
        verify(auditLogService).save(
                eq(AuditEventType.UUID_MISMATCH), eq(USERNAME), eq(PLAYER_IP), anyString());
    }

    @Test
    void testVerifyPlayerUuid_UuidMismatchWithLegacyConstructor_NoAuditServiceStillDenies() {

        handler = new UuidVerificationHandler(databaseManager, authCache, logger);
        RegisteredPlayer dbPlayer =
                registeredPlayer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), false);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result, "Missing audit service must not weaken the deny decision");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
        verify(authCache).endSession(playerUuid);
    }

    @Test
    void testVerifyPlayerUuid_NullAuditSupplier_DefaultsToNoAuditAndStillDenies() {

        handler = new UuidVerificationHandler(databaseManager, authCache, logger, null);
        RegisteredPlayer dbPlayer =
                registeredPlayer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), false);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result, "Null audit supplier must not break fail-secure mismatch handling");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
    }

    @Test
    void testVerifyPlayerUuid_ConflictModeWithUuidMismatch_AllowsAccess() {

        RegisteredPlayer dbPlayer =
                registeredPlayer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), true);
        dbPlayer.setConflictTimestamp(System.currentTimeMillis());
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertTrue(result, "CONFLICT_MODE must allow access despite UUID mismatch within TTL");
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
        verify(authCache, never()).endSession(any(UUID.class));
    }

    @Test
    void testVerifyPlayerUuid_ConflictModeWithMatchingUuid_AllowsAccess() {

        RegisteredPlayer dbPlayer = registeredPlayer(playerUuid.toString(), null, true);
        dbPlayer.setConflictTimestamp(System.currentTimeMillis());
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertTrue(result, "CONFLICT_MODE with a matching UUID must allow access");
    }

    @Test
    void testVerifyPlayerUuid_ConflictModeExpired_PerformsFullUuidVerificationAndDenies() {

        RegisteredPlayer dbPlayer =
                registeredPlayer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), true);
        long expiredMillis = System.currentTimeMillis()
                - (CONFLICT_TTL_HOURS * 60L * 60L * 1000L) - 60_000L;
        dbPlayer.setConflictTimestamp(expiredMillis);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result,
                "An expired CONFLICT_MODE entry must NOT relax UUID verification — full check applies");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
        verify(authCache).endSession(playerUuid);
        verify(auditLogService).save(
                eq(AuditEventType.UUID_MISMATCH), eq(USERNAME), eq(PLAYER_IP), anyString());
    }

    @Test
    void testVerifyPlayerUuid_ConflictModeExpiredButUuidMatches_AllowsAccess() {

        RegisteredPlayer dbPlayer = registeredPlayer(playerUuid.toString(), null, true);
        long expiredMillis = System.currentTimeMillis()
                - (CONFLICT_TTL_HOURS * 60L * 60L * 1000L) - 60_000L;
        dbPlayer.setConflictTimestamp(expiredMillis);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertTrue(result,
                "Expired CONFLICT_MODE with a matching UUID must pass full verification");
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
    }

    @Test
    void testVerifyPlayerUuid_ConflictModePermanent_TtlDisabledAllowsDespiteMismatch() {

        handler = new UuidVerificationHandler(databaseManager, authCache, logger, () -> auditLogService,
                new ConflictModeService(databaseManager, 0));
        RegisteredPlayer dbPlayer =
                registeredPlayer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), true);

        dbPlayer.setConflictTimestamp(0L);
        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.completedFuture(DbResult.success(dbPlayer)));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertTrue(result,
                "With conflict-mode-ttl-hours=0 (disabled), conflict mode must be permanent (legacy)");
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
    }

    @Test
    void testVerifyPlayerUuid_DbFutureCompletesExceptionally_FailSecureReturnsFalse() {

        when(databaseManager.findPlayerByNickname(USERNAME)).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("pool exhausted")));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result, "An async DB failure must resolve to false, never propagate");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
        verify(authCache).endSession(playerUuid);
    }

    @Test
    void testVerifyPlayerUuid_OnlineModeCheckThrows_FailSecureReturnsFalse() {

        when(player.isOnlineMode()).thenThrow(new IllegalStateException("connection closed"));

        boolean result = handler.verifyPlayerUuid(player).join();

        assertFalse(result, "A synchronous RuntimeException must resolve to false");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
        verify(authCache).endSession(playerUuid);
    }

    @Test
    void testPerformUuidVerification_NullDbPlayer_ReturnsFalse() {

        boolean result = handler.performUuidVerification(player, null);

        assertFalse(result, "Null DB record must fail verification");
        verify(authCache, never()).removeAuthorizedPlayer(any(UUID.class));
    }

    @Test
    void testPerformUuidVerification_MalformedStoredUuids_TreatedAsMismatchReturnsFalse() {

        RegisteredPlayer dbPlayer = org.mockito.Mockito.mock(RegisteredPlayer.class);
        when(dbPlayer.getUuid()).thenReturn("not-a-uuid");
        when(dbPlayer.getPremiumUuid()).thenReturn("also-not-a-uuid");
        when(dbPlayer.getConflictMode()).thenReturn(false);

        boolean result = handler.performUuidVerification(player, dbPlayer);

        assertFalse(result, "Malformed stored UUIDs must be treated as a mismatch (fail-secure)");
        verify(authCache).removeAuthorizedPlayer(playerUuid);
        verify(authCache).endSession(playerUuid);
    }

    @Test
    void testPerformUuidVerification_NullStoredUuidsWithoutConflictMode_ReturnsFalse() {

        RegisteredPlayer dbPlayer = registeredPlayer(null, null, false);

        boolean result = handler.performUuidVerification(player, dbPlayer);

        assertFalse(result, "A record without any stored UUID must not match any connection");
    }

    private static RegisteredPlayer registeredPlayer(String uuid, String premiumUuid, boolean conflictMode) {
        RegisteredPlayer dbPlayer = new RegisteredPlayer();
        dbPlayer.setNickname(USERNAME);
        if (uuid != null) {

            dbPlayer.setUuid(uuid);
        }
        dbPlayer.setPremiumUuid(premiumUuid);
        dbPlayer.setConflictMode(conflictMode);
        return dbPlayer;
    }
}
