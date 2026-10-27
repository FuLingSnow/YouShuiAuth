package io.github.fulingsnow.youshuiauth.alert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for DiscordWebhookClient security features.
 */
class DiscordWebhookClientTest {

    private static final String VALID_WEBHOOK_URL_1 = "https://discord.com/api/webhooks/123456789/abcdefghijklmnopqrstuvwxyz";

    @Test
    void shouldAcceptValidDiscordWebhookUrl() {

        String validUrl2 = "https://discordapp.com/api/webhooks/987654321/zyxwvutsrqponmlkjihgfedcba";

        assertDoesNotThrow(() -> new DiscordWebhookClient(VALID_WEBHOOK_URL_1));
        assertDoesNotThrow(() -> new DiscordWebhookClient(validUrl2));
    }

    @Test
    void shouldRejectInvalidWebhookUrl() {

        String invalidUrl1 = "http://discord.com/api/webhooks/123/abc";
        String invalidUrl2 = "https://example.com/webhooks/123/abc";
        String invalidUrl3 = "https://discord.com/wrong/path/123/abc";
        String invalidUrl4 = "not-a-url";

        assertThrows(IllegalArgumentException.class,
                () -> new DiscordWebhookClient(invalidUrl1),
                "Should reject HTTP URLs");

        assertThrows(IllegalArgumentException.class,
                () -> new DiscordWebhookClient(invalidUrl2),
                "Should reject non-Discord domains");

        assertThrows(IllegalArgumentException.class,
                () -> new DiscordWebhookClient(invalidUrl3),
                "Should reject invalid webhook paths");

        assertThrows(IllegalArgumentException.class,
                () -> new DiscordWebhookClient(invalidUrl4),
                "Should reject malformed URLs");
    }

    @Test
    void shouldRejectNullWebhookUrl() {

        assertThrows(NullPointerException.class,
                () -> new DiscordWebhookClient(null),
                "Should reject null webhook URL");
    }

    @Test
    void shouldRejectEmptyWebhookUrl() {

        String emptyUrl = "";

        assertThrows(IllegalArgumentException.class,
                () -> new DiscordWebhookClient(emptyUrl),
                "Should reject empty webhook URL");
    }

    @Test
    void shouldHandleBlankContent() {

        DiscordWebhookClient client = new DiscordWebhookClient(VALID_WEBHOOK_URL_1);

        boolean result1 = client.sendMessage(null);
        boolean result2 = client.sendMessage("");
        boolean result3 = client.sendMessage("   ");

        assertFalse(result1, "Should reject null content");
        assertFalse(result2, "Should reject empty content");
        assertFalse(result3, "Should reject blank content");
    }

    @Test
    void shouldTruncateLongContent() {

        DiscordWebhookClient client = new DiscordWebhookClient(VALID_WEBHOOK_URL_1);

        String longContent = "A".repeat(3000);

        assertDoesNotThrow(() -> client.sendMessage(longContent));
    }

    @Test
    void shouldRejectNullEmbed() {

        DiscordWebhookClient client = new DiscordWebhookClient(VALID_WEBHOOK_URL_1);

        assertThrows(NullPointerException.class,
                () -> client.sendEmbed(null),
                "Should reject null embed");
    }

    @Test
    void shouldCreateValidEmbed() {

        DiscordWebhookClient.DiscordEmbed embed = new DiscordWebhookClient.DiscordEmbed()
                .title("Test Alert")
                .description("Test description")
                .color(0xFF0000);

        var map = embed.toMap();

        assertEquals("Test Alert", map.get("title"));
        assertEquals("Test description", map.get("description"));
        assertEquals(0xFF0000, map.get("color"));
    }

    @Test
    void shouldCreateEmbedField() {

        DiscordWebhookClient.EmbedField field1 = new DiscordWebhookClient.EmbedField("Name", "Value");
        DiscordWebhookClient.EmbedField field2 = new DiscordWebhookClient.EmbedField("Inline Name", "Inline Value", true);

        var map1 = field1.toMap();
        var map2 = field2.toMap();

        assertEquals("Name", map1.get("name"));
        assertEquals("Value", map1.get("value"));
        assertFalse((Boolean) map1.get("inline"));

        assertEquals("Inline Name", map2.get("name"));
        assertEquals("Inline Value", map2.get("value"));
        assertTrue((Boolean) map2.get("inline"));
    }
}
