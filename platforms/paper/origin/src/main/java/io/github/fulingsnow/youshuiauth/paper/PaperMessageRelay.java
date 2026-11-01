package io.github.fulingsnow.youshuiauth.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 玩家消息中继：所有玩家可见提示统一由「每 tick 的定时任务」发出。
 *
 * <p>为什么需要它：服务端平台在「事件处理中」或「异步线程」里直接调用
 * {@code Player#sendMessage} 时，重连后的客户端常常收不到（实测：功能生效但无任何提示，
 * 连原版的「无权限」都不显示）；而在调度任务里发出的提示始终可见。
 * 因此这里把消息统一排队，由定时器在主线程送达。</p>
 */
public final class PaperMessageRelay {

    private static final int MAX_QUEUE = 50;

    private static final Map<UUID, Deque<Component>> QUEUE = new ConcurrentHashMap<>();
    private static volatile boolean running;

    private PaperMessageRelay() {
    }

    /** 插件启用时调用一次；未启动时消息退回直发，避免整体不可用。 */
    public static void start(Plugin plugin) {
        if (running) {
            return;
        }
        running = true;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                PaperMessageRelay::flush, 1L, 1L);
        if (task == null) {
            running = false;
        }
    }

    public static void enqueue(Player player, Component message) {
        if (!running) {
            player.sendMessage(message);
            return;
        }
        Deque<Component> queue = QUEUE.computeIfAbsent(player.getUniqueId(),
                ignored -> new ConcurrentLinkedDeque<>());
        if (queue.size() >= MAX_QUEUE) {
            queue.pollFirst();
        }
        queue.addLast(message);
    }

    private static void flush() {
        for (Map.Entry<UUID, Deque<Component>> entry : QUEUE.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                QUEUE.remove(entry.getKey());
                continue;
            }
            Component message;
            while ((message = entry.getValue().pollFirst()) != null) {
                try {
                    player.sendMessage(message);
                } catch (RuntimeException ignored) {

                }
            }
        }
    }

    public static void clear(UUID playerId) {
        QUEUE.remove(playerId);
    }
}
