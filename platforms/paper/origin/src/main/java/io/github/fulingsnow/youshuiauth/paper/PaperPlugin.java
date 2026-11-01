package io.github.fulingsnow.youshuiauth.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.fulingsnow.youshuiauth.command.AuthCommands;
import io.github.fulingsnow.youshuiauth.paper.quarantine.QuarantineService;
import io.github.fulingsnow.youshuiauth.runtime.AuthRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class PaperPlugin extends JavaPlugin {

    private AuthRuntime runtime;
    private QuarantineService quarantine;
    private AuthCommands commands;
    private io.github.fulingsnow.youshuiauth.qq.QqBridgeServer qqBridge;
    /** 平台侧配置（隔离区/部署标识）。core 的内核配置占用 config.yml，两者不能共用同一文件。 */
    private static final String PLATFORM_CONFIG_VERSION = "1";
    private YamlConfiguration platformConfig;

    @Override
    public void onEnable() {
        saveResource("platform.yml", false);
        migratePlatformConfig();
        platformConfig = YamlConfiguration.loadConfiguration(
                new File(getDataFolder(), "platform.yml"));
        Path dataDirectory = getDataFolder().toPath();
        PaperScheduler scheduler = new PaperScheduler(this);
        PaperPlatformAccess access = new PaperPlatformAccess(scheduler, dataDirectory);

        try {
            runtime = new AuthRuntime(dataDirectory, getSLF4JLogger(), access,
                    new PaperTransferOps(), null, null, this::onSessionExpired);
        } catch (RuntimeException failure) {
            getSLF4JLogger().error("YouShuiAuth 启动失败", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PaperMessageRelay.start(this);
        commands = new AuthCommands(runtime, runtime.databaseManager(), runtime.authCache(),
                runtime.settings(), runtime.messages());

        World world = resolveQuarantineWorld();
        Location anchor = resolveQuarantineAnchor(world);
        PaperAuthPlayer.bindScheduler(runtime.platformAccess().scheduler());
        quarantine = new QuarantineService(this, runtime, world, anchor);
        Bukkit.getPluginManager().registerEvents(quarantine, this);

        bind("login", commands::canLogin, (sender, args) -> commands.login(sender, args));
        bind("register", commands::canRegister, (sender, args) -> commands.register(sender, args));
        bind("changepassword", commands::canChangePassword, (sender, args) -> commands.changePassword(sender, args));
        bind("setpassword", commands::canSetPassword, (sender, args) -> commands.setPassword(sender, args));
        bind("logout", (sender, args) -> commands.logout(sender, args));
        bind("2fa", commands::canUseTwoFactor, (sender, args) -> commands.twoFactor(sender, args));
        bind("unregister", (sender, args) -> commands.unregister(sender, args));
        io.github.fulingsnow.youshuiauth.command.QqBindCommandCore qqBind =
                new io.github.fulingsnow.youshuiauth.command.QqBindCommandCore(runtime.qq(), runtime.messages());
        bind("qqbind", qqBind::hasPermission, qqBind::execute);
        bind("ysauth", (sender, args) -> commands.admin(sender, args));

        startQqBridge();

        getSLF4JLogger().info("YouShuiAuth 已在 Paper 端启用（隔离点 {} {}）",
                world.getName(), anchor.toVector());
        getSLF4JLogger().info("已注册命令：{}", getDescription().getCommands().keySet());
        String deploymentId = resolveDeploymentId();
        io.github.fulingsnow.youshuiauth.runtime.DeploymentIdentity.warnIfUnset(
                getSLF4JLogger(), deploymentId, "paper");
        runtime.startInstanceHeartbeat(deploymentId, getPluginMeta().getVersion());
    }

    /**
     * 心跳锁与多端共存判定的前提是实例标识唯一；缺失时生成一次并写回 platform.yml，
     * 之后每次启动都复用同一个值（不会因为重启变成新实例）。
     */
    /** platform.yml 与 config.yml 共用合并逻辑：补新键、保用户值、写备份。 */
    private void migratePlatformConfig() {
        Path platformPath = new File(getDataFolder(), "platform.yml").toPath();
        String raw;
        try (java.io.Reader reader = getTextResource("platform.yml")) {
            if (reader == null) {
                return;
            }
            java.io.StringWriter buffer = new java.io.StringWriter();
            reader.transferTo(buffer);
            raw = buffer.toString();
        } catch (java.io.IOException e) {
            getSLF4JLogger().warn("读取内置 platform.yml 模板失败：{}", e.getMessage());
            return;
        }
        if (raw.isBlank()) {
            return;
        }
        String template = raw.replace("__PLATFORM_CONFIG_VERSION__", PLATFORM_CONFIG_VERSION);
        String deploymentIdBefore = readDeploymentId(platformPath);
        try {
            io.github.fulingsnow.youshuiauth.config.ConfigMigrator.migrateWithTemplate(
                    platformPath, template, PLATFORM_CONFIG_VERSION,
                    "platform-backup-", getSLF4JLogger());
        } catch (java.io.IOException e) {
            getSLF4JLogger().warn("platform.yml 迁移失败，按原文件继续：{}", e.getMessage());
        }
        restoreDeploymentId(platformPath, deploymentIdBefore);
    }

    /**
     * deployment.id 丢了会让两端各认证一次，所以迁移前后单独保护一次，
     * 与合并逻辑解耦。
     */
    private String readDeploymentId(Path platformPath) {
        if (!java.nio.file.Files.exists(platformPath)) {
            return null;
        }
        YamlConfiguration probe = YamlConfiguration.loadConfiguration(platformPath.toFile());
        String id = probe.getString("deployment.id", "");
        return id == null || id.isBlank() ? null : id.trim();
    }

    private void restoreDeploymentId(Path platformPath, String previous) {
        if (previous == null) {
            return;
        }
        YamlConfiguration probe = YamlConfiguration.loadConfiguration(platformPath.toFile());
        String current = probe.getString("deployment.id", "");
        if (current != null && !current.isBlank()) {
            return;
        }
        probe.set("deployment.id", previous);
        try {
            probe.save(platformPath.toFile());
            getSLF4JLogger().warn("platform.yml 迁移后丢失 deployment.id，已恢复为 {}", previous);
        } catch (java.io.IOException e) {
            getSLF4JLogger().warn("恢复 deployment.id 失败：{}", e.getMessage());
        }
    }

    private String resolveDeploymentId() {
        String configured = platformConfig.getString("deployment.id", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String generated = io.github.fulingsnow.youshuiauth.runtime.DeploymentIdentity
                .generate("paper");
        platformConfig.set("deployment.id", generated);
        try {
            platformConfig.save(new java.io.File(getDataFolder(), "platform.yml"));
            getSLF4JLogger().info("deployment.id 未配置，已自动生成并写入 platform.yml：{}",
                    generated);
        } catch (java.io.IOException e) {
            getSLF4JLogger().warn("deployment.id 自动生成后写回 platform.yml 失败：{}",
                    e.getMessage());
        }
        return generated;
    }

    private void bind(String name,
                      BiConsumer<io.github.fulingsnow.youshuiauth.platform.AuthSender, String[]> body) {
        bind(name, null, body);
    }

    private void bind(String name,
                      java.util.function.Predicate<io.github.fulingsnow.youshuiauth.platform.AuthSender>
                              visible,
                      BiConsumer<io.github.fulingsnow.youshuiauth.platform.AuthSender, String[]> body) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getSLF4JLogger().warn("plugin.yml 中缺少命令 /{}", name);
            return;
        }
        command.setExecutor((sender, cmd, label, args) ->
                {
                    io.github.fulingsnow.youshuiauth.platform.AuthSender authSender =
                            PaperAuthSender.of(sender);

                    if (visible != null && !visible.test(authSender)) {
                        return true;
                    }
                    body.accept(authSender, args);
                    return true;
                });
        command.setTabCompleter((sender, cmd, label, args) -> switch (name) {
            case "ysauth" -> commands.adminSuggestions(args);
            case "2fa" -> commands.twoFactorSuggestions(args);
            default -> java.util.List.of();
        });
    }

    private World resolveQuarantineWorld() {
        String configured = platformConfig.getString("quarantine.world", "");
        World world = configured == null || configured.isBlank()
                ? Bukkit.getWorlds().get(0)
                : Bukkit.getWorld(configured);
        return world != null ? world : Bukkit.getWorlds().get(0);
    }

    private Location resolveQuarantineAnchor(World world) {
        double x = platformConfig.getDouble("quarantine.x", world.getSpawnLocation().getX());
        double z = platformConfig.getDouble("quarantine.z", world.getSpawnLocation().getZ());
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);

        world.getChunkAt(blockX >> 4, blockZ >> 4).load();

        double y;
        if (platformConfig.isSet("quarantine.y")) {
            y = platformConfig.getDouble("quarantine.y");
        } else {
            int surface = world.getHighestBlockYAt(blockX, blockZ);
            if (surface <= world.getMinHeight() + 2) {

                Location spawn = world.getSpawnLocation();
                getSLF4JLogger().warn("隔离点所在列 {} {} 的地表高度异常（{}），"
                                + "改用世界出生点（{} {} {}）",
                        blockX, blockZ, surface,
                        spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
                return new Location(world, spawn.getX(), spawn.getY(), spawn.getZ(),
                        (float) platformConfig.getDouble("quarantine.yaw", 0),
                        (float) platformConfig.getDouble("quarantine.pitch", 0));
            }
            y = surface + 1.0;
        }
        getSLF4JLogger().info("隔离点解析完成：{} {} {}", blockX, (int) y, blockZ);
        return new Location(world, x, y, z,
                (float) platformConfig.getDouble("quarantine.yaw", 0),
                (float) platformConfig.getDouble("quarantine.pitch", 0));
    }

    /**
     * 会话超时由 core 的超时任务回调，这里负责把玩家踢出并清理会话。
     */
    private void onSessionExpired(UUID playerId) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }
            runtime.sessionExpiry().expire(PaperAuthPlayer.of(player),
                    runtime.settings().getAuthServerTimeoutSeconds());
        });
    }

    private void startQqBridge() {
        if (!(runtime.qq() instanceof io.github.fulingsnow.youshuiauth.qq.QqAuthFlow flow)) {
            return;
        }
        io.github.fulingsnow.youshuiauth.qq.QqSettings qqSettings = runtime.settings().getQqSettings();
        try {
            qqBridge = new io.github.fulingsnow.youshuiauth.qq.QqBridgeServer(qqSettings,
                    new io.github.fulingsnow.youshuiauth.paper.qq.PaperQqBridge(
                            runtime, flow, qqSettings, getSLF4JLogger()));
            qqBridge.start();
            getSLF4JLogger().info("QQ 桥接监听于 {}:{}",
                    qqSettings.getBindAddress(), qqSettings.getPort());
        } catch (java.io.IOException e) {
            getSLF4JLogger().warn("QQ 桥接启动失败；/qqbind 将提示该功能不可用", e);
        }
    }

    @Override
    public void onDisable() {
        if (quarantine != null) {
            quarantine.restoreAllPlatforms();
        }
        if (qqBridge != null) {
            qqBridge.close();
        }
        if (commands != null) {
            commands.shutdown();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
        io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider.shutdown();
    }
}
