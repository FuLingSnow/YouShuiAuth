package io.github.fulingsnow.youshuiauth.auth;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CredentialEpochServiceTest {

    private static final UUID ID = UUID.fromString("31313131-4242-5353-6464-e5e5e5e5e5e5");

    private DatabaseManager databaseManager;
    private AuthCache cache;
    private CredentialEpochService service;

    @BeforeEach
    void setUp() {
        databaseManager = mock(DatabaseManager.class);
        cache = mock(AuthCache.class);
        service = new CredentialEpochService(databaseManager, cache,
                LoggerFactory.getLogger(CredentialEpochServiceTest.class), 0L);
        when(databaseManager.findCredentialEpoch(ID))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        OptionalLong.of(0L)));
        when(databaseManager.incrementCredentialEpoch(ID))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1L));
    }

    @Test
    void unknownPlayerIsNeverStale() {
        assertFalse(service.isStale(ID));
        verify(cache, never()).invalidatePlayerData(any());
    }

    @Test
    void bumpAlignsLocalEpochAndSubsequentCheckPasses() {
        service.record(ID, 0L);
        assertTrue(service.bump(ID) == 1L, "bump 应返回新版本");
        when(databaseManager.findCredentialEpoch(ID))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        OptionalLong.of(1L)));
        assertFalse(service.isStale(ID), "bump 后本地与库内一致");
        verify(cache, never()).invalidatePlayerData(any());
    }

    @Test
    void advancedEpochOnAnotherDeploymentRevokesLocalSession() {
        service.record(ID, 0L);

        when(databaseManager.findCredentialEpoch(ID))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        OptionalLong.of(2L)));

        assertTrue(service.isStale(ID), "库内版本领先时本地会话必须失效");
        verify(cache).invalidatePlayerData(ID);
        verify(cache).endSession(ID);

        assertFalse(service.isStale(ID), "本地已对齐后不应再次撤销");
    }

    @Test
    void missingAccountIsTreatedAsNotStaleToSurviveDatabaseOutages() {
        service.record(ID, 0L);
        when(databaseManager.findCredentialEpoch(ID))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        OptionalLong.empty()));
        assertFalse(service.isStale(ID), "库不可达/账号缺失不应把玩家踢下线");
        verify(cache, never()).invalidatePlayerData(any());
    }
}
