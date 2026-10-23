package io.github.fulingsnow.youshuiauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigMigratorTest.class);

    @TempDir
    Path tempDir;

    @Test
    void keepsUserValuesAndCleansObsoleteKeys() throws IOException {
        Path config = writeConfig("""
                # 旧版配置
                config-version: "1.0.0"
                language: zh_hk
                security:
                  auto-login-max-age-days: 14
                  auto-login-fingerprint-required: false
                  deny-qq-password-login: true
                """);
        String template = """
                # 新模板
                config-version: "__CONFIG_VERSION__"
                language: en
                security:
                  auto-login-max-age-days: 7
                  auto-login-require-same-subnet: true
                  deny-qq-password-login: false
                  new-feature: true
                """;

        var result = ConfigMigrator.migrateWithTemplate(config, template, "1.1.2", LOGGER);
        assertEquals(1, result.removedKeys(), "只有废弃键计入移除，config-version 不算");

        String migrated = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("language: zh_hk"), "用户自定义值保留");
        assertTrue(migrated.contains("auto-login-max-age-days: 14"));
        assertTrue(migrated.contains("deny-qq-password-login: true"));
        assertTrue(migrated.contains("auto-login-require-same-subnet: true"), "新键落默认值");
        assertTrue(migrated.contains("new-feature: true"));
        assertFalse(migrated.contains("auto-login-fingerprint-required"), "废弃键清理");
        assertTrue(migrated.contains("config-version: \"1.1.2\""));
        Path backup = tempDir.resolve("config-backup-1.0.0.yml");
        assertTrue(Files.exists(backup), "旧文件备份");
        assertTrue(Files.readString(backup, StandardCharsets.UTF_8)
                .contains("auto-login-fingerprint-required: false"));
    }

    @Test
    void platformConfigMigrationKeepsDeploymentIdAndAddsVersion() throws IOException {
        Path platform = tempDir.resolve("platform.yml");
        Files.writeString(platform, """
                deployment:
                  id: paper-host-4dac
                quarantine:
                  world: ""
                  x: 0.0
                  z: 0.0
                  yaw: 0.0
                  pitch: 0.0
                """, StandardCharsets.UTF_8);
        String template = """
                config-version: "1"
                deployment:
                  id: ""
                quarantine:
                  world: ""
                  yaw: 0.0
                  pitch: 0.0
                """;

        var result = ConfigMigrator.migrateWithTemplate(
                platform, template, "1", "platform-backup-", LOGGER);

        assertTrue(result.migrated());
        String migrated = Files.readString(platform, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("id: paper-host-4dac"), "部署标识必须保留");
        assertTrue(migrated.contains("config-version: \"1\""), "补上版本号");
        assertFalse(migrated.contains("x: 0.0"), "废弃的固定坐标被清理");
        assertTrue(Files.exists(tempDir.resolve("platform-backup-legacy.yml")),
                "平台配置使用独立备份前缀");
    }

    @Test
    void chineseCommentsInExistingFileDoNotWipeUserValues() throws IOException {
        Path platform = tempDir.resolve("platform.yml");
        Files.writeString(platform, """
                # 中文注释：旧版平台配置
                deployment:
                  id: paper-中文主机-4dac
                """, StandardCharsets.UTF_8);
        String template = """
                # 中文注释：新模板
                config-version: "1"
                deployment:
                  id: ""
                """;

        ConfigMigrator.migrateWithTemplate(platform, template, "1", "platform-backup-", LOGGER);

        String migrated = Files.readString(platform, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("id: paper-中文主机-4dac"),
                "含中文的旧文件必须能解析出用户值");
    }

    @Test
    void crlfPlatformFileKeepsUserValues() throws IOException {
        Path platform = tempDir.resolve("platform.yml");

        String content = "# 同一套数据库被多个端同时使用时，必须为每个端配置唯一标识，\r\n"
                + "deployment:\r\n"
                + "  id: paper-DESKTOP-QS7J66C-4dac\r\n"
                + "\r\n"
                + "# 冻结隔离区\r\n"
                + "quarantine:\r\n"
                + "  world: ''\r\n"
                + "  x: 0.0\r\n"
                + "  z: 0.0\r\n"
                + "  yaw: 0.0\r\n"
                + "  pitch: 0.0\r\n";
        Files.writeString(platform, content, StandardCharsets.UTF_8);

        String template = """
                # 本文件由插件维护
                config-version: "1"
                deployment:
                  id: ""
                quarantine:
                  world: ""
                  yaw: 0.0
                  pitch: 0.0
                """;

        var result = ConfigMigrator.migrateWithTemplate(
                platform, template, "1", "platform-backup-", LOGGER);

        assertTrue(result.keptValues() > 0,
                "CRLF 文件必须能匹配出用户值，实际 kept=" + result.keptValues());
        String migrated = Files.readString(platform, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("paper-DESKTOP-QS7J66C-4dac"), "部署标识必须保留");
    }

    @Test
    void crlfTemplateStillMatchesKeys() throws IOException {
        Path platform = tempDir.resolve("platform.yml");
        Files.writeString(platform, "deployment:\n  id: paper-host-4dac\n",
                StandardCharsets.UTF_8);

        String template = "config-version: \"1\"\r\n"
                + "deployment:\r\n"
                + "  id: \"\"\r\n";

        var result = ConfigMigrator.migrateWithTemplate(
                platform, template, "1", "platform-backup-", LOGGER);

        assertEquals(1, result.keptValues(), "CRLF 模板也必须能匹配用户值");
        String migrated = Files.readString(platform, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("id: paper-host-4dac"));
    }

    @Test
    void identicalVersionSkipsMigration() throws IOException {
        Path config = writeConfig("config-version: \"1.1.2\"\nlanguage: en\n");

        var result = ConfigMigrator.migrateIfNeeded(config, "1.1.2", LOGGER);

        assertFalse(result.migrated());
        assertEquals("config-version: \"1.1.2\"\nlanguage: en\n",
                Files.readString(config, StandardCharsets.UTF_8));
        assertTrue(backupCount() == 0, "同版本不产生备份");
    }

    @Test
    void missingVersionIsTreatedAsLegacy() throws IOException {
        Path config = writeConfig("language: en\n");
        String template = "config-version: \"__CONFIG_VERSION__\"\nlanguage: en\n";

        var result = ConfigMigrator.migrateWithTemplate(config, template, "1.1.2", LOGGER);

        assertTrue(result.migrated());
        assertTrue(Files.exists(tempDir.resolve("config-backup-legacy.yml")));
    }

    @Test
    void stringValuesWithSpecialCharactersGetQuoted() throws IOException {
        Path config = writeConfig("""
                config-version: "1.0.0"
                database:
                  name: "my: server"
                """);
        String template = """
                config-version: "__CONFIG_VERSION__"
                database:
                  name: localhost
                """;

        ConfigMigrator.migrateWithTemplate(config, template, "1.1.2", LOGGER);

        assertTrue(Files.readString(config, StandardCharsets.UTF_8)
                .contains("name: \"my: server\""), "含特殊字符的字符串值加引号");
    }

    @Test
    void backupsAreRollingCapped() throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(tempDir.resolve("config-backup-a" + i + ".yml"), "x",
                    StandardCharsets.UTF_8);
        }
        Path config = writeConfig("config-version: \"0.9.0\"\nlanguage: en\n");

        ConfigMigrator.migrateWithTemplate(config,
                "config-version: \"__CONFIG_VERSION__\"\n", "1.1.2", LOGGER);

        assertTrue(backupCount() <= 3, "备份滚动清理到上限");
    }

    @Test
    void unknownTopLevelSectionsArePreserved() throws IOException {
        Path config = writeConfig("""
                config-version: "1.0.0"
                language: en
                picolimbo:
                  server-name: historical-limbo
                  timeout-seconds: 210
                """);
        String template = """
                config-version: "__CONFIG_VERSION__"
                language: en
                auth-server:
                  server-name: limbo
                  timeout-seconds: 300
                """;

        ConfigMigrator.migrateWithTemplate(config, template, "1.1.2", LOGGER);

        String migrated = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("picolimbo:"), "未知顶层段应保留");
        assertTrue(migrated.contains("historical-limbo"));
        assertTrue(migrated.contains("server-name: limbo"), "模板段正常生成");
    }

    @Test
    void fullSettingsLoadMigratesLegacyPicolimboOnlyConfig() throws IOException {
        writeConfig("""
                picolimbo:
                  server-name: historical-limbo
                  timeout-seconds: 210
                """);

        Settings settings = new Settings(tempDir);
        assertTrue(settings.load());

        String migrated = Files.readString(tempDir.resolve("config.yml"), StandardCharsets.UTF_8);
        assertTrue(migrated.contains("auth-server:"));
        assertTrue(migrated.contains("picolimbo:"), "原段保留在文件尾部备查");
        assertTrue(Files.exists(tempDir.resolve("config-backup-legacy.yml")));
    }

    private Path writeConfig(String content) throws IOException {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, content, StandardCharsets.UTF_8);
        return config;
    }

    private long backupCount() throws IOException {
        try (var files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("config-backup"))
                    .count();
        }
    }
}
