package io.github.fulingsnow.youshuiauth.paper.qq;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.auth.DeviceApprovalFlow;
import io.github.fulingsnow.youshuiauth.model.QqBinding;
import io.github.fulingsnow.youshuiauth.model.QqLinkCode;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ConnectionOps;
import io.github.fulingsnow.youshuiauth.platform.QqOps;
import io.github.fulingsnow.youshuiauth.qq.CrossDeploymentClock;
import io.github.fulingsnow.youshuiauth.qq.QqAuthBridge;
import io.github.fulingsnow.youshuiauth.qq.QqAuthFlow;
import io.github.fulingsnow.youshuiauth.qq.QqDao;
import io.github.fulingsnow.youshuiauth.qq.QqLinkCodes;
import io.github.fulingsnow.youshuiauth.qq.QqLoginChallenges;
import io.github.fulingsnow.youshuiauth.qq.QqNotify;
import io.github.fulingsnow.youshuiauth.qq.QqResult;
import io.github.fulingsnow.youshuiauth.qq.QqSettings;
import io.github.fulingsnow.youshuiauth.runtime.AuthRuntime;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Paper 侧桥接实现：群内绑定码绑定账号、快捷登录码放行连接、新设备确认与免密阻止。
 * 群内注册建号仍只能由代理端消费（注册码在 Paper 返回明确结果码而非静默失败）。
 */
public final class PaperQqBridge implements QqAuthBridge {

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private final AuthRuntime runtime;
    private volatile io.github.fulingsnow.youshuiauth.command.QqAccountFlow accountFlow;
    private final QqAuthFlow flow;
    private final QqSettings settings;
    private final Logger logger;

    public PaperQqBridge(AuthRuntime runtime, QqAuthFlow flow, QqSettings settings, Logger logger) {
        this.runtime = runtime;
        this.flow = flow;
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public QqResult bind(String platform, String groupId, String qqId, String code, String nickname) {
        return accountFlow().bind(platform, groupId, qqId, code, nickname);
    }

    @Override
    public QqResult confirmLogin(String platform, String groupId, String qqId, String code) {
        return accountFlow().confirmLogin(platform, groupId, qqId, code);
    }

    private io.github.fulingsnow.youshuiauth.command.QqAccountFlow accountFlow() {
        io.github.fulingsnow.youshuiauth.command.QqAccountFlow flow = accountFlow;
        if (flow == null) {
            synchronized (this) {
                flow = accountFlow;
                if (flow == null) {
                    flow = new io.github.fulingsnow.youshuiauth.command.QqAccountFlow(runtime);
                    accountFlow = flow;
                }
            }
        }
        return flow;
    }

    @Override
    public QqResult decideDeviceApproval(String platform, String groupId, String qqId,
                                          String decision, String approvalId) {
        var flow = deviceApprovalFlow();
        if (flow == null) {
            return QqResult.NO_PENDING_APPROVAL;
        }
        DeviceApprovalFlow.DecisionOutcome outcome = flow.decide(qqId, decision, approvalId);
        if (outcome.result() != QqResult.OK) {
            return outcome.result();
        }
        var pending = outcome.pending();
        var connections = runtime.connections();
        if (!connections.isCurrent(pending.operation())) {
            return QqResult.NOT_ONLINE;
        }
        Optional<AuthPlayer> online = runtime.platformAccess().player(pending.uuid());
        if (online.isEmpty() || !online.get().isActive()) {
            return QqResult.NOT_ONLINE;
        }
        AuthPlayer player = online.get();
        String ip = player.address() == null ? "unknown" : player.address().getHostAddress();
        AuthCache cache = runtime.authCache();
        if (cache.isPlayerAuthorized(pending.uuid(), ip)
                && cache.hasActiveSession(pending.uuid(), player.username(), ip)) {
            return QqResult.ALREADY_AUTHORIZED;
        }
        if (outcome.approve()) {
            io.github.fulingsnow.youshuiauth.model.RegisteredPlayer dbPlayer =
                    runtime.databaseManager().findRegisteredPlayerByUuid(pending.uuid());
            if (dbPlayer == null) {
                return QqResult.NOT_BOUND;
            }
            io.github.fulingsnow.youshuiauth.command.CommandContext commandContext =
                    io.github.fulingsnow.youshuiauth.command.CommandContext.forPlatform(runtime,
                            runtime.databaseManager(), runtime.authCache(), runtime.settings(),
                            runtime.messages());
            io.github.fulingsnow.youshuiauth.command.AuthenticationContext authContext =
                    new io.github.fulingsnow.youshuiauth.command.AuthenticationContext(player,
                            player.username(), player.address(), dbPlayer, pending.operation());
            if (!io.github.fulingsnow.youshuiauth.command.PostAuthFlow.execute(
                    commandContext, authContext, dbPlayer, "QQ device approval")) {
                return QqResult.ERROR;
            }
            player.sendMessage(io.github.fulingsnow.youshuiauth.i18n.ClientMessages.of(player,
                    runtime.settings(), runtime.messages(), "qq.device_approval.approved",
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
            audit("device-approval-approved", player.username(), true);
            logger.info(SECURITY_MARKER, "New device approved over QQ for {}", player.username());
        } else {
            flowQqOps().ifPresent(qq -> qq.revokeRememberTokens(pending.uuid()));
            audit("device-approval-denied", player.username(), false);
            if (!connections.isCurrent(pending.operation())) {
                return QqResult.NOT_ONLINE;
            }
            runtime.platformAccess().scheduler().scheduleOnce(() -> {
                if (player.isActive()) {
                    player.disconnect(io.github.fulingsnow.youshuiauth.i18n.ClientMessages.of(player,
                            runtime.settings(), runtime.messages(), "qq.device_approval.denied",
                            net.kyori.adventure.text.format.NamedTextColor.RED));
                }
            }, java.time.Duration.ZERO);
            logger.info(SECURITY_MARKER, "New device denied over QQ for {}", player.username());
        }
        return QqResult.OK;
    }

    @Override
    public QqResult stopAutoLogin(String platform, String groupId, String qqId,
                                  String stopId, boolean forceOverride) {
        var flow = deviceApprovalFlow();
        if (flow == null) {
            return QqResult.NO_PENDING_APPROVAL;
        }
        DeviceApprovalFlow.StopOutcome outcome =
                flow.stopAutoLogin(platform, groupId, qqId, stopId, forceOverride);
        if (outcome.result() != QqResult.OK || outcome.playerId() == null) {
            return outcome.result();
        }
        runtime.platformAccess().player(outcome.playerId()).ifPresent(player ->
                runtime.platformAccess().scheduler().scheduleOnce(() -> {
                    if (!player.isActive()) {
                        return;
                    }
                    player.disconnect(io.github.fulingsnow.youshuiauth.i18n.ClientMessages.of(
                            player, runtime.settings(), runtime.messages(),
                            "qq.auto_login.stopped_kick",
                            net.kyori.adventure.text.format.NamedTextColor.RED));
                }, java.time.Duration.ZERO));
        return QqResult.OK;
    }

    private void audit(String details, String username, boolean success) {
        var auditLog = runtime.auditLogService();
        if (auditLog == null || !auditLog.isEnabled()) {
            return;
        }
        auditLog.save(success
                        ? io.github.fulingsnow.youshuiauth.audit.AuditEventType.QQ_LOGIN_OK
                        : io.github.fulingsnow.youshuiauth.audit.AuditEventType.QQ_LOGIN_FAIL,
                username, null, details);
    }

    private DeviceApprovalFlow deviceApprovalFlow() {
        return runtime.qq() instanceof QqAuthFlow
                ? runtime.deviceApprovalFlow()
                : null;
    }

    private Optional<QqOps> flowQqOps() {
        return runtime.qq() instanceof QqOps ops ? Optional.of(ops) : Optional.empty();
    }

    @Override
    public boolean isQqBound(String platform, String qqId) {
        return settings.isEnabled() && flow.isQqBound(qqId);
    }

    @Override
    public List<QqNotify> pollNotifications() {
        DeviceApprovalFlow flow = deviceApprovalFlow();
        if (flow == null) {
            return List.of();
        }
        List<QqNotify> out = new java.util.ArrayList<>();
        QqNotify notify;
        while ((notify = flow.pollNotification()) != null) {
            out.add(notify);
        }
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
