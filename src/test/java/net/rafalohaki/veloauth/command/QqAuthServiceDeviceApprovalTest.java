package net.rafalohaki.veloauth.command;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import net.rafalohaki.veloauth.VeloAuth;
import net.rafalohaki.veloauth.config.Settings;
import net.rafalohaki.veloauth.connection.AuthTimeoutScheduler;
import net.rafalohaki.veloauth.database.DatabaseManager;
import net.rafalohaki.veloauth.i18n.Messages;
import net.rafalohaki.veloauth.lifecycle.ConnectionLifecycleRegistry;
import net.rafalohaki.veloauth.model.QqBinding;
import net.rafalohaki.veloauth.model.QqRememberToken;
import net.rafalohaki.veloauth.model.RegisteredPlayer;
import net.rafalohaki.veloauth.qq.QqDao;
import net.rafalohaki.veloauth.qq.QqNotify;
import net.rafalohaki.veloauth.qq.QqResult;
import net.rafalohaki.veloauth.qq.QqSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the new-device approval decision entry points: a disabled feature, malformed replies,
 * replies without a pending request, and — most importantly — replies from a QQ that does not
 * own the binding (the pending request must survive a rejected reply).
 */
class QqAuthServiceDeviceApprovalTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String OWNER_QQ = "10001";
    private static final String APPROVAL_ID = "approval-token-1";

    private VeloAuth plugin;
    private CommandContext ctx;
    private Settings settings;
    private DatabaseManager databaseManager;
    private QqDao dao;
    private ConnectionLifecycleRegistry lifecycleRegistry;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(VeloAuth.class);
        ctx = mock(CommandContext.class);
        settings = mock(Settings.class);
        databaseManager = mock(DatabaseManager.class);
        dao = mock(QqDao.class);
        lifecycleRegistry = new ConnectionLifecycleRegistry();
        playerUuid = UUID.randomUUID();

        when(ctx.settings()).thenReturn(settings);
        when(ctx.messages()).thenReturn(mock(Messages.class));
        when(ctx.databaseManager()).thenReturn(databaseManager);
        when(databaseManager.getQqDao()).thenReturn(dao);
        when(settings.isAutoLoginEnabled()).thenReturn(true);
        when(settings.getQqSettings()).thenReturn(qqSettings(true));
        when(settings.isAutoLoginRequireSameSubnet()).thenReturn(false);
        when(settings.getTwoFactorSettings())
                .thenReturn(new Settings.TwoFactorSettings(false, "YouShuiAuth", false, 300));
        when(databaseManager.findPlayerByNickname(anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        DatabaseManager.DbResult.success(account())));
        when(dao.findByUuid("qq", playerUuid.toString()))
                .thenReturn(Optional.of(binding(OWNER_QQ)));
        when(dao.findUsableRememberTokenByUuid(anyString(), anyLong()))
                .thenReturn(Optional.of(rememberToken(playerUuid.toString(), "stored")));
        when(dao.findRememberToken(anyString())).thenReturn(Optional.empty());
        when(plugin.getConnectionLifecycleRegistry()).thenReturn(lifecycleRegistry);
        when(plugin.getAuthTimeoutScheduler()).thenReturn(mock(AuthTimeoutScheduler.class));
        stubScheduler();
    }

    @Test
    void disabledFeatureReturnsDisabled() {
        when(settings.getQqSettings()).thenReturn(qqSettings(false));

        assertEquals(QqResult.DISABLED, service().decideDeviceApproval(
                "qq", "123456", OWNER_QQ, "Y", APPROVAL_ID));
    }

    @Test
    void malformedDecisionReturnsInvalidDecision() {
        assertEquals(QqResult.INVALID_DECISION, service().decideDeviceApproval(
                "qq", "123456", OWNER_QQ, "maybe", APPROVAL_ID));
    }

    @Test
    void blankApprovalIdReturnsInvalidCode() {
        assertEquals(QqResult.INVALID_CODE, service().decideDeviceApproval(
                "qq", "123456", OWNER_QQ, "Y", " "));
    }

    @Test
    void blankQqIdReturnsInvalidCode() {
        assertEquals(QqResult.INVALID_CODE, service().decideDeviceApproval(
                "qq", "123456", "  ", "Y", APPROVAL_ID));
    }

    @Test
    void replyWithoutPendingRequestReturnsNoPendingApproval() {
        assertEquals(QqResult.NO_PENDING_APPROVAL, service().decideDeviceApproval(
                "qq", "123456", OWNER_QQ, "Y", APPROVAL_ID));
    }

    @Test
    void stopWithoutTokenReturnsNoPendingApproval() {
        assertEquals(QqResult.NO_PENDING_APPROVAL, service().stopAutoLogin(
                "qq", "123456", OWNER_QQ, "missing-token"));
    }

    @Test
    void nonOwnerCannotStopAndOwnerRevokesDeviceTrust() throws Exception {
        QqAuthService service = service();
        service.queueAutoLoginNotify(player(), "1 天");
        String stopId = service.pollNotifications().stream()
                .filter(notify -> QqNotify.TYPE_AUTO_LOGIN.equals(notify.type()))
                .map(QqNotify::approvalId)
                .findFirst()
                .orElseThrow();

        // A stranger quoting the notification must not consume the request...
        assertEquals(QqResult.FORBIDDEN, service.stopAutoLogin(
                "qq", "123456", "99999", stopId));
        // ...and the owner can still stop afterwards (player offline: revoke-only path).
        assertEquals(QqResult.OK, service.stopAutoLogin(
                "qq", "123456", OWNER_QQ, stopId));
        assertEquals(QqResult.NO_PENDING_APPROVAL, service.stopAutoLogin(
                "qq", "123456", OWNER_QQ, stopId));
    }

    @Test
    void replyFromNonOwnerReturnsForbiddenWithoutConsuming() throws Exception {
        QqAuthService service = service();
        seedPendingApproval(service);
        String approvalId = service.pollNotifications().stream()
                .filter(notify -> QqNotify.TYPE_DEVICE_APPROVAL.equals(notify.type()))
                .map(QqNotify::approvalId)
                .findFirst()
                .orElseThrow();

        assertEquals(QqResult.FORBIDDEN, service.decideDeviceApproval(
                "qq", "123456", "99999", "Y", approvalId));
        // The pending request must survive a rejected reply, and only the owner can consume it.
        assertEquals(QqResult.NOT_ONLINE, service.decideDeviceApproval(
                "qq", "123456", OWNER_QQ, "Y", approvalId));
    }

    private void seedPendingApproval(QqAuthService service) {
        Player player = player();
        assertTrue(service.requestDeviceApproval(
                player, lifecycleRegistry.activate(player, ignored -> {}), "fp", null));
    }

    private QqAuthService service() {
        return new QqAuthService(plugin, ctx);
    }

    private void stubScheduler() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
        when(builder.delay(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> builder);
        when(builder.schedule()).thenReturn(mock(ScheduledTask.class));
        when(plugin.getServer()).thenReturn(proxyServer);
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenReturn(builder);
        when(proxyServer.getPlayer(playerUuid)).thenReturn(Optional.empty());
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getUsername()).thenReturn(".Steve");
        when(player.isOnlineMode()).thenReturn(false);
        when(player.getClientBrand()).thenReturn("Minecraft");
        when(player.getProtocolVersion()).thenReturn(
                com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_21_4);
        return player;
    }

    private static QqSettings qqSettings(boolean deviceApprovalEnabled) {
        return new QqSettings(true, "127.0.0.1", 8765, SECRET, Set.of("123456"),
                300, 120, 5, "", deviceApprovalEnabled, 120);
    }

    private static RegisteredPlayer account() {
        RegisteredPlayer account = new RegisteredPlayer(".Steve",
                "$2a$10$stubhashstubhashstubhashstubhashstubhashst",
                "1.2.3.4", UUID.randomUUID().toString());
        account.setTotpToken("JBSWY3DPEHPK3PXP");
        return account;
    }

    private static QqBinding binding(String qqId) {
        return new QqBinding("qq", qqId, UUID.randomUUID().toString(), ".Steve",
                System.currentTimeMillis(), "bind_code");
    }

    private static QqRememberToken rememberToken(String uuid, String fingerprint) {
        return new QqRememberToken(
                net.rafalohaki.veloauth.util.DeviceFingerprints.hash(uuid, fingerprint),
                uuid,
                System.currentTimeMillis(),
                System.currentTimeMillis() + 86_400_000L);
    }
}
