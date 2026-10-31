package io.github.fulingsnow.youshuiauth.command;

import io.github.fulingsnow.youshuiauth.YouShuiAuth;
import io.github.fulingsnow.youshuiauth.audit.AuditLogService;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.platform.AuthServices;
import io.github.fulingsnow.youshuiauth.platform.ConnectionOps;
import io.github.fulingsnow.youshuiauth.platform.PlatformAccess;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.platform.ReportOps;
import io.github.fulingsnow.youshuiauth.platform.TimeoutOps;
import io.github.fulingsnow.youshuiauth.platform.TransferOps;

/**
 * Test-facing view over the plugin facade so the legacy CommandContext constructor
 * keeps working with mocked {@link YouShuiAuth} instances.
 */
final class YouShuiAuthServicesView implements AuthServices {

    private final YouShuiAuth plugin;

    YouShuiAuthServicesView(YouShuiAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public DatabaseManager databaseManager() {
        return plugin.getDatabaseManager();
    }

    @Override
    public AuthCache authCache() {
        return plugin.getAuthCache();
    }

    @Override
    public Settings settings() {
        return plugin.getSettings();
    }

    @Override
    public Messages messages() {
        return plugin.getMessages();
    }

    @Override
    public org.slf4j.Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public AuditLogService auditLogService() {
        return plugin.getAuditLogService();
    }

    @Override
    public io.github.fulingsnow.youshuiauth.auth.totp.TotpService totpService() {
        return plugin.getTotpService();
    }

    @Override
    public io.github.fulingsnow.youshuiauth.auth.totp.PendingTotpStore pendingTotpStore() {
        return plugin.getPendingTotpStore();
    }

    @Override
    public io.github.fulingsnow.youshuiauth.auth.totp.TotpReplayGuard totpReplayGuard() {
        return plugin.getTotpReplayGuard();
    }

    @Override
    public PlatformAccess platformAccess() {
        return plugin.getPlatformAccess();
    }

    @Override
    public ConnectionOps connections() {
        return new ConnectionOps() {
            public io.github.fulingsnow.youshuiauth.platform.ConnectionRef capture(
                    io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
                com.velocitypowered.api.proxy.Player velocity =
                        io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
                return velocity == null
                        ? null : plugin.getConnectionLifecycleRegistry().capture(velocity);
            }

            public boolean isCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation) {
                return plugin.getConnectionLifecycleRegistry().isCurrent(operation);
            }

            public boolean runIfCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef operation,
                                        Runnable effect) {
                return plugin.getConnectionLifecycleRegistry().runIfCurrent(operation, effect);
            }

            public boolean retire(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
                com.velocitypowered.api.proxy.Player velocity =
                        io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
                return velocity != null && plugin.getConnectionLifecycleRegistry().markRetired(
                        velocity, () -> plugin.clearConnectionBoundState(
                                velocity, plugin.getAuthCache(), plugin.getConnectionManager()));
            }
        };
    }

    @Override
    public TimeoutOps timeouts() {
        return plugin.getAuthTimeoutScheduler();
    }

    @Override
    public TransferOps transfers() {
        io.github.fulingsnow.youshuiauth.connection.ConnectionManager cm = plugin.getConnectionManager();
        return new TransferOps() {
            public boolean isPlayerOnAuthServer(java.util.UUID playerId) {
                return cm.isPlayerOnAuthServer(playerId);
            }

            public boolean transferToBackend(io.github.fulingsnow.youshuiauth.platform.AuthPlayer player) {
                com.velocitypowered.api.proxy.Player velocity =
                        io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer.unwrap(player);
                return velocity != null && cm.transferToBackend(velocity);
            }
        };
    }

    @Override
    public ReportOps reports() {
        io.github.fulingsnow.youshuiauth.report.ReportService service = plugin.getReportService();
        return service == null ? null : new ReportOps() {
            public Outcome generateAndUpload(Settings.OperationSettings settings) {
                io.github.fulingsnow.youshuiauth.report.ReportService.ReportResult result =
                        service.generateAndUpload(settings);
                return new Outcome(result.success(), result.url(), result.error());
            }
        };
    }

    @Override
    public QqOps qq() {
        return plugin.getQqAuthService();
    }

    @Override
    public boolean reloadConfig() {
        return plugin.reloadConfig();
    }
}
