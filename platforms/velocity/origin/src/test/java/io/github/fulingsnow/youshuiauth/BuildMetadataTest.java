package io.github.fulingsnow.youshuiauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建元数据一致性：BuildConstants 与过滤后的 velocity-plugin.json 都由同一组令牌生成，
 * 版本漂移说明令牌替换链路被破坏。
 */
class BuildMetadataTest {

    @Test
    void buildConstantsVersionMatchesPluginMetadata() throws Exception {
        JsonObject metadata = readPluginMetadata();
        assertEquals(BuildConstants.VERSION, metadata.get("version").getAsString(),
                "BuildConstants.VERSION must match the filtered plugin metadata");
        assertTrue(BuildConstants.VERSION.matches("\\d+\\.\\d+\\.\\d+.*"),
                "Version must look semver-like, was " + BuildConstants.VERSION);
    }

    @Test
    void pluginMetadataMatchesProjectIdentity() throws Exception {
        JsonObject metadata = readPluginMetadata();
        assertEquals("youshuiauth", metadata.get("id").getAsString());
        assertEquals("YouShuiAuth", metadata.get("name").getAsString());
        assertNotNull(metadata.get("main"), "Plugin main class must be declared");
    }

    private static JsonObject readPluginMetadata() throws Exception {
        try (InputStream input = BuildMetadataTest.class.getClassLoader()
                .getResourceAsStream("velocity-plugin.json")) {
            assertNotNull(input, "Filtered velocity-plugin.json must be on the test classpath");
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
