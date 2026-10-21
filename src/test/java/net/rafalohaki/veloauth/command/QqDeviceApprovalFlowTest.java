package net.rafalohaki.veloauth.command;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.Scheduler;
import net.rafalohaki.veloauth.VeloAuth;
import net.rafalohaki.veloauth.config.Settings;
import net.rafalohaki.veloauth.database.DatabaseManager;
import net.rafalohaki.veloauth.lifecycle.ConnectionLifecycleRegistry;
import net.rafalohaki.veloauth.model.QqBinding;
import net.rafalohaki.veloauth.model.QqRememberToken;
import net.rafalohaki.veloauth.model.RegisteredPlayer;
import net.rafalohaki.veloauth.qq.QqDao;
import net.rafalohaki.veloauth.qq.QqSettings;
import net.rafalohaki.veloauth.util.DeviceFingerprints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Trigger matrix for the new-device approval request: every guard must answer "no approval"
 * (fall back to limbo) instead of blocking the player outside a working login flow.
 */
class QqDeviceApprovalFlowTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String NICKNAME = ".Steve";

    private VeloAuth plugin;
    private CommandContext ctx;
    private Settings settings;
    private DatabaseManager databaseManager;
    private QqDao dao;
    private Player player;
    private UUID playerUuid;
    private ConnectionLifecycleRegistry.Operation operation;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(VeloAuth.class);
        ctx = mock(CommandContext.class);
        settings = mock(Settings.class);
        databaseManager = mock(DatabaseManager.class);
        dao = mock(QqDao.class);
        player = mock(Player.class);
        playerUuid = UUID.randomUUID();
        operation = mock(ConnectionLifecycleRegistry.Operation.class);

        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getUsername()).thenReturn(NICKNAME);
        when(player.isOnlineMode()).thenReturn(false);
        when(player.getClientBrand()).thenReturn("Minecraft");
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_21_4);

        when(ctx.settings()).thenReturn(settings);
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
                .thenReturn(Optional.of(binding()));
    }

    @Test
    void disabledFeatureNeverRequestsApproval() {
        when(settings.getQqSettings()).thenReturn(qqSettings(false));

        assertFalse(service().requestDeviceApproval(player, operation, "fp", null));
    }

    @Test
    void onlineModeNeverRequestsApproval() {
        when(player.isOnlineMode()).thenReturn(true);

        assertFalse(service().requestDeviceApproval(player, operation, "fp", null));
    }

    @Test
    void blankFingerprintNeverRequestsApproval() {
        assertFalse(service().requestDeviceApproval(player, operation, "  ", null));
    }

    @Test
    void totpAccountNeverRequestsApproval() throws Exception {
        when(settings.getTwoFactorSettings()).thenReturn(twoFactorEnabled());
        when(dao.findUsableRememberTokenByUuid(anyString(), anyLong()))
                .thenReturn(Optional.of(rememberToken(playerUuid.toString(), "stored")));

        assertFalse(service().requestDeviceApproval(player, operation, "fp", null));
    }

    @Test
    void unboundAccountNeverRequestsApproval() throws Exception {
        when(dao.findByUuid("qq", playerUuid.toString())).thenReturn(Optional.empty());

        assertFalse(service().requestDeviceApproval(player, operation, "fp", null));
    }

    @Test
    void firstLoginWithoutDeviceRecordNeverRequestsApproval() throws Exception {
        when(dao.findUsableRememberTokenByUuid(anyString(), anyLong()))
                .thenReturn(Optional.empty());

        assertFalse(service().requestDeviceApproval(player, operation, "fp", null));
    }

    @Test
    void matchingFingerprintNeverRequestsApproval() throws Exception {
        String fingerprint = DeviceFingerprints.fingerprint(player, false);
        when(dao.findUsableRememberTokenByUuid(anyString(), anyLong()))
                .thenReturn(Optional.of(rememberToken(playerUuid.toString(), fingerprint)));
        when(dao.findRememberToken(DeviceFingerprints.hash(playerUuid.toString(), fingerprint)))
                .thenReturn(Optional.of(rememberToken(playerUuid.toString(), fingerprint)));

        assertFalse(service()
                .requestDeviceApproval(player, operation, fingerprint, null));
    }

    @Test
    void schedulingFailureFallsBackToLimbo() throws Exception {
        when(dao.findUsableRememberTokenByUuid(anyString(), anyLong()))
                .thenReturn(Optional.of(rememberToken(playerUuid.toString(), "stored")));
        ProxyServer proxyServer = mock(ProxyServer.class);
        Scheduler scheduler = mock(Scheduler.class);
        when(plugin.getServer()).thenReturn(proxyServer);
        when(proxyServer.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class)))
                .thenThrow(new IllegalStateException("scheduler unavailable"));

        assertFalse(service().requestDeviceApproval(player, operation, "fp", null));
    }

    @Test
    void protocolLabelPrefersHumanReadableVersion() {
        ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
        assertEquals(version.getMostRecentSupportedVersion()
                        + " (" + version.getProtocol() + ")",
                QqDeviceApprovalService.protocolLabel(version));
    }

    @Test
    void protocolLabelFallsBackForUnknownAndLegacy() {
        assertEquals("unknown", QqDeviceApprovalService.protocolLabel(ProtocolVersion.UNKNOWN));
        assertEquals("legacy (" + ProtocolVersion.LEGACY.getProtocol() + ")",
                QqDeviceApprovalService.protocolLabel(ProtocolVersion.LEGACY));
        assertEquals("unknown", QqDeviceApprovalService.protocolLabel(null));
    }

    private QqAuthService service() {
        return new QqAuthService(plugin, ctx);
    }

    private static QqSettings qqSettings(boolean deviceApprovalEnabled) {
        return new QqSettings(true, "127.0.0.1", 8765, SECRET, Set.of("123456"),
                300, 120, 5, "", deviceApprovalEnabled, 120);
    }

    private static RegisteredPlayer account() {
        RegisteredPlayer account = new RegisteredPlayer(NICKNAME,
                "$2a$10$stubhashstubhashstubhashstubhashstubhashst",
                "1.2.3.4", UUID.randomUUID().toString());
        account.setTotpToken("JBSWY3DPEHPK3PXP");
        return account;
    }

    private static QqBinding binding() {
        return new QqBinding("qq", "10001", playerUuidValue(), NICKNAME,
                System.currentTimeMillis(), "bind_code");
    }

    private static String playerUuidValue() {
        // The binding row is created lazily inside tests; identity only needs to be stable.
        return "00000000-0000-0000-0000-000000000001";
    }

    private static QqRememberToken rememberToken(String uuid, String fingerprint) {
        return new QqRememberToken(
                DeviceFingerprints.hash(uuid, fingerprint),
                uuid,
                System.currentTimeMillis(),
                System.currentTimeMillis() + 86_400_000L);
    }

    private static Settings.TwoFactorSettings twoFactorEnabled() {
        return new Settings.TwoFactorSettings(true, "YouShuiAuth", false, 300);
    }
}
