package io.github.fulingsnow.youshuiauth.auth;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.qq.QqAuthFlow;
import io.github.fulingsnow.youshuiauth.util.PlatformFingerprints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoLoginServiceTest {

    private static final UUID ID = UUID.fromString("12121212-3434-5656-7878-909090909090");
    private static final String NAME = "AutoTester";
    private static final String IP = "203.0.113.5";

    private Settings settings;
    private AuthCache cache;
    private QqDao dao;
    private AuthPlayer player;

    @BeforeEach
    void setUp() throws Exception {
        settings = mock(Settings.class);
        cache = mock(AuthCache.class);
        dao = mock(QqDao.class);
        when(settings.isAutoLoginEnabled()).thenReturn(true);
        when(settings.isAutoLoginRequireSameSubnet()).thenReturn(false);

        when(settings.isAutoLoginPasswordEnabled()).thenReturn(true);
        var twoFactor = mock(Settings.TwoFactorSettings.class);
        when(twoFactor.isEnabled()).thenReturn(false);
        when(settings.getTwoFactorSettings()).thenReturn(twoFactor);
        when(dao.findByUuid(anyString(), anyString())).thenReturn(Optional.empty());

        player = mock(AuthPlayer.class);
        InetAddress address = InetAddress.getByName(IP);
        when(player.uniqueId()).thenReturn(ID);
        when(player.username()).thenReturn(NAME);
        when(player.address()).thenReturn(address);
        when(player.virtualHost()).thenReturn(Optional.empty());
        when(player.clientBrand()).thenReturn("vanilla");
        when(player.protocolVersion()).thenReturn(776);
        when(player.settings()).thenReturn(Optional.empty());
        when(player.onlineMode()).thenReturn(false);
    }

    private AutoLoginService service() {
        var databaseManager = mock(io.github.fulingsnow.youshuiauth.database.DatabaseManager.class);
        var account = new io.github.fulingsnow.youshuiauth.model.RegisteredPlayer(
                NAME, "hash", IP, ID.toString());
        when(databaseManager.findRegisteredPlayerByUuid(ID)).thenReturn(account);
        var messages = mock(io.github.fulingsnow.youshuiauth.i18n.Messages.class);
        when(messages.component(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(net.kyori.adventure.text.Component.empty());
        return new AutoLoginService(settings, cache, () -> dao, databaseManager, "paper",
                LoggerFactory.getLogger(AutoLoginServiceTest.class), messages, null,
                mock(io.github.fulingsnow.youshuiauth.platform.TimeoutOps.class));
    }

    private QqRememberToken tokenWithHash(String hash) {
        long now = System.currentTimeMillis();
        return new QqRememberToken(hash, ID.toString(), now, now + 86_400_000L);
    }

    @Test
    void disabledOrPremiumOrMissingRecordDoesNotRestore() throws Exception {
        when(settings.isAutoLoginEnabled()).thenReturn(false);
        assertFalse(service().attempt(player).authorized());

        when(settings.isAutoLoginEnabled()).thenReturn(true);
        when(player.onlineMode()).thenReturn(true);
        assertFalse(service().attempt(player).authorized(), "正版连接不应走设备免密");

        when(player.onlineMode()).thenReturn(false);
        when(dao.findUsableRememberTokenByUuidAndPlatform(eq(ID.toString()), anyLong(), eq("paper")))
                .thenReturn(Optional.empty());
        assertFalse(service().attempt(player).authorized(), "无设备记录时不应放行");
        verify(cache, never()).authorize(any(), any(), any(), any());
    }

    @Test
    void matchingFingerprintRestoresAuthorization() throws Exception {
        String fingerprint = PlatformFingerprints.fingerprint(player, false, "paper");
        String hash = PlatformFingerprints.hash(ID.toString(), fingerprint);
        QqRememberToken token = tokenWithHash(hash);
        when(dao.findUsableRememberTokenByUuidAndPlatform(eq(ID.toString()), anyLong(), eq("paper")))
                .thenReturn(Optional.of(token));
        when(dao.touchRememberToken(token)).thenReturn(true);

        assertTrue(service().attempt(player).authorized());
        verify(cache).authorize(eq(ID), any(CachedAuthUser.class), eq(NAME), eq(IP));
        verify(dao).touchRememberToken(token);
    }

    @Test
    void differentFingerprintIsRejected() throws Exception {
        when(dao.findUsableRememberTokenByUuidAndPlatform(eq(ID.toString()), anyLong(), eq("paper")))
                .thenReturn(Optional.of(tokenWithHash("deadbeef")));

        assertFalse(service().attempt(player).authorized(), "指纹不匹配时必须要求重新认证");
        verify(cache, never()).authorize(any(), any(), any(), any());
    }

    @Test
    void deviceMismatchIsDistinguishedFromMissingRecord() throws Exception {

        when(dao.findUsableRememberTokenByUuidAndPlatform(eq(ID.toString()), anyLong(), eq("paper")))
                .thenReturn(Optional.of(tokenWithHash("deadbeef")));
        assertEquals(AutoLoginService.Outcome.DEVICE_MISMATCH,
                service().attempt(player).outcome());

        when(dao.findUsableRememberTokenByUuidAndPlatform(eq(ID.toString()), anyLong(), eq("paper")))
                .thenReturn(Optional.empty());
        assertEquals(AutoLoginService.Outcome.NO_MATCHING_DEVICE,
                service().attempt(player).outcome());
    }
}
