package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.YouShuiAuth;
import io.github.fulingsnow.youshuiauth.audit.AuditEventType;
import io.github.fulingsnow.youshuiauth.auth.DeviceApprovalFlow;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.qq.QqDeviceApprovals;
import io.github.fulingsnow.youshuiauth.qq.QqNotify;
import io.github.fulingsnow.youshuiauth.qq.QqResult;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.util.VelocityPlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 代理端的新设备确认外壳：pending 管理、归属裁决、通知内容都在 core 的
 * {@link DeviceApprovalFlow}（v1 指纹哈希由这里传入）；本类只保留代理专属动作——
 * limbo 认证超时的挂起与恢复、群回复超时调度、autoTransfer 与踢人。
 */
final class QqDeviceApprovalService {

    private static final Logger logger = LoggerFactory.getLogger(QqDeviceApprovalService.class);
    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private final YouShuiAuth plugin;
    private final CommandContext ctx;
    private final QqSettings qqSettings;
    private final QqAuthService parent;
    private final DeviceApprovalFlow flow;

    /** 供 AutoLoginService 的免密后通知复用同一个设备确认状态机。 */
    public DeviceApprovalFlow flow() {
        return flow;
    }
    private final ConcurrentMap<String, ScheduledTask> deviceApprovalTasks = new ConcurrentHashMap<>();

    QqDeviceApprovalService(YouShuiAuth plugin, CommandContext ctx, QqSettings qqSettings,
            QqAuthService parent) {
        this.plugin = plugin;
        this.ctx = ctx;
        this.qqSettings = qqSettings;
        this.parent = parent;
        this.flow = new DeviceApprovalFlow(qqSettings, ctx.settings(), ctx.databaseManager(),
                parent::dao, "velocity", logger);
    }

    boolean isDeviceApprovalEnabled() {
        return parent.isEnabled()
                && qqSettings.isDeviceApprovalEnabled()
                && ctx.settings().isAutoLoginEnabled();
    }

    boolean requestDeviceApproval(Player player,
                                  ConnectionRef operation,
                                  String fingerprint,
                                  Runnable onTimeout) {
        if (!isDeviceApprovalEnabled() || player == null || operation == null
                || player.isOnlineMode() || fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        try {
            if (!flow.request(VelocityAuthPlayer.of(player), operation)) {
                return false;
            }
            QqNotify notify = flow.pollNotification();
            if (notify != null) {
                parent.enqueueNotification(notify);
            }
            ctx.timeouts().beginSession(VelocityAuthPlayer.of(player));
            ctx.timeouts().suspend(player.getUniqueId());
            scheduleApprovalTimeout(notify.qqId(), notify.approvalId(), onTimeout);
            player.sendMessage(ctx.messages().component(
                    "qq.device_approval.pending", NamedTextColor.YELLOW,
                    qqSettings.getDeviceApprovalTimeoutSeconds()));
            return true;
        } catch (RuntimeException e) {
            logger.warn(SECURITY_MARKER, "New-device approval unavailable for {}: {}",
                    player.getUsername(), e.toString());
            return false;
        }
    }

    QqResult decideDeviceApproval(String platform, String groupId, String qqId,
                                  String decision, String approvalId) {
        DeviceApprovalFlow.DecisionOutcome outcome = flow.decide(qqId, decision, approvalId);
        if (outcome.result() != QqResult.OK) {
            return outcome.result();
        }
        QqDeviceApprovals.PendingDeviceApproval pending = outcome.pending();
        cancelDeviceApprovalTask(pending.qqId());

        Player player = plugin.getServer().getPlayer(pending.uuid()).orElse(null);
        if (player == null || !player.isActive()
                || !plugin.getConnectionLifecycleRegistry().isCurrent(pending.operation())
                || !plugin.getConnectionManager().isPlayerOnAuthServer(player)) {
            return QqResult.NOT_ONLINE;
        }
        if (parent.alreadyAuthorized(player)) {
            return QqResult.ALREADY_AUTHORIZED;
        }
        return outcome.approve() ? approveDevice(pending, player) : denyDevice(pending, player);
    }

    boolean isPending(Player player) {
        if (player == null || !isDeviceApprovalEnabled()) {
            return false;
        }
        Optional<QqDeviceApprovals.PendingDeviceApproval> pending = flow.peekByUuid(player.getUniqueId());
        return pending.isPresent()
                && plugin.getConnectionLifecycleRegistry().isCurrent(pending.get().operation());
    }

    void cancelDeviceApproval(Player player) {
        if (player == null) {
            return;
        }
        flow.cancel(player.getUniqueId())
                .ifPresent(approval -> cancelDeviceApprovalTask(approval.qqId()));
    }

    private QqResult approveDevice(QqDeviceApprovals.PendingDeviceApproval pending, Player player) {
        parent.authorizeDevice(player);
        parent.rememberDevice(player);
        boolean applied = plugin.getConnectionLifecycleRegistry().runIfCurrent(
                pending.operation(), () -> {
                    player.sendMessage(ctx.messages().component(
                            "qq.device_approval.approved", NamedTextColor.GREEN));
                    plugin.getConnectionManager().autoTransferFromAuthServerToBackend(player);
                });
        if (!applied) {
            return QqResult.NOT_ONLINE;
        }
        parent.audit(AuditEventType.LOGIN_OK, player.getUsername(), "device-approval");
        return QqResult.OK;
    }

    private QqResult denyDevice(QqDeviceApprovals.PendingDeviceApproval pending, Player player) {

        parent.revokeRememberTokens(pending.uuid());
        boolean applied = plugin.getConnectionLifecycleRegistry().runIfCurrent(
                pending.operation(), () -> player.disconnect(ctx.messages().component(
                        "qq.device_approval.denied", NamedTextColor.RED)));
        if (!applied) {
            return QqResult.NOT_ONLINE;
        }
        parent.audit(AuditEventType.LOGIN_FAIL, player.getUsername(), "device-approval-denied");
        return QqResult.OK;
    }

    private void scheduleApprovalTimeout(String qqId, String approvalId, Runnable onTimeout) {
        cancelDeviceApprovalTask(qqId);
        try {
            ScheduledTask task = plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> {
                        deviceApprovalTasks.remove(qqId);
                        flow.consumeByApprovalId(approvalId);
                        if (onTimeout != null) {
                            onTimeout.run();
                        }
                    })
                    .delay(Math.max(1, qqSettings.getDeviceApprovalTimeoutSeconds()),
                            TimeUnit.SECONDS)
                    .schedule();
            deviceApprovalTasks.put(qqId, task);
        } catch (RuntimeException e) {
            logger.warn(SECURITY_MARKER, "Failed to schedule new-device approval timeout");
        }
    }

    private void cancelDeviceApprovalTask(String qqId) {
        if (qqId == null) {
            return;
        }
        ScheduledTask task = deviceApprovalTasks.remove(qqId);
        if (task != null) {
            task.cancel();
        }
    }

    void queueAutoLoginNotify(Player player, String remainingTime) {
        flow.notifyAutoLogin(VelocityAuthPlayer.of(player), remainingTime);
        QqNotify notify;
        while ((notify = flow.pollNotification()) != null) {
            parent.enqueueNotification(notify);
        }
    }

    QqResult stopAutoLogin(String platform, String groupId, String qqId, String stopId) {
        DeviceApprovalFlow.StopOutcome outcome = flow.stopAutoLogin(platform, groupId, qqId, stopId);
        if (outcome.result() != QqResult.OK) {
            return outcome.result();
        }
        Player player = plugin.getServer().getPlayer(outcome.playerId()).orElse(null);
        if (player != null && player.isActive()) {
            player.disconnect(ctx.messages().component(
                    "qq.auto_login.stopped_kick", NamedTextColor.RED));
        }
        parent.audit(AuditEventType.LOGIN_FAIL,
                player != null ? player.getUsername() : outcome.playerId().toString(),
                "auto-login-stopped");
        return QqResult.OK;
    }
}
