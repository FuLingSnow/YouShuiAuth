package io.github.fulingsnow.youshuiauth.paper.quarantine;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.paper.PaperAuthPlayer;
import io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.PlatformScheduler;
import io.github.fulingsnow.youshuiauth.runtime.AuthRuntime;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冻结隔离区：未认证玩家被固定在配置点，禁止移动、交互、破坏与除白名单外的命令；
 * 认证完成后解除限制，玩家留在当前位置（正式出生点由服务端既有生成逻辑决定）。
 */
public final class QuarantineService implements Listener {

    private final Plugin plugin;
    private final AuthRuntime runtime;
    private final World world;
    private final Location anchor;
    /** 聊天被拦截时的提示冷却，避免刷屏。 */
    private static final long CHAT_NOTICE_COOLDOWN_MILLIS = 2_000L;
    /** 巡检每 200ms 一次；提醒间隔取 core 的统一值。 */
    private static final int REMINDER_TICKS = Math.max(1,
            io.github.fulingsnow.youshuiauth.runtime.AuthDeadlinePolicy
                    .REMINDER_INTERVAL_SECONDS * 5);
    private final Map<java.util.UUID, Long> chatNotices = new ConcurrentHashMap<>();
    /**
     * 该玩家是否已有账号。未登录提醒的文案与按钮依赖它：
     * 已注册给 /login，未注册给 /register 或 /qqbind。
     */
    private final Map<java.util.UUID,
            io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind> accountKinds =
            new ConcurrentHashMap<>();

    /** 水平回拉容差（2.5 格）：位移在此范围内不重新传送，避免抖动。 */
    private static final double PULL_TOLERANCE = 2.5;

    private final Map<java.util.UUID, PlatformScheduler.TaskHandle> watchers = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, Boolean> previousAllowFlight = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, Map<org.bukkit.block.Block, org.bukkit.block.data.BlockData>> placedPlatforms =
            new ConcurrentHashMap<>();

    public QuarantineService(Plugin plugin, AuthRuntime runtime, World world, Location anchor) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.world = world;
        this.anchor = anchor;
    }

    static boolean isAllowedCommand(String rawCommand) {
        return io.github.fulingsnow.youshuiauth.platform.quarantine.QuarantinePolicy
                .isAllowedCommand(rawCommand);
    }

    private boolean isAuthenticated(Player player) {
        return AuthRuntime.shouldRestoreSession(runtime.settings(), runtime.authCache(),
                PaperAuthPlayer.of(player), ipOf(player));
    }

    private String ipOf(Player player) {
        return io.github.fulingsnow.youshuiauth.util.PlayerAddressUtils
                .getPlayerIp(PaperAuthPlayer.of(player));
    }

    private boolean requestDeviceApproval(Player player) {
        var flow = runtime.deviceApprovalFlow();
        if (flow == null || !flow.isEnabled() || flow.isPending(player.getUniqueId())) {
            return false;
        }
        var auth = PaperAuthPlayer.of(player);
        var operation = runtime.connections().capture(auth);
        if (!flow.request(auth, operation)) {
            return false;
        }
        int ttl = runtime.settings().getQqSettings().getDeviceApprovalTimeoutSeconds();
        player.sendMessage(io.github.fulingsnow.youshuiauth.i18n.ClientMessages.of(PaperAuthPlayer.of(player), runtime.settings(), runtime.messages(), "qq.device_approval.pending",
                net.kyori.adventure.text.format.NamedTextColor.YELLOW, ttl));
        runtime.platformAccess().scheduler().scheduleOnce(
                () -> flow.cancel(player.getUniqueId()), java.time.Duration.ofSeconds(ttl));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        runtime.connections().beginSession(PaperAuthPlayer.of(player));
        if (isAuthenticated(player)) {
            return;
        }
        quarantine(player);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            accountKinds.put(player.getUniqueId(), resolveAccountKind(player));
            try {
                if (runtime.autoLogin().attempt(PaperAuthPlayer.of(player)).authorized()) {
                    return;
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().warning(runtime.messages().get(
                        "quarantine.warn.autologin.failed", player.getName(), failure));
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && watchers.containsKey(player.getUniqueId())) {
                    PaperAuthPlayer.of(player).sendMessage(promptWithButton(player));
                    requestDeviceApproval(player);
                }
            });
        });
    }

    private void quarantine(Player player) {
        plugin.getLogger().info(runtime.messages().get("quarantine.info.isolated",
                player.getName(), runtime.settings().isSessionCacheEnabled(),
                runtime.settings().isAutoLoginEnabled()));
        hide(player);
        player.teleport(anchor);
        watch(player);
        runtime.timeouts().beginSession(PaperAuthPlayer.of(player));
    }

    private void hide(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.hidePlayer(plugin, player);
            }
        }
        previousAllowFlight.put(player.getUniqueId(), player.getAllowFlight());
        player.setInvulnerable(true);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGravity(true);
        placePlatform(player);
    }

    private void placePlatform(Player player) {
        java.util.Map<org.bukkit.block.Block, org.bukkit.block.data.BlockData> placed =
                new java.util.LinkedHashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                org.bukkit.block.Block below = anchor.clone().add(dx, -1.0, dz).getBlock();
                if (below.getType().isAir()) {
                    placed.put(below, below.getBlockData());
                    below.setType(org.bukkit.Material.BARRIER, false);
                }
            }
        }
        if (!placed.isEmpty()) {
            placedPlatforms.put(player.getUniqueId(), placed);
        }
    }

    private void removePlatform(Player player) {
        restorePlatform(placedPlatforms.remove(player.getUniqueId()));
    }

    /** 只还原本插件放下的屏障，别的插件/玩家改过的方块不动。 */
    private static void restorePlatform(
            java.util.Map<org.bukkit.block.Block, org.bukkit.block.data.BlockData> placed) {
        if (placed == null) {
            return;
        }
        placed.forEach((block, data) -> {
            if (block.getType() == org.bukkit.Material.BARRIER) {
                block.setBlockData(data, false);
            }
        });
    }

    /** 插件卸载/服务器关闭时兜底还原，避免屏障残留到世界文件里。 */
    public void restoreAllPlatforms() {
        placedPlatforms.values().forEach(QuarantineService::restorePlatform);
        placedPlatforms.clear();
    }

    /** 与 Velocity 的提醒形态一致：文案按账号状态选键，后接可点击的 /login 按钮。 */
    private net.kyori.adventure.text.Component promptWithButton(Player player) {
        var authPlayer = PaperAuthPlayer.of(player);
        var reminder = io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.reminder(
                accountKinds.getOrDefault(player.getUniqueId(),
                        io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind.UNKNOWN),
                runtime.settings().isPasswordRegistrationEnabled(),
                runtime.settings().getQqSettings().isEnabled());
        net.kyori.adventure.text.Component prompt =
                io.github.fulingsnow.youshuiauth.i18n.ClientMessages.of(authPlayer,
                        runtime.settings(), runtime.messages(), reminder.messageKey(),
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW);
        if (!reminder.hasButton()) {
            return prompt;
        }
        net.kyori.adventure.text.Component button = runtime.messages()
                .component(reminder.buttonKey(),
                        net.kyori.adventure.text.format.NamedTextColor.AQUA)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent
                        .runCommand(reminder.command()));
        return prompt.append(net.kyori.adventure.text.Component.space()).append(button);
    }

    /**
     * 判断该玩家属于哪种账号：未注册 / 有密码 / 绑定了 QQ / 仅 QQ。
     * 提醒的文案与按钮依赖它——已注册的 QQ 账号不能提示「用密码登录」。
     */
    private io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind
            resolveAccountKind(Player player) {
        var unknown = io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind.UNKNOWN;
        try {
            var result = runtime.databaseManager().findPlayerByNickname(player.getName()).join();
            if (result == null || result.isDatabaseError()) {
                return unknown;
            }
            var account = result.getValue();
            if (account == null) {
                return io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind.NONE;
            }
            if (io.github.fulingsnow.youshuiauth.qq.QqAuthFlow.QQ_ONLY_HASH
                    .equals(account.getHash())) {
                return io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind.QQ_ONLY;
            }
            var dao = runtime.databaseManager().getQqDao();
            if (dao != null && dao.findByUuid(io.github.fulingsnow.youshuiauth.platform.QqOps.PLATFORM_QQ,
                    player.getUniqueId().toString()).isPresent()) {
                return io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind.QQ_BOUND;
            }
            return io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy.AccountKind.PASSWORD;
        } catch (java.sql.SQLException | RuntimeException failure) {
            plugin.getLogger().warning(runtime.messages().get(
                    "quarantine.warn.autologin.failed", player.getName(), failure));
            return unknown;
        }
    }

    private void watch(Player player) {
        long quarantineStartedAt = System.currentTimeMillis();
        long[] nextReminderAt = {System.currentTimeMillis()
                + io.github.fulingsnow.youshuiauth.runtime.AuthDeadlinePolicy
                .INITIAL_REMINDER_DELAY_SECONDS * 1000L};
        watchers.put(player.getUniqueId(), runtime.platformAccess().scheduler()
                .scheduleRepeating(() -> {
                    if (!player.isOnline()) {
                        PlatformScheduler.TaskHandle self = watchers.remove(player.getUniqueId());
                        if (self != null) {
                            self.cancel();
                        }
                        previousAllowFlight.remove(player.getUniqueId());
                        return;
                    }

                    String ip = PlayerAddressUtils.getPlayerIp(PaperAuthPlayer.of(player));
                    var cached = runtime.authCache().findAuthorizedPlayer(player.getUniqueId());
                    boolean since = runtime.authCache().isPlayerAuthorizedSince(
                            player.getUniqueId(), ip, quarantineStartedAt);
                    if (cached.isPresent() && plugin.getLogger().isLoggable(
                            java.util.logging.Level.FINE)) {
                        plugin.getLogger().fine(runtime.messages().get(
                                "quarantine.debug.release.check", player.getName(), since,
                                cached.get().getCacheTime(), quarantineStartedAt,
                                cached.get().getLoginIp(), ip));
                    }
                    if (System.currentTimeMillis() >= nextReminderAt[0]) {
                        nextReminderAt[0] = System.currentTimeMillis()
                                + io.github.fulingsnow.youshuiauth.runtime.AuthDeadlinePolicy
                                .REMINDER_INTERVAL_SECONDS * 1000L;
                        PaperAuthPlayer.of(player).sendMessage(promptWithButton(player));
                    }
                    if (since) {
                        release(player);
                    } else {

                        Location current = player.getLocation();
                        double dx = current.getX() - anchor.getX();
                        double dz = current.getZ() - anchor.getZ();

                        if (current.getY() < world.getMinHeight() - 8
                                || io.github.fulingsnow.youshuiauth.platform.quarantine
                                        .QuarantinePolicy.isOutsideAnchor(
                                                dx, 0.0, dz, PULL_TOLERANCE)) {
                            player.teleport(anchor);
                        }
                    }
                }, Duration.ofMillis(200), Duration.ofMillis(200)));
    }

    private void release(Player player) {
        runtime.timeouts().cancel(player.getUniqueId());
        PlatformScheduler.TaskHandle handle = watchers.remove(player.getUniqueId());
        if (handle != null) {
            handle.cancel();
        }
        player.setInvulnerable(false);
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setGravity(true);
        Boolean allowFlight = previousAllowFlight.remove(player.getUniqueId());
        player.setAllowFlight(allowFlight != null && allowFlight);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.showPlayer(plugin, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (watchers.containsKey(event.getPlayer().getUniqueId())
                && event.getTo() != null
                && event.getFrom().distanceSquared(event.getTo()) > 0
                && !isSameBlock(event.getFrom(), event.getTo())) {
            event.setTo(event.getFrom());
        }
    }

    private static boolean isSameBlock(Location from, Location to) {
        return io.github.fulingsnow.youshuiauth.platform.quarantine.QuarantinePolicy
                .isSameBlock(from.getX(), from.getY(), from.getZ(),
                        to.getX(), to.getY(), to.getZ());
    }

    /**
     * 未认证玩家禁止发言：否则隔离区可以被用于刷屏/骚扰。
     * AsyncChatEvent 是异步事件，取消本身线程安全；提示统一交给消息中继在主线程发出。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!watchers.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = chatNotices.get(player.getUniqueId());
        if (last == null || now - last > CHAT_NOTICE_COOLDOWN_MILLIS) {
            chatNotices.put(player.getUniqueId(), now);
            PaperAuthPlayer.of(player).sendMessage(io.github.fulingsnow.youshuiauth.i18n.ClientMessages.of(PaperAuthPlayer.of(player), runtime.settings(), runtime.messages(),
                    io.github.fulingsnow.youshuiauth.runtime.AuthPromptPolicy
                            .promptKey(runtime.settings().isPasswordRegistrationEnabled(),
                                runtime.settings().getQqSettings().isEnabled()),
                    NamedTextColor.YELLOW));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (watchers.containsKey(event.getPlayer().getUniqueId())
                && !isAllowedCommand(event.getMessage())) {
            event.setCancelled(true);
            runtime.sendPermissionDenied(PaperAuthPlayer.of(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (watchers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (watchers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (watchers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && watchers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (watchers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && watchers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        accountKinds.remove(event.getPlayer().getUniqueId());
        io.github.fulingsnow.youshuiauth.paper.PaperMessageRelay.clear(
                event.getPlayer().getUniqueId());
        PlatformScheduler.TaskHandle handle = watchers.remove(event.getPlayer().getUniqueId());
        if (handle != null) {
            handle.cancel();
        }
        previousAllowFlight.remove(event.getPlayer().getUniqueId());
        removePlatform(event.getPlayer());

        runtime.authCache().invalidatePlayerData(event.getPlayer().getUniqueId());
        runtime.authCache().endSession(event.getPlayer().getUniqueId());
        chatNotices.remove(event.getPlayer().getUniqueId());
        runtime.connections().retire(PaperAuthPlayer.of(event.getPlayer()));

        runtime.pendingTotpStore().invalidate(event.getPlayer().getUniqueId());
        var approvalFlow = runtime.deviceApprovalFlow();
        if (approvalFlow != null) {
            approvalFlow.cancel(event.getPlayer().getUniqueId());
        }
    }

    public World world() {
        return world;
    }

    public Location anchor() {
        return anchor.clone();
    }

    AuthPlayer viewOf(Player player) {
        return PaperAuthPlayer.of(player);
    }
}
