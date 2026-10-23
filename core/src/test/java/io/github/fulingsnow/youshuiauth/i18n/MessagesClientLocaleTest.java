package io.github.fulingsnow.youshuiauth.i18n;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Client-language detection (issues #50/#52): the player's Minecraft locale picks the
 * message language when a translation exists, with fallback to the configured default
 * language and then to English per missing key. Runs without Mockito (legacy JAR mode).
 */
class MessagesClientLocaleTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void resolvePlayerLanguage_mapsExactVariantsFirst() {
        Messages messages = new Messages();
        messages.setLanguage("en");

        assertEquals("zh_cn", messages.resolvePlayerLanguage(Locale.forLanguageTag("zh-CN")));
        assertEquals("zh_hk", messages.resolvePlayerLanguage(Locale.forLanguageTag("zh-HK")));
        assertEquals("en", messages.resolvePlayerLanguage(Locale.forLanguageTag("en-GB")));
    }

    @Test
    void resolvePlayerLanguage_unsupportedVariantsFallBackToConfiguredDefault() {
        Messages messages = new Messages();
        messages.setLanguage("en");

        assertEquals("en", messages.resolvePlayerLanguage(Locale.forLanguageTag("pl-PL")),
                "Polish is no longer bundled — keep the configured default");
        assertEquals("en", messages.resolvePlayerLanguage(Locale.forLanguageTag("de-AT")),
                "German is no longer bundled");
        assertEquals("en", messages.resolvePlayerLanguage(Locale.forLanguageTag("pt-PT")),
                "Portuguese is no longer bundled");
        assertEquals("en", messages.resolvePlayerLanguage(Locale.forLanguageTag("uk-UA")),
                "No bundled Ukrainian at all — keep the configured default");
    }

    @Test
    void resolvePlayerLanguage_unsupportedOrNullFallsBackToConfiguredDefault() {
        Messages messages = new Messages();
        messages.setLanguage("zh_cn");

        assertEquals("zh_cn", messages.resolvePlayerLanguage(Locale.forLanguageTag("sw-KE")),
                "Unsupported client locale must use the configured default, not hardcoded English");
        assertEquals("zh_cn", messages.resolvePlayerLanguage(null));
    }

    @Test
    void resolvePlayerLanguage_ambiguousFamilyKeepsConfiguredDefault() {
        Messages messages = new Messages();
        messages.setLanguage("en");

        assertEquals("en", messages.resolvePlayerLanguage(Locale.forLanguageTag("zh-TW")),
                "zh has two bundled variants (zh_cn/zh_hk) — ambiguous without a country, keep the default");
    }

    @Test
    void componentForLocale_simplifiedChineseClientGetsChineseMessage() {
        Messages messages = new Messages();
        messages.setLanguage("en");

        String rendered = PLAIN.serialize(messages.componentForLocale(
                Locale.forLanguageTag("zh-CN"), "auth.account_exists", NamedTextColor.GREEN));

        assertEquals("你的账户已存在！请使用 /login <密码> 登录，登录后可执行 /qqbind 绑定 QQ；后续密码登录可能废弃。",
                rendered);
    }

    @Test
    void componentForLocale_traditionalChineseLocaleGetsHongKongFile() {
        Messages messages = new Messages();
        messages.setLanguage("en");

        String rendered = PLAIN.serialize(messages.componentForLocale(
                Locale.forLanguageTag("zh-HK"), "auth.account_exists", NamedTextColor.GREEN));

        assertEquals("你的帳號已存在！請使用 /login <密碼> 登入，登入後可執行 /qqbind 綁定 QQ；後續密碼登入可能廢棄。",
                rendered);
    }

    @Test
    void componentForLocale_unsupportedLocaleGetsConfiguredDefault() {
        Messages messages = new Messages();
        messages.setLanguage("en");

        String rendered = PLAIN.serialize(messages.componentForLocale(
                Locale.forLanguageTag("sw-KE"), "auth.account_exists", NamedTextColor.GREEN));

        assertEquals("Your account already exists. Use /login <password>, then /qqbind to bind QQ. "
                + "Password login may be removed later.", rendered);
    }

    @Test
    void componentForLocale_nullLocaleMatchesGlobalComponent() {
        Messages messages = new Messages();
        messages.setLanguage("en");

        String global = PLAIN.serialize(messages.component("auth.first_time", NamedTextColor.AQUA));
        String perPlayer = PLAIN.serialize(messages.componentForLocale(
                null, "auth.first_time", NamedTextColor.AQUA));

        assertEquals(global, perPlayer);
    }
}
