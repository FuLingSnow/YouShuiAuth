package io.github.fulingsnow.youshuiauth.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.model.QqBinding;
import io.github.fulingsnow.youshuiauth.model.QqLinkCode;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;
import io.github.fulingsnow.youshuiauth.platform.ConnectionOps;
import io.github.fulingsnow.youshuiauth.platform.PlatformAccess;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.qq.QqAuthFlow;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.qq.QqResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

class QqAccountFlowTest {

    private static final String PLATFORM = "qq";
    private static final String GROUP = "851342671";
    private static final String QQ_ID = "10001";
    private static final String CODE = "ABCD1234";
    private static final UUID PLAYER_ID = UUID.randomUUID();

    private AuthServices services;
    private QqOps qq;
    private DatabaseManager databaseManager;
    private QqDao dao;
    private Settings settings;
    private PlatformAccess platformAccess;
    private QqAccountFlow flow;

    @BeforeEach
    void setUp() {
        services = mock(AuthServices.class);
        qq = mock(QqOps.class);
        databaseManager = mock(DatabaseManager.class);
        dao = mock(QqDao.class);
        settings = mock(Settings.class);
        platformAccess = mock(PlatformAccess.class);
        when(services.qq()).thenReturn(qq);
        when(services.databaseManager()).thenReturn(databaseManager);
        when(services.settings()).thenReturn(settings);
        when(services.authCache()).thenReturn(mock(AuthCache.class));
        when(services.messages()).thenReturn(mock(Messages.class));
        when(services.connections()).thenReturn(mock(ConnectionOps.class));
        when(services.platformAccess()).thenReturn(platformAccess);
        when(databaseManager.getQqDao()).thenReturn(dao);
        when(qq.isEnabled()).thenReturn(true);
        when(settings.getTwoFactorSettings()).thenReturn(mock(Settings.TwoFactorSettings.class));
        flow = new QqAccountFlow(services);
    }

    @Test
    void disabledFeatureReturnsDisabled() {
        when(qq.isEnabled()).thenReturn(false);
        assertEquals(QqResult.DISABLED, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
        assertEquals(QqResult.DISABLED, flow.confirmLogin(PLATFORM, GROUP, QQ_ID, CODE));
    }

    @Test
    void blankCodeIsRejectedBeforeTouchingDao() throws Exception {
        assertEquals(QqResult.INVALID_CODE, flow.bind(PLATFORM, GROUP, QQ_ID, "  ", "nick"));
        verify(dao, never()).consumeLinkCode(anyString(), anyLong());
    }

    @Test
    void unknownCodeIsRejected() throws Exception {
        when(dao.findLinkCode(anyString())).thenReturn(Optional.empty());
        assertEquals(QqResult.INVALID_CODE, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
    }

    @Test
    void consumedCodeIsRejected() throws Exception {
        long now = System.currentTimeMillis();
        QqLinkCode consumed = new QqLinkCode("hash", PLAYER_ID.toString(),
                QqLinkCode.PURPOSE_BIND, now, now + 300_000L);
        try {
            var consumedAtField = QqLinkCode.class.getDeclaredField("consumedAt");
            consumedAtField.setAccessible(true);
            consumedAtField.set(consumed, System.currentTimeMillis());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(dao.findLinkCode(anyString())).thenReturn(Optional.of(consumed));
        assertEquals(QqResult.INVALID_CODE, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
    }

    @Test
    void expiredCodeReportsCodeExpired() throws Exception {
        long now = System.currentTimeMillis();
        when(dao.findLinkCode(anyString())).thenReturn(Optional.of(
                new QqLinkCode("hash", PLAYER_ID.toString(), QqLinkCode.PURPOSE_BIND,
                        now - 10_000L, now - 5_000L)));
        assertEquals(QqResult.CODE_EXPIRED, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
    }

    @Test
    void alreadyBoundQqIdIsRejectedBeforeConsuming() throws Exception {
        when(dao.findLinkCode(anyString())).thenReturn(Optional.of(freshCode(QqLinkCode.PURPOSE_BIND)));
        when(dao.findByQq(PLATFORM, QQ_ID)).thenReturn(Optional.of(
                new QqBinding(PLATFORM, QQ_ID, PLAYER_ID.toString(), "nick", 0L, "bind_code")));
        assertEquals(QqResult.ALREADY_BOUND, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
        verify(dao, never()).consumeLinkCode(anyString(), anyLong());
    }

    @Test
    void uuidBoundToOtherQqIsRejected() throws Exception {
        when(dao.findLinkCode(anyString())).thenReturn(Optional.of(freshCode(QqLinkCode.PURPOSE_BIND)));
        when(dao.findByUuid(PLATFORM, PLAYER_ID.toString())).thenReturn(Optional.of(
                new QqBinding(PLATFORM, "99999", PLAYER_ID.toString(), "nick", 0L, "bind_code")));
        assertEquals(QqResult.UUID_BOUND_TO_OTHER, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
    }

    @Test
    void existingPasswordAccountBlocksRegistrationWithoutCreatingOne() throws Exception {
        when(dao.findLinkCode(anyString()))
                .thenReturn(Optional.of(freshCode(QqLinkCode.PURPOSE_REGISTER)));
        when(dao.findByQq(PLATFORM, QQ_ID)).thenReturn(Optional.empty());
        when(dao.findByUuid(PLATFORM, PLAYER_ID.toString())).thenReturn(Optional.empty());
        when(dao.consumeLinkCode(anyString(), anyLong())).thenReturn(true);

        AuthPlayer player = mock(AuthPlayer.class);
        when(player.isActive()).thenReturn(true);
        when(player.uniqueId()).thenReturn(PLAYER_ID);
        when(player.username()).thenReturn("nick");
        when(player.address()).thenReturn(null);
        when(platformAccess.player(PLAYER_ID)).thenReturn(Optional.of(player));
        when(databaseManager.findRegisteredPlayerByUuid(PLAYER_ID)).thenReturn(
                new RegisteredPlayer("nick", "$2a$10$hash", "127.0.0.1", PLAYER_ID.toString()));

        assertEquals(QqResult.ACCOUNT_EXISTS, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
        verify(databaseManager, never()).registerPlayerIfAbsent(any());
    }

    @Test
    void registerWithoutOnlinePlayerIsRejected() throws Exception {
        when(dao.findLinkCode(anyString()))
                .thenReturn(Optional.of(freshCode(QqLinkCode.PURPOSE_REGISTER)));
        when(dao.findByQq(PLATFORM, QQ_ID)).thenReturn(Optional.empty());
        when(dao.findByUuid(PLATFORM, PLAYER_ID.toString())).thenReturn(Optional.empty());
        when(dao.consumeLinkCode(anyString(), anyLong())).thenReturn(true);
        when(platformAccess.player(PLAYER_ID)).thenReturn(Optional.empty());

        assertEquals(QqResult.NOT_ONLINE, flow.bind(PLATFORM, GROUP, QQ_ID, CODE, "nick"));
        verify(databaseManager, never()).registerPlayerIfAbsent(any());
    }

    @Test
    void unknownLoginCodeIsRejected() {
        when(qq.consumeLoginCode(CODE)).thenReturn(Optional.empty());
        assertEquals(QqResult.INVALID_CODE, flow.confirmLogin(PLATFORM, GROUP, QQ_ID, CODE));
    }

    @Test
    void qqOnlyHashMatchesCoreContract() {
        assertTrue("!qq-only".equals(QqAuthFlow.QQ_ONLY_HASH));
    }

    private QqLinkCode freshCode(String purpose) {
        long now = System.currentTimeMillis();
        return new QqLinkCode("hash", PLAYER_ID.toString(), purpose, now, now + 300_000L);
    }
}
