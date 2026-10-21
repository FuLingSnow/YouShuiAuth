package net.rafalohaki.veloauth.connection;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.rafalohaki.veloauth.VeloAuth;
import net.rafalohaki.veloauth.cache.AuthCache;
import net.rafalohaki.veloauth.config.Settings;
import net.rafalohaki.veloauth.i18n.Messages;
import net.rafalohaki.veloauth.util.PlayerAddressUtils;
import static net.rafalohaki.veloauth.util.PlayerMessages.playerMessage;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class AuthTimeoutScheduler {

    private static final Marker AUTH_MARKER = MarkerFactory.getMarker("AUTH");

    /**
     * How often the login/register reminder repeats. The one-shot prompt from
     * {@code AuthListener.sendAuthInstructions} can be lost on the initial join
     * (see issue #48); this cadence keeps the player informed without spamming
     * within the default 300s auth timeout window.
     */
    private static final int REMINDER_INTERVAL_SECONDS = 10;
    /**
     * The one-shot prompt sent from {@code ServerConnectedEvent} is frequently dropped because
     * the client is still finishing the join screen. Send the first reminder almost immediately
     * (after the world is visible) and then keep the normal cadence.
     */
    private static final int INITIAL_REMINDER_DELAY_SECONDS = 1;
    private static final String GENERIC_PROMPT_KEY = "auth.prompt.generic";
    static final long MAX_SESSION_WINDOW_MILLIS = 600_000L;
    static final int RENEWAL_GRACE_SECONDS = 30;

    private final VeloAuth plugin;
    private final Settings settings;
    private final Messages messages;
    private final AuthCache authCache;
    private final ConnectionManager connectionManager;
    private final Logger logger;
    private final ConcurrentMap<UUID, ScheduledTask> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ScheduledTask> reminders = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> sessionStarts = new ConcurrentHashMap<>();
    /** Account-status-aware reminder content, filled in by AuthListener once the nickname
     *  lookup resolves; absent entry means "status unknown — use the generic prompt". */
    private final ConcurrentMap<UUID, ReminderPrompt> reminderPrompts = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AuthTimeoutScheduler(VeloAuth plugin, Settings settings, Messages messages,
                                AuthCache authCache, ConnectionManager connectionManager) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.authCache = authCache;
        this.connectionManager = connectionManager;
        this.logger = plugin.getLogger();
    }

    public void schedule(Player player) {
        int seconds = settings.getAuthServerTimeoutSeconds();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        lifecycleLock.lock();
        try {
            if (closed.get()) {
                return;
            }
            long sessionStart = sessionStarts.computeIfAbsent(uuid, ignored -> now);
            scheduleReminderLocked(player, uuid);
            if (seconds > 0) {
                long base = seconds * 1000L;
                armLocked(player, uuid, sessionStart,
                        Math.max(deadlines.getOrDefault(uuid, 0L), now + base));
            } else {
                ScheduledTaskRegistry.cancel(pending, uuid);
                deadlines.remove(uuid);
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (logger.isDebugEnabled()) {
            logger.debug(AUTH_MARKER, "Auth timeout armed for {} (base {}s, {} ms left)",
                    player.getUsername(), seconds, remainingMillis(uuid));
        }
    }

    public void renewForCode(Player player, int codeTtlSeconds) {
        if (player == null || codeTtlSeconds <= 0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        lifecycleLock.lock();
        try {
            if (closed.get() || !deadlines.containsKey(uuid)) {
                return;
            }
            long sessionStart = sessionStarts.getOrDefault(uuid, now);
            long base = Math.max(0L, settings.getAuthServerTimeoutSeconds()) * 1000L;
            long codeWindow = (codeTtlSeconds + RENEWAL_GRACE_SECONDS) * 1000L;
            long candidate = Math.max(deadlines.getOrDefault(uuid, 0L),
                    Math.max(sessionStart + base, now + codeWindow));
            armLocked(player, uuid, sessionStart, candidate);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void beginSession(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        lifecycleLock.lock();
        try {
            if (closed.get()) {
                return;
            }
            sessionStarts.putIfAbsent(uuid, now);
            long base = Math.max(0L, settings.getAuthServerTimeoutSeconds()) * 1000L;
            deadlines.putIfAbsent(uuid, now + base);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void suspend(UUID uuid) {
        if (uuid == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            ScheduledTaskRegistry.cancel(pending, uuid);
            ScheduledTaskRegistry.cancel(reminders, uuid);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void armLocked(Player player, UUID uuid, long sessionStart, long candidateDeadline) {
        long now = System.currentTimeMillis();
        long deadline = Math.min(candidateDeadline, sessionStart + MAX_SESSION_WINDOW_MILLIS);
        if (deadline <= now) {
            deadline = now + 1_000L;
        }
        deadlines.put(uuid, deadline);
        long remaining = Math.max(1_000L, deadline - now);
        scheduleKickLocked(player, uuid, remaining, (int) ((remaining + 999L) / 1000L));
    }

    private long remainingMillis(UUID uuid) {
        Long deadline = deadlines.get(uuid);
        return deadline == null ? 0L : Math.max(0L, deadline - System.currentTimeMillis());
    }

    private void scheduleKickLocked(Player player, UUID uuid, long delayMillis, int windowSeconds) {
        ScheduledTaskRegistry.replace(pending, uuid, callback ->
                plugin.getServer().getScheduler().buildTask(plugin, callback)
                        .delay(Math.max(1L, delayMillis), TimeUnit.MILLISECONDS)
                        .schedule(), () -> {
            if (closed.get() || !player.isActive()) {
                return;
            }
            // Re-check: maybe player authenticated in the meantime but cancel() didn't fire
            // (e.g. external session restore). Verify against cache + current server.
            if (isAuthorizedAndStillOnAuthServer(player)) {
                return;
            }
            if (!connectionManager.isPlayerOnAuthServer(player)) {
                return; // already moved on, nothing to do
            }

            player.disconnect(playerMessage(player, settings, messages, "auth.timeout.kick", NamedTextColor.RED, windowSeconds));
            if (logger.isInfoEnabled()) {
                logger.info(AUTH_MARKER,
                        "Kicked player {} after {}s auth timeout (no login/register)",
                        player.getUsername(), windowSeconds);
            }
        });
    }

    private void scheduleReminderLocked(Player player, UUID uuid) {
        ScheduledTask task = plugin.getServer().getScheduler()
                .buildTask(plugin, (Consumer<ScheduledTask>) self -> runReminder(player, self))
                .delay(INITIAL_REMINDER_DELAY_SECONDS, TimeUnit.SECONDS)
                .repeat(REMINDER_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .schedule();
        ScheduledTask previous = reminders.put(uuid, task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void runReminder(Player player, ScheduledTask self) {
        if (closed.get() || !player.isActive()
                || isAuthorizedAndStillOnAuthServer(player)
                || !connectionManager.isPlayerOnAuthServer(player)) {
            reminders.remove(player.getUniqueId(), self);
            reminderPrompts.remove(player.getUniqueId());
            self.cancel();
            return;
        }
        ReminderPrompt prompt = reminderPrompts.getOrDefault(player.getUniqueId(), ReminderPrompt.GENERIC);
        Component message = playerMessage(player, settings, messages, prompt.key(), prompt.color());
        if (prompt.command() != null) {
            message = message.append(Component.space()).append(buttonFor(prompt.command()));
        }
        player.sendMessage(message);
    }

    private Component buttonFor(String command) {
        String labelKey = "/qqbind".equals(command) ? "auth.button.qqbind" : "auth.button.login";
        return messages.component(labelKey, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command));
    }

    /**
     * Switches the repeating reminder to the account-status-specific prompt (issue #54).
     * Ignored when no timeout is armed for the player, so a lookup finishing after
     * disconnect cannot leave a stale entry behind.
     */
    public void useReminderPrompt(UUID uuid, String key, NamedTextColor color) {
        useReminderPrompt(uuid, key, color, null);
    }

    /**
     * Same as {@link #useReminderPrompt(UUID, String, NamedTextColor)} but also renders a
     * clickable button that executes {@code command}.
     */
    public void useReminderPrompt(UUID uuid, String key, NamedTextColor color, String command) {
        if (uuid == null || key == null) {
            return;
        }
        if (pending.containsKey(uuid) || reminders.containsKey(uuid)) {
            reminderPrompts.put(uuid, new ReminderPrompt(key, color, command));
        }
    }

    private boolean isAuthorizedAndStillOnAuthServer(Player player) {
        // If the cache says the player is authorized for their current IP, they have authenticated
        // — the cancel() call must have raced with the scheduler. Skip the kick.
        // An expired session means the player must log in again, so reminders and the kick
        // still apply despite a surviving authorization entry.
        String ip = PlayerAddressUtils.getPlayerIp(player);
        return authCache.isPlayerAuthorized(player.getUniqueId(), ip)
                && authCache.hasActiveSession(player.getUniqueId(), player.getUsername(), ip);
    }

    public void cancel(UUID uuid) {
        ScheduledTaskRegistry.cancel(pending, uuid);
        ScheduledTaskRegistry.cancel(reminders, uuid);
        reminderPrompts.remove(uuid);
        deadlines.remove(uuid);
        sessionStarts.remove(uuid);
    }

    /**
     * Cancels every pending timeout. Called during plugin shutdown.
     */
    public void shutdown() {
        lifecycleLock.lock();
        try {
            if (closed.compareAndSet(false, true)) {
                ScheduledTaskRegistry.cancelAll(pending);
                ScheduledTaskRegistry.cancelAll(reminders);
                reminderPrompts.clear();
                deadlines.clear();
                sessionStarts.clear();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private record ReminderPrompt(String key, NamedTextColor color, String command) {
        private static final ReminderPrompt GENERIC =
                new ReminderPrompt(GENERIC_PROMPT_KEY, NamedTextColor.YELLOW, null);
    }
}
