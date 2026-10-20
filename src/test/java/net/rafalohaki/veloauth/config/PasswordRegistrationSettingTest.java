package net.rafalohaki.veloauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordRegistrationSettingTest {

    @TempDir
    Path dataDirectory;

    @Test
    void passwordRegistrationIsEnabledByDefault() throws IOException {
        writeConfig("language: en\n");

        Settings settings = new Settings(dataDirectory);

        assertTrue(settings.load());
        assertTrue(settings.isPasswordRegistrationEnabled());
    }

    @Test
    void passwordRegistrationCanBeDisabled() throws IOException {
        writeConfig("""
                language: en
                security:
                  allow-password-registration: false
                """);

        Settings settings = new Settings(dataDirectory);

        assertTrue(settings.load());
        assertFalse(settings.isPasswordRegistrationEnabled());
    }

    private void writeConfig(String yaml) throws IOException {
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("config.yml"), yaml, StandardCharsets.UTF_8);
    }
}
