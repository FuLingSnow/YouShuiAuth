package net.rafalohaki.veloauth.config;

import net.rafalohaki.veloauth.qq.QqSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqSettingsLoadingTest {

    @TempDir
    Path dataDirectory;

    @Test
    void loadsTheQqSection() throws IOException {
        writeConfig("""
                qq:
                  enabled: true
                  bind-address: "127.0.0.1"
                  port: 8766
                  shared-secret: "0123456789abcdef0123456789abcdef"
                  allowed-groups: ["123456", "654321"]
                  bind-code-ttl-seconds: 300
                  login-code-ttl-seconds: 90
                  max-attempts-per-minute: 7
                """);

        Settings settings = new Settings(dataDirectory);
        assertTrue(settings.load());

        QqSettings qq = settings.getQqSettings();
        assertTrue(qq.isEnabled());
        assertEquals(8766, qq.getPort());
        assertEquals(90, qq.getLoginCodeTtlSeconds());
        assertEquals(7, qq.getMaxAttemptsPerMinute());
        assertEquals(2, qq.getAllowedGroups().size());
        assertTrue(qq.getAllowedGroups().contains("123456"));
    }

    @Test
    void disabledQqSectionKeepsDefaults() throws IOException {
        writeConfig("""
                qq:
                  enabled: false
                """);

        Settings settings = new Settings(dataDirectory);
        assertTrue(settings.load());

        QqSettings qq = settings.getQqSettings();
        assertFalse(qq.isEnabled());
        assertEquals("127.0.0.1", qq.getBindAddress());
        assertEquals(8765, qq.getPort());
        assertTrue(qq.getAllowedGroups().isEmpty());
    }

    @Test
    void loadsDeviceApprovalSection() throws IOException {
        writeConfig("""
                qq:
                  enabled: true
                  shared-secret: "0123456789abcdef0123456789abcdef"
                  device-approval:
                    enabled: true
                    timeout-seconds: 90
                """);

        Settings settings = new Settings(dataDirectory);
        assertTrue(settings.load());

        QqSettings qq = settings.getQqSettings();
        assertTrue(qq.isDeviceApprovalEnabled());
        assertEquals(90, qq.getDeviceApprovalTimeoutSeconds());
    }

    @Test
    void deviceApprovalDefaultsToDisabled() throws IOException {
        writeConfig("""
                qq:
                  enabled: true
                  shared-secret: "0123456789abcdef0123456789abcdef"
                """);

        Settings settings = new Settings(dataDirectory);
        assertTrue(settings.load());

        QqSettings qq = settings.getQqSettings();
        assertFalse(qq.isDeviceApprovalEnabled());
        assertEquals(120, qq.getDeviceApprovalTimeoutSeconds());
    }

    @Test
    void rejectsDeviceApprovalTimeoutOutOfRange() throws IOException {
        writeConfig("""
                qq:
                  enabled: true
                  shared-secret: "0123456789abcdef0123456789abcdef"
                  device-approval:
                    enabled: true
                    timeout-seconds: 10
                """);

        Settings settings = new Settings(dataDirectory);
        assertFalse(settings.load());
    }

    @Test
    void rejectsEnabledBridgeWithoutALongEnoughSecret() throws IOException {
        writeConfig("""
                qq:
                  enabled: true
                  shared-secret: "too-short"
                """);

        Settings settings = new Settings(dataDirectory);
        assertFalse(settings.load());
    }

    private void writeConfig(String yaml) throws IOException {
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("config.yml"), yaml, StandardCharsets.UTF_8);
    }
}
