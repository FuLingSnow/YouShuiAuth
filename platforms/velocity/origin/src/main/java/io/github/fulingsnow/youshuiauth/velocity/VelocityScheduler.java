package io.github.fulingsnow.youshuiauth.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.PlatformScheduler;

import java.time.Duration;
import java.util.function.Consumer;

public final class VelocityScheduler implements PlatformScheduler {

    private final Object plugin;
    private final Scheduler scheduler;

    public VelocityScheduler(Object plugin, ProxyServer server) {
        this.plugin = plugin;
        this.scheduler = server.getScheduler();
    }

    @Override
    public TaskHandle scheduleOnce(Runnable task, Duration delay) {
        return handle(scheduler.buildTask(plugin, task)
                .delay(Math.max(0L, delay.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)
                .schedule());
    }

    @Override
    public TaskHandle scheduleRepeating(Runnable task, Duration delay, Duration period) {
        return handle(scheduler.buildTask(plugin, task)
                .delay(Math.max(0L, delay.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)
                .repeat(Math.max(1L, period.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS)
                .schedule());
    }

    @Override
    public TaskHandle scheduleForPlayer(AuthPlayer player, Consumer<TaskHandle> assign, Runnable task,
                                       Duration delay) {
        TaskHandle handle = scheduleOnce(task, delay);
        if (assign != null) {
            assign.accept(handle);
        }
        return handle;
    }

    private static TaskHandle handle(ScheduledTask scheduled) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        return new TaskHandle() {
            @Override
            public void cancel() {
                scheduled.cancel();
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }
}
