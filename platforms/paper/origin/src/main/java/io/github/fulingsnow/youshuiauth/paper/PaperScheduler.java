package io.github.fulingsnow.youshuiauth.paper;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bukkit 调度器适配：所有时长按 20 tick/秒 换算，至少 1 tick。
 */
public final class PaperScheduler implements PlatformScheduler {

    private final Plugin plugin;

    public PaperScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    private static long ticks(Duration duration) {
        return Math.max(1L, duration.toMillis() / 50L);
    }

    @Override
    public TaskHandle scheduleOnce(Runnable task, Duration delay) {
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, ticks(delay)));
    }

    @Override
    public TaskHandle scheduleRepeating(Runnable task, Duration delay, Duration period) {
        return wrap(Bukkit.getScheduler().runTaskTimer(
                plugin, task, ticks(delay), ticks(period)));
    }

    @Override
    public TaskHandle scheduleForPlayer(AuthPlayer player, Consumer<TaskHandle> assign,
                                        Runnable task, Duration delay) {
        TaskHandle handle = scheduleOnce(task, delay);
        assign.accept(handle);
        return handle;
    }

    private static TaskHandle wrap(BukkitTask task) {
        return new TaskHandle() {
            private final AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void cancel() {
                cancelled.set(true);
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get() || task.isCancelled();
            }
        };
    }
}
