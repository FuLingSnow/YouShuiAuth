package io.github.fulingsnow.youshuiauth.qq;

import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.platform.TimeoutOps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QqAuthFlowTest {

    private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private QqDao dao;
    private QqSettings qqSettings;
    private Settings coreSettings;
    private TimeoutOps timeouts;
    private io.github.fulingsnow.youshuiauth.database.DatabaseManager databaseManager;
    private io.github.fulingsnow.youshuiauth.cache.AuthCache authCache;
    private AuthPlayer player;

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(QqDao.class);
        qqSettings = mock(QqSettings.class);
        coreSettings = mock(Settings.class);
        timeouts = mock(TimeoutOps.class);
        authCache = mock(io.github.fulingsnow.youshuiauth.cache.AuthCache.class);

        when(qqSettings.isEnabled()).thenReturn(true);
        when(qqSettings.getBindCodeTtlSeconds()).thenReturn(300);
        when(qqSettings.getLoginCodeTtlSeconds()).thenReturn(120);
        when(qqSettings.getDeviceApprovalTimeoutSeconds()).thenReturn(120);
        when(qqSettings.getJoinUrl()).thenReturn("https://qq.example/join");
        when(dao.findByUuid(anyString(), anyString())).thenReturn(Optional.empty());

        databaseManager = mock(io.github.fulingsnow.youshuiauth.database.DatabaseManager.class);
        when(databaseManager.findPlayerByNickname(anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        io.github.fulingsnow.youshuiauth.database.DatabaseManager.DbResult.success(null)));

        player = mock(AuthPlayer.class);
        InetAddress address = InetAddress.getByName("203.0.113.9");
        when(player.uniqueId()).thenReturn(ID);
        when(player.username()).thenReturn("QqTester");
        when(player.address()).thenReturn(address);
        when(player.virtualHost()).thenReturn(Optional.empty());
        when(player.clientBrand()).thenReturn("vanilla");
        when(player.protocolVersion()).thenReturn(776);
        when(player.settings()).thenReturn(Optional.empty());
    }

    private QqAuthFlow flow() {
        return new QqAuthFlow(qqSettings, coreSettings, () -> dao, timeouts,
                LoggerFactory.getLogger(QqAuthFlowTest.class), "paper",
                authCache, databaseManager);
    }

    @Test
    void disabledWhenSettingsOff() {
        when(qqSettings.isEnabled()).thenReturn(false);
        assertFalse(flow().isEnabled());
        assertEquals(QqResult.DISABLED, flow().createBindCodeOutcome(player).result());
    }

    @Test
    void issuesBindCodeAndReusesItUntilCooldown() throws Exception {
        QqAuthFlow flow = flow();
        QqOps.BindCodeIssue first = flow.createBindCodeOutcome(player);

        assertEquals(QqResult.OK, first.result());
        assertNotNull(first.code());
        assertEquals(8, first.code().length());
        assertEquals(300, first.ttlSeconds());

        assertTrue(first.createsAccount());
        verify(dao).insertLinkCode(any());
        verify(timeouts).renewForCode(eq(player), anyInt());

        assertEquals(QqResult.RATE_LIMITED, flow.createBindCodeOutcome(player).result(),
                "冷却期内的重复请求应被限流");
    }

    @Test
    void authenticatedPlayerGetsBindCodeInsteadOfRegisterCode() throws Exception {
        when(authCache.isPlayerAuthorized(any(java.util.UUID.class), anyString()))
                .thenReturn(true);
        QqAuthFlow flow = flow();

        QqOps.BindCodeIssue issue = flow.createBindCodeOutcome(player);

        assertEquals(QqResult.OK, issue.result());
        assertFalse(issue.createsAccount(), "已认证玩家应发普通 BIND 码");
    }

    @Test
    void alreadyBoundAccountGetsNoCode() throws Exception {
        when(dao.findByUuid(anyString(), anyString()))
                .thenReturn(Optional.of(mock(io.github.fulingsnow.youshuiauth.model.QqBinding.class)));
        QqAuthFlow flow = flow();

        assertEquals(QqResult.ALREADY_BOUND, flow.createBindCodeOutcome(player).result());
        assertTrue(flow.isAccountQqBound(player));
        verify(dao, never()).insertLinkCode(any());
    }

    @Test
    void rememberDeviceSkipsOnlineModeAndWritesForOffline() throws Exception {
        when(coreSettings.isAutoLoginRequireSameSubnet()).thenReturn(false);
        when(coreSettings.getAutoLoginMaxAgeDays()).thenReturn(7);
        QqAuthFlow flow = flow();

        when(player.onlineMode()).thenReturn(true);
        flow.rememberDevice(player);
        verify(dao, never()).insertRememberToken(any());

        when(player.onlineMode()).thenReturn(false);
        flow.rememberDevice(player);
        verify(dao, times(1)).deleteRememberTokensByUuidAndPlatform(ID.toString(), "paper");
        verify(dao, times(1)).insertRememberToken(any(QqRememberToken.class));
    }

    @Test
    void revokeRememberTokensDeletesStoredRecord() throws Exception {
        flow().revokeRememberTokens(ID);
        verify(dao).deleteRememberTokensByUuid(ID.toString());
    }
}
