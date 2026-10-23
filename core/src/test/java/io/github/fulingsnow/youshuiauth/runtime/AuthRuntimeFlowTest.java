package io.github.fulingsnow.youshuiauth.runtime;

import net.kyori.adventure.text.Component;
import at.favre.lib.crypto.bcrypt.BCrypt;
import io.github.fulingsnow.youshuiauth.command.AuthCommands;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.AuthSender;
import io.github.fulingsnow.youshuiauth.platform.PlatformAccess;
import io.github.fulingsnow.youshuiauth.platform.PlatformScheduler;
import io.github.fulingsnow.youshuiauth.platform.TransferOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端验证平台无关链路：真实 H2 数据库 + 真实命令实现，覆盖注册/登录/改密/登出。
 * 这是 Paper 命令路径的同构验证（Paper 只是换 AuthSender/AuthPlayer 的实现）。
 */
class AuthRuntimeFlowTest {

    private static final String IP = "203.0.113.42";
    private static final UUID UUID_VALUE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String USERNAME = "FlowTester";

    @TempDir
    Path dataDirectory;

    private AuthRuntime runtime;
    private AuthCommands commands;
    private CapturingSender sender;

    @BeforeEach
    void setUp() throws Exception {
        AuthPlayer player = mockPlayer();
        sender = new CapturingSender(player);
        PlatformScheduler.TaskHandle noopHandle = new PlatformScheduler.TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        PlatformScheduler scheduler = mock(PlatformScheduler.class);
        when(scheduler.scheduleOnce(org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(noopHandle);
        when(scheduler.scheduleRepeating(org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.any(Duration.class),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(noopHandle);
        PlatformAccess access = mock(PlatformAccess.class);
        when(access.scheduler()).thenReturn(scheduler);
        when(access.player(UUID_VALUE)).thenReturn(Optional.of(player));
        when(access.platformId()).thenReturn("test");
        when(access.dataDirectory()).thenReturn(dataDirectory);

        TransferOps transfers = new TransferOps() {
            @Override
            public boolean isPlayerOnAuthServer(UUID playerId) {
                return true;
            }

            @Override
            public boolean transferToBackend(AuthPlayer authPlayer) {
                return true;
            }
        };

        runtime = new AuthRuntime(dataDirectory, LoggerFactory.getLogger(AuthRuntimeFlowTest.class),
                access, transfers, null, null, ignored -> {
        });
        runtime.startInstanceHeartbeat("test-deployment", "1.1.2");
        commands = new AuthCommands(runtime, runtime.databaseManager(), runtime.authCache(),
                runtime.settings(), runtime.messages());
    }

    @AfterEach
    void tearDown() {
        if (commands != null) {
            commands.shutdown();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @Test
    void registerLoginChangePasswordLogout() {
        commands.register(sender, new String[]{"Passw0rd!", "Passw0rd!"});
        assertTrue(awaitAuthorized(true), "注册后应完成认证，实际消息: " + sender.messages);
        settle();

        commands.logout(sender, new String[0]);
        assertTrue(awaitAuthorized(false), "登出后应撤销认证，实际消息: " + sender.messages);

        commands.login(sender, new String[]{"Passw0rd!"});
        assertTrue(awaitAuthorized(true), "登入后应重新认证，实际消息: " + sender.messages);
        settle();

        assertTrue(awaitPasswordRotated(), "改密后数据库中的密码应为新密码，实际消息: " + sender.messages);
        RegisteredPlayer stored = awaitStoredPlayer();
        assertNotNull(stored, "改密后账号应仍存在，实际消息: " + sender.messages);
        assertFalse(BCrypt.verifyer().verify("Passw0rd!".toCharArray(), stored.getHash()).verified,
                "旧密码不应再匹配");
    }

    @Test
    void instanceHeartbeatIsRegisteredAndListed() {
        var rows = runtime.databaseManager().listInstances();
        assertTrue(rows.stream().anyMatch(row -> "test-deployment".equals(row.getDeploymentId())
                        && "test".equals(row.getPlatform()) && "1.1.2".equals(row.getVersion())),
                "心跳行应在启动时写入共享注册表");
    }

    @Test
    void wrongPasswordIsRejected() {
        commands.register(sender, new String[]{"Passw0rd!", "Passw0rd!"});
        assertTrue(awaitAuthorized(true), "注册后应完成认证，实际消息: " + sender.messages);
        commands.logout(sender, new String[0]);
        assertTrue(awaitAuthorized(false), "登出后应撤销认证，实际消息: " + sender.messages);

        commands.login(sender, new String[]{"WrongPassw0rd!"});
        assertFalse(awaitAuthorized(true), "错误密码不应通过认证");
    }

    /**
     * 注册/登录在异步收尾阶段才释放命令锁；串行模拟玩家连续操作需要让出时间片。
     */
    private static void settle() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 改密被命令锁拒绝时不会产生 sender 级消息（提示发往玩家），因此以「库内密码是否已变」轮询重试。
     */
    private boolean awaitPasswordRotated() {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            commands.changePassword(sender, new String[]{"Passw0rd!", "NewPassw0rd!"});
            RegisteredPlayer stored = awaitStoredPlayer();
            if (stored != null && BCrypt.verifyer()
                    .verify("NewPassw0rd!".toCharArray(), stored.getHash()).verified) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private RegisteredPlayer awaitStoredPlayer() {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var result = runtime.databaseManager().findPlayerByNickname(USERNAME).join();
            if (!result.isDatabaseError() && result.getValue() != null) {
                return result.getValue();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private boolean awaitAuthorized(boolean expected) {
        return awaitTrue(() -> runtime.authCache().isPlayerAuthorized(UUID_VALUE, IP) == expected,
                "authorized != " + expected);
    }

    private static boolean awaitTrue(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static AuthPlayer mockPlayer() throws Exception {
        AuthPlayer player = mock(AuthPlayer.class);
        InetAddress address = InetAddress.getByName(IP);
        when(player.uniqueId()).thenReturn(UUID_VALUE);
        when(player.username()).thenReturn(USERNAME);
        when(player.address()).thenReturn(address);
        when(player.remoteAddress()).thenReturn(new InetSocketAddress(address, 25565));
        when(player.virtualHost()).thenReturn(Optional.empty());
        when(player.clientBrand()).thenReturn("vanilla");
        when(player.protocolVersion()).thenReturn(776);
        when(player.settings()).thenReturn(Optional.empty());
        when(player.isActive()).thenReturn(true);
        when(player.onlineMode()).thenReturn(false);
        when(player.sendMessage(org.mockito.ArgumentMatchers.any(Component.class))).thenReturn(true);
        return player;
    }

    private static final class CapturingSender implements AuthSender {

        private final AuthPlayer player;
        private final List<String> messages = new ArrayList<>();

        private CapturingSender(AuthPlayer player) {
            this.player = player;
        }

        @Override
        public String name() {
            return USERNAME;
        }

        @Override
        public boolean hasPermission(String node) {
            return false;
        }

        @Override
        public boolean sendMessage(Component message) {
            messages.add(message.toString());
            return true;
        }

        @Override
        public Optional<AuthPlayer> asPlayer() {
            return Optional.of(player);
        }

        @Override
        public Locale effectiveLocale() {
            return Locale.ENGLISH;
        }
    }
}
