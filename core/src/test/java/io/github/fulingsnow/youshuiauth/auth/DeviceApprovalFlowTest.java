package io.github.fulingsnow.youshuiauth.auth;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.model.QqBinding;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.qq.CrossDeploymentClock;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.qq.QqNotify;
import io.github.fulingsnow.youshuiauth.qq.QqResult;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.util.PlatformFingerprints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceApprovalFlowTest {

    private static final UUID ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String QQ_ID = "10001";

    private DatabaseManager databaseManager;
    private QqDao dao;
    private QqSettings qqSettings;
    private Settings coreSettings;
    private AuthPlayer player;
    private ConnectionRef operation;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = mock(DatabaseManager.class);
        dao = mock(QqDao.class);
        qqSettings = mock(QqSettings.class);
        coreSettings = mock(Settings.class);

        when(qqSettings.isEnabled()).thenReturn(true);
        when(qqSettings.isDeviceApprovalEnabled()).thenReturn(true);
        when(qqSettings.getDeviceApprovalTimeoutSeconds()).thenReturn(120);
        when(coreSettings.isAutoLoginEnabled()).thenReturn(true);
        when(coreSettings.isAutoLoginRequireSameSubnet()).thenReturn(false);
        when(coreSettings.getTwoFactorSettings())
                .thenReturn(new Settings.TwoFactorSettings(false, "YouShuiAuth", false, 300));
        when(databaseManager.findRegisteredPlayerByUuid(ID)).thenReturn(new RegisteredPlayer());

        QqBinding binding = new QqBinding("qq", QQ_ID, ID.toString(), "Tester", 0L, "bind_code");
        when(dao.findByUuid(eq("qq"), anyString())).thenReturn(Optional.of(binding));
        when(dao.findByQq("qq", QQ_ID)).thenReturn(Optional.of(binding));

        player = mock(AuthPlayer.class);
        InetAddress address = InetAddress.getByName("203.0.113.8");
        when(player.uniqueId()).thenReturn(ID);
        when(player.username()).thenReturn("ApprovalTester");
        when(player.address()).thenReturn(address);
        when(player.virtualHost()).thenReturn(Optional.empty());
        when(player.clientBrand()).thenReturn("vanilla");
        when(player.protocolVersion()).thenReturn(776);
        when(player.settings()).thenReturn(Optional.empty());
        when(player.onlineMode()).thenReturn(false);

        operation = mock(ConnectionRef.class);
    }

    private DeviceApprovalFlow flow() {
        return new DeviceApprovalFlow(qqSettings, coreSettings, databaseManager, () -> dao,
                "paper", LoggerFactory.getLogger(DeviceApprovalFlowTest.class));
    }

    private String currentFingerprintHash() {
        String fingerprint = PlatformFingerprints.fingerprint(player, false, "paper");
        return PlatformFingerprints.hash(ID.toString(), fingerprint);
    }

    private void seedToken(String hash) throws java.sql.SQLException {
        long now = System.currentTimeMillis();
        when(dao.findUsableRememberTokenByUuidAndPlatform(eq(ID.toString()), anyLong(), eq("paper")))
                .thenReturn(Optional.of(new QqRememberToken("old", ID.toString(), now,
                        now + 86_400_000L)));
        when(dao.findRememberToken(hash)).thenReturn(
                Optional.of(new QqRememberToken(hash, ID.toString(), now, now + 86_400_000L)));
    }

    @Test
    void requestIsRejectedWithoutBindingOrFirstLoginOrMatchingFingerprint() throws Exception {
        when(dao.findByUuid(eq("qq"), anyString())).thenReturn(Optional.empty());
        assertFalse(flow().request(player, operation), "未绑定 QQ 时不适用");

        when(dao.findByUuid(eq("qq"), anyString())).thenReturn(
                Optional.of(new QqBinding("qq", QQ_ID, ID.toString(), "Tester", 0L, "bind_code")));
        when(dao.findUsableRememberTokenByUuidAndPlatform(eq(ID.toString()), anyLong(), eq("paper")))
                .thenReturn(Optional.empty());
        assertFalse(flow().request(player, operation), "首次登录（无设备记录）不适用");

        seedToken(currentFingerprintHash());
        assertFalse(flow().request(player, operation), "指纹匹配时应直接免密，无需确认");
    }

    @Test
    void mismatchingFingerprintCreatesApprovalAndNotification() throws Exception {
        seedToken("different-hash");
        DeviceApprovalFlow flow = flow();

        assertTrue(flow.request(player, operation), "指纹不匹配应发起确认");
        QqNotify notify = flow.pollNotification();
        assertNotNull(notify, "应生成待推送通知");
        assertEquals(QQ_ID, notify.qqId());
        assertEquals(QqNotify.TYPE_DEVICE_APPROVAL, notify.type());
        assertTrue(notify.message().contains("ApprovalTester"));
    }

    @Test
    void decideRejectsStrangerAndHonoursOwnerDecision() throws Exception {
        seedToken("different-hash");
        DeviceApprovalFlow flow = flow();
        assertTrue(flow.request(player, operation));
        QqNotify notify = flow.pollNotification();
        String approvalId = notify.approvalId();

        assertEquals(QqResult.FORBIDDEN, flow.decide("99999", "Y", approvalId).result(),
                "非绑定 QQ 的回复必须拒绝");

        DeviceApprovalFlow.DecisionOutcome denied = flow.decide(QQ_ID, "N", approvalId);
        assertEquals(QqResult.OK, denied.result());
        assertFalse(denied.approve());

        assertEquals(QqResult.NO_PENDING_APPROVAL, flow.decide(QQ_ID, "Y", approvalId).result(),
                "pending 应被一次性消费");
    }

    @Test
    void invalidDecisionIsReportedNotConsumed() throws Exception {
        seedToken("different-hash");
        DeviceApprovalFlow flow = flow();
        assertTrue(flow.request(player, operation));
        String approvalId = flow.pollNotification().approvalId();

        assertEquals(QqResult.INVALID_DECISION, flow.decide(QQ_ID, "maybe", approvalId).result());
        assertTrue(flow.isPending(ID), "无效回复不应消费 pending");
    }

    @Test
    void stopAutoLoginRevokesBoundAccountDevices() throws java.sql.SQLException {

        assertEquals(QqResult.NO_PENDING_APPROVAL,
                flow().stopAutoLogin("qq", "g", QQ_ID, "forged-id").result());

        DeviceApprovalFlow flow = flow();
        flow.notifyAutoLogin(player, "6 days");
        String stopId = flow.pollNotification().approvalId();

        DeviceApprovalFlow.StopOutcome outcome = flow.stopAutoLogin("qq", "g", QQ_ID, stopId);
        assertEquals(QqResult.OK, outcome.result());
        assertEquals(ID, outcome.playerId());
        org.mockito.Mockito.verify(dao).deleteRememberTokensByUuid(ID.toString());
    }
}
