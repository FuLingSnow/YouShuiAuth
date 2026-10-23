package io.github.fulingsnow.youshuiauth.runtime;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.PlatformScheduler;
import io.github.fulingsnow.youshuiauth.platform.TimeoutOps;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 基于平台调度器的会话超时：每个玩家最多一个待触发任务，
 * 到期时回调 {@code onExpired}（平台据此踢出并清理会话）。
 */
public final class SchedulerTimeoutOps implements TimeoutOps {

    private final PlatformScheduler scheduler;
    private final Duration sessionTimeout;
    private final Consumer<UUID> onExpired;
    /** 会话起点：续期时以它为基准，避免每次 /qqbind 都从「现在」重新计时。 */
    private final Map<UUID, Long> sessionStarts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlatformScheduler.TaskHandle> timers =
            new ConcurrentHashMap<>();

    public SchedulerTimeoutOps(PlatformScheduler scheduler, Duration sessionTimeout,
                               Consumer<UUID> onExpired) {
        this.scheduler = scheduler;
        this.sessionTimeout = sessionTimeout;
        this.onExpired = onExpired;
    }

    /**
     * 拿到 QQ 绑定/登录码后延长未认证窗口：取「原会话基线」与「验证码窗口+宽限」的较大者，
     * 并按会话起点做 10 分钟截断——与 Velocity 端完全一致的算法。
     * 此前这里直接用验证码 TTL 覆盖定时器，会把会话缩短到验证码有效期，
     * 玩家还没在群里完成绑定就被踢出。
     */
    @Override
    public void renewForCode(AuthPlayer player, int codeTtlSeconds) {
        if (player == null || codeTtlSeconds <= 0) {
            return;
        }
        UUID playerId = player.uniqueId();
        Long sessionStart = sessionStarts.get(playerId);
        if (sessionStart == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long deadline = io.github.fulingsnow.youshuiauth.runtime.AuthDeadlinePolicy
                .renewDeadline(now, sessionStart, now + sessionTimeout.toMillis(),
                        sessionTimeout.toMillis(), codeTtlSeconds);
        schedule(playerId, Duration.ofMillis(Math.max(1L, deadline - now)));
    }

    @Override
    public void beginSession(AuthPlayer player) {
        if (player != null) {
            long now = System.currentTimeMillis();
            sessionStarts.put(player.uniqueId(), now);
            long deadline = io.github.fulingsnow.youshuiauth.runtime.AuthDeadlinePolicy
                    .initialDeadline(now, now, sessionTimeout.toMillis());
            schedule(player.uniqueId(), Duration.ofMillis(Math.max(1L, deadline - now)));
        }
    }

    @Override
    public void suspend(UUID playerId) {
        cancelTimer(playerId);
    }

    @Override
    public void cancel(UUID playerId) {
        cancelTimer(playerId);
        sessionStarts.remove(playerId);
    }

    private void schedule(UUID playerId, Duration delay) {
        cancelTimer(playerId);
        sessionStarts.putIfAbsent(playerId, System.currentTimeMillis());
        PlatformScheduler.TaskHandle handle = scheduler.scheduleOnce(() -> {
            timers.remove(playerId);
            onExpired.accept(playerId);
        }, delay);
        timers.put(playerId, handle);
    }

    private void cancelTimer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlatformScheduler.TaskHandle handle = timers.remove(playerId);
        if (handle != null) {
            handle.cancel();
        }
    }
}
