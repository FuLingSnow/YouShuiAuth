package io.github.fulingsnow.youshuiauth.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Messages} covering language selection, fallbacks and formatting.
 */
class MessagesTest {

    private Messages messages;

    @BeforeEach
    void setUp() {
        messages = new Messages();
    }

    @Test
    void setLanguage_nullValue_fallsBackToDefaultLanguage() {
        messages.setLanguage(null);

        assertEquals("zh_cn", messages.getCurrentLanguage());
    }

    @Test
    void setLanguage_unsupportedLanguage_fallsBackToDefaultLanguage() {
        messages.setLanguage("xx");

        assertEquals("zh_cn", messages.getCurrentLanguage());
    }

    @Test
    void setLanguage_supportedLanguage_loadsMessages() {
        messages.setLanguage("zh_cn");

        assertEquals("zh_cn", messages.getCurrentLanguage());
        assertEquals("数据库中不存在玩家 {}", messages.get("player.not_found"));
    }

    @Test
    void get_missingKey_returnsKeyItself() {
        String key = "non.existing.key";

        String result = messages.get(key);

        assertEquals(key, result);
    }

    @Test
    void get_withArguments_formatsMessage() {
        messages.setLanguage("en");

        String message = messages.get("admin.stats.premium_accounts", 5);

        assertEquals("Premium accounts: 5", message);
    }

    @Test
    void get_deprecatedKeys_resolveToCanonicalMessages() {
        messages.setLanguage("en");

        assertEquals(messages.get("validation.password.too_short", 8),
                messages.get("auth.register.password_too_short", 8));
        assertEquals(messages.get("connection.error.generic"),
                messages.get("error.connection.generic"));
    }

    @Test
    void get_messageFormatEscapesQuotedPlaceholders() {
        messages.setLanguage("en");

        String message = messages.get("connection.picolimbo.found", "auth", "127.0.0.1:25565");

        assertEquals("✅ auth server server 'auth' found: 127.0.0.1:25565", message);
    }

    @Test
    void get_messageFormatPreservesApostrophesForChineseAndEnglish() {
        assertEquals("数据库中不存在玩家 Alex",
                messages.getForLanguage("zh_cn", "player.not_found", "Alex"));
        assertEquals("✅ Player Alex successfully transferred to auth server",
                messages.getForLanguage("en", "player.transfer.success", "Alex"));
    }

    @Test
    void get_chineseBruteForceMessage_hasNoDanglingPlaceholder() {
        assertEquals("失败次数过多，已被临时封禁",
                messages.getForLanguage("zh_cn", "security.brute_force.blocked"));
    }

    @Test
    void isLanguageSupported_recognizesSupportedLanguages() {

        assertTrue(messages.isLanguageSupported("en"));
        assertTrue(messages.isLanguageSupported("zh_cn"));
        assertTrue(messages.isLanguageSupported("zh_hk"));
        assertTrue(messages.isLanguageSupported("EN"));
        assertFalse(messages.isLanguageSupported("pl"));
        assertFalse(messages.isLanguageSupported("fr"));

        assertFalse(messages.isLanguageSupported("xx"));
    }

    @Test
    void getSupportedLanguages_returnsConsistentList() {

        assertArrayEquals(BuiltInLanguages.codes(), messages.getSupportedLanguages());
    }
}
