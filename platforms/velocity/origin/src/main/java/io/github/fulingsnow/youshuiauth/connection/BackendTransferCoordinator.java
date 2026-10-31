package io.github.fulingsnow.youshuiauth.connection;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.model.CachedAuthUser;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates one generation-owned backend transfer state machine. */
final class BackendTransferCoordinator {

    private static final String CONNECTION_ERROR_GAME_SERVER = "connection.error.game_server";
    private static final String MSG_ERROR_UNKNOWN = "error.unknown";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int BACKEND_WAIT_REMINDER_INTERVAL = 6;
    private static final int MAX_BACKEND_WAIT_RETRIES = 60;
    private static final long BACKEND_WAIT_INTERVAL_SECONDS = 5;

    private final ConnectionManager lifecycle;
    private final BackendSelector backendSelector;
    private final AuthCache authCache;
    private final Settings settings;
    private final Logger logger;
    private final Messages messages;

    BackendTransferCoordinator(
            ConnectionManager lifecycle,
            BackendSelector backendSelector,
            AuthCache authCache,
            Settings settings,
            Logger logger,
            Messages messages) {
        this.lifecycle = lifecycle;
        this.backendSelector = backendSelector;
        this.authCache = authCache;
        this.settings = settings;
        this.logger = logger;
        this.messages = messages;
    }

    BackendTransferOutcome transfer(Player player) {
        PlayerTransferState state = currentState(player);
        return state == null ? BackendTransferOutcome.REJECTED : transfer(player, state);
    }

    private BackendTransferOutcome transfer(Player player, PlayerTransferState state) {
        if (isStale(state) || !resetTasksIfCurrent(state, false)) {
            return BackendTransferOutcome.REJECTED;
        }
        try {
            Optional<RegisteredServer> backendServer =
                    backendSelector.resolveForcedHostTarget(player, state);
            if (backendServer.isEmpty()) {
                backendServer = backendSelector.findAvailableBackendServer(state);
            }

            if (backendServer.isEmpty()) {
                if (isStale(state)) {
                    return BackendTransferOutcome.REJECTED;
                }
                logger.warn("{} 没有可用的后端服务器——开始后台重试",
                        player.getUsername());
                scheduleBackendWaitRetry(player, state, 0);
                return BackendTransferOutcome.WAITING_FOR_BACKEND;
            }

            RegisteredServer targetServer = backendServer.get();
            String serverName = targetServer.getServerInfo().getName();
            if (isStale(state)) {
                return BackendTransferOutcome.REJECTED;
            }

            player.sendMessage(messages.component("connection.connecting", NamedTextColor.YELLOW));
            if (logger.isDebugEnabled()) {
                logger.debug(messages.get(
                        "player.transfer.backend.attempt", player.getUsername(), serverName));
            }
            return executeBackendTransfer(player, state, targetServer, serverName);
        } catch (RuntimeException failure) {
            if (isStale(state)) {
                return BackendTransferOutcome.REJECTED;
            }
            logger.error("把玩家转移到后端的操作出错：{}", player.getUsername(), failure);
            sendErrorMessageIfCurrent(player, state);
            return BackendTransferOutcome.REJECTED;
        }
    }

    private BackendTransferOutcome executeBackendTransfer(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName) {
        if (isStale(state) || !validatePlayerActive(player, serverName)) {
            return BackendTransferOutcome.REJECTED;
        }

        int attempts = state.retryAttempts().get();
        if (!validateRetryLimit(player, state, attempts)) {
            return BackendTransferOutcome.REJECTED;
        }

        if (!claimBackendConnection(state)) {
            logger.debug("{} 的后端传送已在进行中——合并重复请求",
                    player.getUsername());
            return BackendTransferOutcome.COALESCED;
        }
        try {
            return performTransfer(player, state, targetServer, serverName, attempts);
        } finally {
            releaseBackendConnection(state);
        }
    }

    private boolean claimBackendConnection(PlayerTransferState state) {
        if (isStale(state)
                || !state.backendConnectionActive().compareAndSet(false, true)) {
            return false;
        }
        if (isStale(state)) {
            state.backendConnectionActive().set(false);
            return false;
        }
        return true;
    }

    private void releaseBackendConnection(PlayerTransferState state) {
        if (!isStale(state)) {
            state.backendConnectionActive().set(false);
        }
    }

    private boolean validatePlayerActive(Player player, String serverName) {
        if (!player.isActive()) {
            if (logger.isDebugEnabled()) {
                logger.debug("玩家 {} 已不在线——跳过传送到 {}",
                        player.getUsername(), serverName);
            }
            return false;
        }
        return true;
    }

    private boolean validateRetryLimit(Player player, PlayerTransferState state, int attempts) {
        if (attempts >= MAX_RETRY_ATTEMPTS) {
            logger.warn("玩家 {} 超过传送重试上限（{}）——放弃",
                    player.getUsername(), MAX_RETRY_ATTEMPTS);
            state.retryAttempts().set(0);
            if (finishIfCurrent(state, false)) {
                sendErrorMessage(player);
            }
            return false;
        }
        return true;
    }

    private BackendTransferOutcome performTransfer(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName,
            int attempts) {
        try {
            if (isStale(state)) {
                return BackendTransferOutcome.REJECTED;
            }
            if (!player.isActive()) {
                logger.debug("玩家 {} 在传送开始前已断开", player.getUsername());
                return BackendTransferOutcome.REJECTED;
            }

            CompletableFuture<ConnectionRequestBuilder.Result> connection =
                    startConnectionIfCurrent(player, state, targetServer);
            if (connection == null) {
                return BackendTransferOutcome.REJECTED;
            }
            ConnectionRequestBuilder.Result result = connection.join();
            return handleTransferResult(player, state, targetServer, serverName, attempts, result);
        } catch (CompletionException failure) {
            if (isStale(state)) {
                return BackendTransferOutcome.REJECTED;
            }
            return handleCompletionException(
                    player, state, targetServer, serverName, attempts, failure);
        } catch (RuntimeException failure) {
            if (isStale(state)) {
                return BackendTransferOutcome.REJECTED;
            }
            logTransferError(player, serverName, failure);
            sendErrorMessageIfCurrent(player, state);
            return BackendTransferOutcome.REJECTED;
        }
    }

    private BackendTransferOutcome handleTransferResult(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName,
            int attempts,
            ConnectionRequestBuilder.Result result) {
        if (isStale(state)) {
            return BackendTransferOutcome.REJECTED;
        }
        if (!player.isActive()) {
            finishIfCurrent(state, false);
            logger.debug("玩家 {} 在连接 {} 期间断开——跳过结果处理",
                    player.getUsername(), serverName);
            return BackendTransferOutcome.REJECTED;
        }
        if (result.isSuccessful()) {
            return handleSuccessfulTransfer(player, state, serverName);
        }
        return handleFailedTransfer(player, state, targetServer, serverName, attempts, result);
    }

    private BackendTransferOutcome handleSuccessfulTransfer(
            Player player, PlayerTransferState state, String serverName) {
        if (!finishIfCurrent(state, true)) {
            return BackendTransferOutcome.REJECTED;
        }
        if (logger.isDebugEnabled()) {
            logger.debug(messages.get(
                    "player.transfer.backend.success", player.getUsername(), serverName));
        }
        return BackendTransferOutcome.CONNECTED;
    }

    private BackendTransferOutcome handleFailedTransfer(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName,
            int attempts,
            ConnectionRequestBuilder.Result result) {
        if (isStale(state)) {
            return BackendTransferOutcome.REJECTED;
        }
        if (logger.isWarnEnabled()) {
            logger.warn("把玩家 {} 传送到服务器 {} 失败（状态：{}）：{}",
                    player.getUsername(), serverName, result.getStatus(),
                    KickReasonRenderer.renderPlain(result));
        }

        if (attemptAuthServerFallback(player, state, targetServer, serverName, attempts)) {
            return BackendTransferOutcome.FALLBACK_TO_AUTH;
        }

        String reason = KickReasonRenderer.renderPlain(result);
        sendErrorMessageIfCurrent(player, state, reason);
        return BackendTransferOutcome.REJECTED;
    }

    private boolean attemptAuthServerFallback(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName,
            int attempts) {
        if (isStale(state)) {
            return false;
        }
        RegisteredServer authServer = validateAndGetAuthServer(player, state);
        if (authServer == null || isPlayerOnAuthServer(player)) {
            return false;
        }

        if (!resetTasksIfCurrent(state, false) || isStale(state)) {
            return false;
        }
        state.retryAttempts().incrementAndGet();
        if (isStale(state)) {
            return false;
        }
        if (logger.isInfoEnabled()) {
            logger.info("玩家 {} 走兜底流程（第 {}/{} 次）：先送认证服再重试后端 {}",
                    player.getUsername(), attempts + 1, MAX_RETRY_ATTEMPTS, serverName);
        }

        scheduleAuthServerFallback(player, state, authServer, targetServer, serverName);
        return true;
    }

    private void scheduleAuthServerFallback(
            Player player,
            PlayerTransferState state,
            RegisteredServer authServer,
            RegisteredServer targetServer,
            String serverName) {
        CompletableFuture<ConnectionRequestBuilder.Result> connection =
                startConnectionIfCurrent(player, state, authServer);
        if (connection == null) {
            return;
        }
        connection.whenComplete((limboResult, failure) ->
                handleAuthServerFallbackResult(
                        player, state, targetServer, serverName, limboResult, failure));
    }

    private void handleAuthServerFallbackResult(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName,
            ConnectionRequestBuilder.Result limboResult,
            Throwable failure) {
        if (isStale(state)) {
            return;
        }
        if (failure != null || limboResult == null || !limboResult.isSuccessful()) {
            logFallbackFailure(player, state, limboResult, failure);
            sendErrorMessageIfCurrent(player, state);
            return;
        }
        scheduleBackendRetryAfterLimbo(player, state, targetServer, serverName);
    }

    private void logFallbackFailure(
            Player player,
            PlayerTransferState state,
            ConnectionRequestBuilder.Result limboResult,
            Throwable failure) {
        if (isStale(state)) {
            return;
        }
        String reason;
        if (failure != null) {
            reason = failure.getMessage();
        } else if (limboResult == null) {
            reason = "null result";
        } else {
            reason = KickReasonRenderer.renderPlain(limboResult);
        }
        logger.warn("{} 回退到认证服失败：{}", player.getUsername(), reason);
    }

    private void scheduleBackendRetryAfterLimbo(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName) {
        scheduleOwnedTask(state, state.pendingTransfer(),
                settings.getAutoTransferDelayMillis(), TimeUnit.MILLISECONDS, () -> {
            if (isStale(state) || !player.isActive() || !isPlayerOnAuthServer(player)) {
                return;
            }
            VirtualThreadExecutorProvider.submitTask(() -> {
                if (!isStale(state)) {
                    executeBackendRetryAfterLimbo(player, state, targetServer, serverName);
                }
            });
        });
    }

    private void scheduleBackendWaitRetry(Player player, PlayerTransferState state, int attempt) {
        if (isStale(state)) {
            return;
        }

        if (attempt == 0) {
            sendIfNotEmptyIfCurrent(
                    player, state, "connection.waiting_for_server", NamedTextColor.YELLOW);
        }

        if (attempt >= MAX_BACKEND_WAIT_RETRIES) {
            if (isStale(state)) {
                return;
            }
            ScheduledTaskRegistry.cancel(state.backendWait());
            logger.warn("{} 等待后端超时（已尝试 {} 次）", player.getUsername(), attempt);
            sendIfNotEmptyIfCurrent(
                    player, state, "connection.error.no_servers", NamedTextColor.RED);
            return;
        }

        scheduleOwnedTask(state, state.backendWait(),
                BACKEND_WAIT_INTERVAL_SECONDS, TimeUnit.SECONDS, () -> {
            if (isStale(state) || !player.isActive() || !isPlayerOnAuthServer(player)) {
                return;
            }

            backendSelector.findAvailableBackendServerForRetryAsync(player, state)

                    .whenComplete((server, failure) ->
                            handleBackendWaitSelection(player, state, attempt,
                                    Objects.requireNonNullElse(server, Optional.empty()), failure));
        });
    }

    private void handleBackendWaitSelection(
            Player player,
            PlayerTransferState state,
            int attempt,
            Optional<RegisteredServer> server,
            Throwable failure) {
        if (isStale(state) || !player.isActive() || !isPlayerOnAuthServer(player)) {
            return;
        }
        if (failure != null) {
            logger.warn("{} 等待期间后端选择失败，正在重试",
                    player.getUsername(), failure);
        }
        if (failure == null && server.isPresent()) {
            RegisteredServer target = server.get();
            String targetName = target.getServerInfo().getName();
            logger.info("{} 等待后后端可用——传送至 {}",
                    player.getUsername(), targetName);
            sendIfNotEmptyIfCurrent(player, state, "connection.connecting", NamedTextColor.GREEN);
            VirtualThreadExecutorProvider.submitTask(() -> {
                if (!isStale(state)) {
                    executeBackendTransfer(player, state, target, targetName);
                }
            });
            return;
        }
        if (attempt % BACKEND_WAIT_REMINDER_INTERVAL == BACKEND_WAIT_REMINDER_INTERVAL - 1) {
            sendIfNotEmptyIfCurrent(
                    player, state, "connection.waiting_for_server", NamedTextColor.YELLOW);
        }
        scheduleBackendWaitRetry(player, state, attempt + 1);
    }

    private void sendIfNotEmptyIfCurrent(
            Player player, PlayerTransferState state, String messageKey, NamedTextColor color) {
        if (isStale(state)) {
            return;
        }
        String resolved = messages.get(messageKey);
        if (resolved == null || resolved.isEmpty() || isStale(state)) {
            return;
        }
        player.sendMessage(messages.componentFromResolvedText(resolved, color));
    }

    private void executeBackendRetryAfterLimbo(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName) {
        if (isStale(state) || !claimBackendConnection(state)) {
            logger.debug("{} 的后端传送已在进行中——跳过 limbo 重试",
                    player.getUsername());
            return;
        }
        try {
            if (isStale(state)) {
                return;
            }
            CompletableFuture<ConnectionRequestBuilder.Result> connection =
                    startConnectionIfCurrent(player, state, targetServer);
            if (connection == null) {
                return;
            }
            ConnectionRequestBuilder.Result retry = connection.join();
            if (!retry.isSuccessful()) {
                if (isStale(state)) {
                    return;
                }
                logger.warn("认证服失败后重试把 {} 连接到 {}：{}",
                        player.getUsername(), serverName, KickReasonRenderer.renderPlain(retry));
                sendErrorMessageIfCurrent(player, state);
            }
        } catch (CompletionException failure) {
            if (isStale(state)) {
                return;
            }
            logger.error("重试 {} 的后端传送出错：{}",
                    player.getUsername(), failure.getMessage(), failure);
            sendErrorMessageIfCurrent(player, state);
        } finally {
            releaseBackendConnection(state);
        }
    }

    private BackendTransferOutcome handleCompletionException(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName,
            int attempts,
            CompletionException failure) {
        if (isStale(state)) {
            return BackendTransferOutcome.REJECTED;
        }
        if (failure.getCause() instanceof TimeoutException
                && handleTimeoutRetry(player, state, targetServer, serverName, attempts)) {
            return BackendTransferOutcome.RETRYING_TIMEOUT;
        }
        logger.error("把玩家 {} 传送到服务器 {} 出错", player.getUsername(), serverName, failure);
        sendErrorMessageIfCurrent(player, state);
        return BackendTransferOutcome.REJECTED;
    }

    private void logTransferError(Player player, String serverName, RuntimeException failure) {
        if (logger.isErrorEnabled()) {
            logger.error("把玩家 {} 传送到服务器 {} 出错：{}",
                    player.getUsername(), serverName, failure.getMessage(), failure);
        }
    }

    private void sendErrorMessage(Player player) {
        sendErrorMessage(player, messages.get(MSG_ERROR_UNKNOWN));
    }

    private void sendErrorMessage(Player player, String reason) {
        player.sendMessage(messages.component(CONNECTION_ERROR_GAME_SERVER, NamedTextColor.RED, reason));
    }

    private void sendErrorMessageIfCurrent(Player player, PlayerTransferState state) {
        if (!isStale(state)) {
            sendErrorMessage(player);
        }
    }

    private void sendErrorMessageIfCurrent(
            Player player, PlayerTransferState state, String reason) {
        if (!isStale(state)) {
            sendErrorMessage(player, reason);
        }
    }

    private boolean handleTimeoutRetry(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName,
            int attempts) {
        if (!validateTimeoutRetryConditions(player, state, attempts)) {
            return false;
        }

        state.retryAttempts().incrementAndGet();
        if (isStale(state)) {
            return false;
        }
        player.sendMessage(messages.component("connection.retry", NamedTextColor.YELLOW));

        scheduleTimeoutRetry(player, state, targetServer, serverName);
        return true;
    }

    private boolean validateTimeoutRetryConditions(
            Player player, PlayerTransferState state, int attempts) {
        if (isStale(state) || !player.isActive() || attempts >= MAX_RETRY_ATTEMPTS) {
            return false;
        }
        if (!state.timeoutRetryActive().compareAndSet(false, true)) {
            return false;
        }
        return !isStale(state);
    }

    private void scheduleTimeoutRetry(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName) {
        scheduleOwnedTask(state, state.timeoutRetry(), 400, TimeUnit.MILLISECONDS,
                () -> executeTimeoutRetry(player, state, targetServer, serverName));
    }

    private void executeTimeoutRetry(
            Player player,
            PlayerTransferState state,
            RegisteredServer targetServer,
            String serverName) {
        try {
            if (isStale(state)) {
                return;
            }
            if (!player.isActive() || !isPlayerOnAuthServer(player)) {
                state.timeoutRetryActive().set(false);
                return;
            }
            if (!claimBackendConnection(state)) {
                if (!isStale(state)) {
                    state.timeoutRetryActive().set(false);
                }
                logger.debug("{} 的后端传送已在进行中——跳过超时重试",
                        player.getUsername());
                return;
            }
            if (isStale(state)) {
                return;
            }
            CompletableFuture<ConnectionRequestBuilder.Result> connection =
                    startConnectionIfCurrent(player, state, targetServer);
            if (connection == null) {
                releaseBackendConnection(state);
                return;
            }
            connection.whenComplete((result, failure) -> {
                try {
                    handleTimeoutRetryResult(player, state, serverName, result, failure);
                } finally {
                    releaseBackendConnection(state);
                }
            });
        } catch (RuntimeException failure) {
            releaseBackendConnection(state);
            if (isStale(state)) {
                return;
            }
            state.timeoutRetryActive().set(false);
            logger.error("{} 超时后安排重试出错：{}",
                    player.getUsername(), failure.getMessage());
        }
    }

    private void handleTimeoutRetryResult(
            Player player,
            PlayerTransferState state,
            String serverName,
            ConnectionRequestBuilder.Result result,
            Throwable failure) {
        if (isStale(state)) {
            return;
        }
        state.timeoutRetryActive().set(false);

        if (failure != null) {
            logger.warn("超时重试失败 {} -> {}：{}",
                    player.getUsername(), serverName, failure.getMessage());
            sendErrorMessageIfCurrent(player, state);
            return;
        }

        if (result != null && result.isSuccessful()) {
            if (!resetTasksIfCurrent(state, false)) {
                return;
            }
            state.retryAttempts().set(0);
            if (logger.isDebugEnabled()) {
                logger.debug("超时重试成功 {} -> {}",
                        player.getUsername(), serverName);
            }
        } else {
            String reason = KickReasonRenderer.renderPlain(result);
            logger.warn("超时重试未成功 {} -> {}：{}",
                    player.getUsername(), serverName, reason);
            sendErrorMessageIfCurrent(player, state);
        }
    }

    void autoTransferFromAuthServerToBackend(Player player) {
        PlayerTransferState state = currentState(player);
        if (state == null || isStale(state)) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        String playerIp = getPlayerIp(player);
        CachedAuthUser cachedUser = authCache.getAuthorizedPlayer(playerUuid);

        if (cachedUser == null || !cachedUser.matchesIp(playerIp) || isStale(state)) {
            if (logger.isDebugEnabled()) {
                logger.debug("自动转服：玩家 {} 未在缓存中通过验证",
                        player.getUsername());
            }
            return;
        }

        if (!authCache.hasActiveSession(playerUuid, player.getUsername(), playerIp)) {
            if (logger.isDebugEnabled()) {
                logger.debug("自动转服：玩家 {} 没有有效会话——需要重新登录",
                        player.getUsername());
            }
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("自动转服：玩家 {} 已通过验证——安排传送到后端",
                    player.getUsername());
        }

        scheduleOwnedTask(state, state.pendingTransfer(),
                settings.getAutoTransferDelayMillis(), TimeUnit.MILLISECONDS,
                () -> runDelayedAutoTransfer(player, state));
    }

    private void runDelayedAutoTransfer(Player player, PlayerTransferState state) {
        if (isStale(state)) {
            return;
        }
        if (!player.isActive()) {
            logger.debug("自动转服：玩家 {} 已不在线", player.getUsername());
            return;
        }
        if (!isPlayerOnAuthServer(player)) {
            logger.debug("自动转服：玩家 {} 已不在认证服", player.getUsername());
            return;
        }
        VirtualThreadExecutorProvider.submitTask(() -> executeAutoTransfer(player, state));
    }

    private void executeAutoTransfer(Player player, PlayerTransferState state) {
        if (isStale(state)) {
            return;
        }
        BackendTransferOutcome outcome = transfer(player, state);
        if (isStale(state) && !outcome.accepted()) {
            return;
        }
        if (outcome.accepted()) {
            logger.debug("自动转服：玩家 {} 已传送到后端", player.getUsername());
        } else {
            logger.warn("自动转服：把玩家 {} 传送到后端失败",
                    player.getUsername());
        }
    }

    private String getPlayerIp(Player player) {
        var address = player.getRemoteAddress();
        if (address instanceof InetSocketAddress inetAddress) {
            return inetAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private PlayerTransferState currentState(Player player) {
        return lifecycle.currentState(player);
    }

    private boolean isStale(PlayerTransferState state) {
        return lifecycle.isStale(state);
    }

    private boolean resetTasksIfCurrent(
            PlayerTransferState state, boolean clearForcedHostTarget) {
        return lifecycle.resetTasksIfCurrent(state, clearForcedHostTarget);
    }

    private boolean finishIfCurrent(
            PlayerTransferState state, boolean clearForcedHostTarget) {
        return lifecycle.finishIfCurrent(state, clearForcedHostTarget);
    }

    private CompletableFuture<ConnectionRequestBuilder.Result> startConnectionIfCurrent(
            Player player, PlayerTransferState state, RegisteredServer targetServer) {
        return lifecycle.startConnectionIfCurrent(player, state, targetServer);
    }

    private RegisteredServer validateAndGetAuthServer(Player player, PlayerTransferState state) {
        return lifecycle.validateAndGetAuthServer(player, state);
    }

    private boolean isPlayerOnAuthServer(Player player) {
        return lifecycle.isPlayerOnAuthServer(player);
    }

    private boolean scheduleOwnedTask(
            PlayerTransferState state,
            AtomicReference<ScheduledTask> taskSlot,
            long delay,
            TimeUnit unit,
            Runnable action) {
        return lifecycle.scheduleOwnedTask(state, taskSlot, delay, unit, action);
    }
}
