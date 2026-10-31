package io.github.fulingsnow.youshuiauth.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReportRedactor}.
 * Verifies that secrets in YAML ({@code config.yml}) and TOML ({@code velocity.toml})
 * are redacted before upload, and that non-secret values are preserved.
 */
class ReportRedactorTest {

    @Test
    void redactYaml_quotedPassword_replaced() {
        String input = "database:\n  password: \"mySecret123\"\n  user: youshuiauth\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("password: \"<redacted>\""), () -> "Expected redacted password, got: " + result);
        assertFalse(result.contains("mySecret123"), () -> "Secret leaked in: " + result);
        assertTrue(result.contains("user: youshuiauth"), () -> "Non-secret user was redacted: " + result);
    }

    @Test
    void redactYaml_unquotedPassword_replaced() {
        String input = "  password: mySecret123\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("password: <redacted>"), () -> "Expected redacted password, got: " + result);
        assertFalse(result.contains("mySecret123"), () -> "Secret leaked in: " + result);
    }

    @Test
    void redactYaml_emptyPassword_replaced() {
        String input = "  password: \"\"\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("password: \"<redacted>\""), () -> "Expected redacted empty password, got: " + result);
    }

    @Test
    void redactYaml_singleQuotedPassword_replaced() {
        String input = "  password: 'mySecret'\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("password: '<redacted>'"), () -> "Expected single-quoted redacted, got: " + result);
        assertFalse(result.contains("mySecret"), () -> "Secret leaked in: " + result);
    }

    @Test
    void redactYaml_webhookUrl_replaced() {
        String input = "discord:\n  webhook-url: \"https://discord.com/api/webhooks/123/abc\"\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("webhook-url: \"<redacted>\""), () -> "Expected redacted webhook, got: " + result);
        assertFalse(result.contains("discord.com/api/webhooks"), () -> "Webhook URL leaked in: " + result);
    }

    @Test
    void redactYaml_sslPassword_replaced() {
        String input = "  ssl-password: \"sslSecret\"\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("ssl-password: \"<redacted>\""), () -> "Expected redacted ssl-password, got: " + result);
        assertFalse(result.contains("sslSecret"), () -> "SSL password leaked in: " + result);
    }

    @Test
    void redactToml_forwardingSecret_replaced() {
        String input = "forwarding-secret = \"mySecret\"\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("forwarding-secret = \"<redacted>\""), () -> "Expected redacted forwarding-secret, got: " + result);
        assertFalse(result.contains("mySecret"), () -> "Forwarding secret leaked in: " + result);
    }

    @Test
    void redactToml_secret_replaced() {
        String input = "secret = \"mySecret\"\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("secret = \"<redacted>\""), () -> "Expected redacted secret, got: " + result);
        assertFalse(result.contains("mySecret"), () -> "Secret leaked in: " + result);
    }

    @Test
    void redact_connectionUrlWithCredentials_credentialsReplaced() {
        String input = "  connection-url: \"postgresql://user:pass@host:5432/db\"\n";

        String result = ReportRedactor.redact(input);

        assertFalse(result.contains("user:pass@"), () -> "URL credentials leaked in: " + result);
        assertTrue(result.contains("postgresql://<redacted>@host:5432/db"), () -> "Expected redacted URL, got: " + result);
    }

    @Test
    void redact_connectionUrlWithoutCredentials_preserved() {
        String input = "  connection-url: \"postgresql://host:5432/db\"\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("postgresql://host:5432/db"), () -> "URL without creds was modified: " + result);
    }

    @Test
    void redact_connectionParameters_redactsSecretParametersAndPreservesDiagnostics() {
        String input = "  connection-parameters: \"sslmode=require&sslpassword=p%40ss%3Aword&applicationName=YouShuiAuth\"\n";

        String result = ReportRedactor.redact(input);

        assertFalse(result.contains("p%40ss%3Aword"), () -> "SSL password leaked in: " + result);
        assertTrue(result.contains("sslpassword=<redacted>"), () -> "Expected redacted parameter, got: " + result);
        assertTrue(result.contains("sslmode=require"), () -> "SSL mode was modified: " + result);
        assertTrue(result.contains("applicationName=YouShuiAuth"), () -> "Non-secret parameter was modified: " + result);
    }

    @Test
    void redact_connectionUrlQuery_redactsCaseInsensitiveSecretParameters() {
        String input = "connection-url: \"jdbc:postgresql://db.example/youshuiauth?sslmode=require&SSLPassword=topSecret&client_secret=clientSecretValue\"\n";

        String result = ReportRedactor.redact(input);

        assertFalse(result.contains("topSecret"));
        assertFalse(result.contains("clientSecretValue"));
        assertTrue(result.contains("SSLPassword=<redacted>"));
        assertTrue(result.contains("client_secret=<redacted>"));
        assertTrue(result.contains("sslmode=require"));
    }

    @Test
    void redact_connectionUrlPasswordContainingAtSign_redactsCompleteUserInfo() {
        String input = "connection-url: \"postgresql://user:p@ss@db.example:5432/youshuiauth\"\n";

        String result = ReportRedactor.redact(input);

        assertFalse(result.contains("user:p@ss@"));
        assertFalse(result.contains("ss@db.example"));
        assertTrue(result.contains("postgresql://<redacted>@db.example:5432/youshuiauth"));
    }

    @Test
    void redact_nonSecretValues_preserved() {
        String input = "  server-name: limbo\n  port: 3306\n  debug-enabled: true\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("server-name: limbo"), () -> "Non-secret was modified: " + result);
        assertTrue(result.contains("port: 3306"), () -> "Non-secret was modified: " + result);
        assertTrue(result.contains("debug-enabled: true"), () -> "Non-secret was modified: " + result);
    }

    @Test
    void redact_fullYouShuiAuthConfig_secretsRedactedNonSecretsPreserved() {
        String input = """
                database:
                  storage-type: "postgresql"
                  host: "localhost"
                  port: 5432
                  database: "youshuiauth"
                  user: "youshuiauth_user"
                  password: "superSecretDbPassword"
                  connection-url: "postgresql://youshuiauth_user:superSecretDbPassword@localhost:5432/youshuiauth"
                alerts:
                  discord:
                    enabled: true
                    webhook-url: "https://discord.com/api/webhooks/999/tokenXYZ"
                ping-timeout-ms: 5000
                debug-enabled: false
                """;

        String result = ReportRedactor.redact(input);

        assertFalse(result.contains("superSecretDbPassword"), "DB password leaked");
        assertFalse(result.contains("discord.com/api/webhooks/999/tokenXYZ"), "Webhook URL leaked");

        assertTrue(result.contains("storage-type: \"postgresql\""), "DB type was modified");
        assertTrue(result.contains("host: \"localhost\""), "Host was modified");
        assertTrue(result.contains("port: 5432"), "Port was modified");
        assertTrue(result.contains("user: \"youshuiauth_user\""), "User was modified");
        assertTrue(result.contains("ping-timeout-ms: 5000"), "Ping timeout was modified");
        assertTrue(result.contains("debug-enabled: false"), "Debug flag was modified");
    }

    @Test
    void redact_longUnterminatedQuotedValue_redactsLinearly() {

        String value = "x".repeat(50_000);
        String input = "  password: \"" + value + "\n";

        long start = System.nanoTime();
        String result = ReportRedactor.redact(input);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.contains(value), "Long secret leaked");
        assertTrue(elapsedMs < 1_000, () -> "Redaction took too long (" + elapsedMs + "ms) — possible backtracking");
    }

    @Test
    void redact_nullInput_returnsNull() {
        assertEquals(null, ReportRedactor.redact(null));
    }

    @Test
    void redact_emptyInput_returnsEmpty() {
        assertEquals("", ReportRedactor.redact(""));
    }

    @Test
    void redactLog_commonRuntimeSecrets_replaced() {
        String input = """
                Authorization: Bearer runtime-token-value
                token=plain-token-value
                password: leaked-password
                webhook https://discord.com/api/webhooks/123456/secret-token
                connected to postgresql://user:pass@db.example/youshuiauth
                """;

        String result = ReportRedactor.redactLog(input);

        assertFalse(result.contains("runtime-token-value"));
        assertFalse(result.contains("plain-token-value"));
        assertFalse(result.contains("leaked-password"));
        assertFalse(result.contains("secret-token"));
        assertFalse(result.contains("user:pass@"));
        assertTrue(result.contains("<redacted>"));
    }

    @Test
    void redactLog_jdbcSslPasswordAndAccessToken_replaced() {
        String input = "jdbc:postgresql://db/youshuiauth?sslpassword=jdbcSecret&accessToken=apiSecret sslmode=require";

        String result = ReportRedactor.redactLog(input);

        assertFalse(result.contains("jdbcSecret"));
        assertFalse(result.contains("apiSecret"));
        assertTrue(result.contains("sslpassword=<redacted>"));
        assertTrue(result.contains("accessToken=<redacted>"));
        assertTrue(result.contains("sslmode=require"));
    }

    @Test
    void redactLog_authenticationCommandArguments_replaced() {
        String input = """
                [INFO] Alice issued server command: /login loginSecret
                [INFO] Ally issued server command: /l aliasLoginSecret
                [INFO] Bob issued server command: /register registerSecret registerSecret
                [INFO] Billy issued server command: /reg aliasRegisterSecret aliasRegisterSecret
                [INFO] Carol issued server command: /changepassword oldSecret newSecret
                [INFO] Dana issued server command: /2fa verify 123456
                [INFO] Erin issued server command: /2fa disable 654321
                [INFO] Evie issued server command: /totp verify 456789
                """;

        String result = ReportRedactor.redactLog(input);

        assertFalse(result.contains("loginSecret"));
        assertFalse(result.contains("aliasLoginSecret"));
        assertFalse(result.contains("registerSecret"));
        assertFalse(result.contains("aliasRegisterSecret"));
        assertFalse(result.contains("oldSecret"));
        assertFalse(result.contains("newSecret"));
        assertFalse(result.contains("123456"));
        assertFalse(result.contains("654321"));
        assertFalse(result.contains("456789"));
        assertTrue(result.contains("/login <redacted>"));
        assertTrue(result.contains("/l <redacted>"));
        assertTrue(result.contains("/register <redacted> <redacted>"));
        assertTrue(result.contains("/reg <redacted> <redacted>"));
        assertTrue(result.contains("/changepassword <redacted> <redacted>"));
        assertTrue(result.contains("/2fa verify <redacted>"));
        assertTrue(result.contains("/2fa disable <redacted>"));
        assertTrue(result.contains("/totp verify <redacted>"));
    }

    @Test
    void redactLog_namespacedAndPartialAuthCommands_replaced() {
        String input = """
                Notch -> executed command /youshuiauth:login namespacedSecret
                Notch -> executed command /youshuiauth:register onlyRegisterSecret
                Notch -> executed command /YouShuiAuth:changepassword onlyOldSecret
                Notch -> executed command /youshuiauth:2fa verify 111222
                Notch -> executed command /login "quoted secret"
                Notch -> executed command /logout now
                Notch -> executed command /list players
                Notch -> executed command /2fa status
                Notch -> executed command /vauth reload
                """;

        String result = ReportRedactor.redactLog(input);

        assertFalse(result.contains("namespacedSecret"));
        assertFalse(result.contains("onlyRegisterSecret"));
        assertFalse(result.contains("onlyOldSecret"));
        assertFalse(result.contains("111222"));
        assertFalse(result.contains("quoted secret"));
        assertTrue(result.contains("/youshuiauth:login <redacted>"));
        assertTrue(result.contains("/youshuiauth:register <redacted>"));
        assertTrue(result.contains("/YouShuiAuth:changepassword <redacted>"));
        assertTrue(result.contains("/youshuiauth:2fa verify <redacted>"));
        assertTrue(result.contains("/login <redacted>"));
        assertTrue(result.contains("/logout now"));
        assertTrue(result.contains("/list players"));
        assertTrue(result.contains("/2fa status"));
        assertTrue(result.contains("/vauth reload"));
    }

    @Test
    void redact_passwordInComment_notRedacted() {

        String input = "# password: example-doc-password\n  password: \"realSecret\"\n";

        String result = ReportRedactor.redact(input);

        assertTrue(result.contains("password: \"<redacted>\""), () -> "Real password not redacted: " + result);

        assertTrue(result.contains("# password: example-doc-password"), () -> "Comment was modified: " + result);
    }
}
