package net.rafalohaki.veloauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuildMetadataTest {

    @Test
    void pomVersionMatchesBuildConstants() throws Exception {
        assertEquals(readPomVersion(), BuildConstants.VERSION,
                "BuildConstants.VERSION must track the Maven project version");
    }

    @Test
    void pluginMetadataMatchesProjectIdentity() throws Exception {
        String projectVersion = readPomVersion();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("velocity-plugin.json")) {
            assertNotNull(input, "Filtered velocity-plugin.json must be on the test classpath");
            JsonObject metadata = JsonParser.parseReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals(projectVersion, metadata.get("version").getAsString(),
                    "Filtered plugin metadata must use the Maven project version");
            assertEquals("youshuiauth", metadata.get("id").getAsString());
            assertEquals("YouShuiAuth", metadata.get("name").getAsString());
        }
    }

    private static String readPomVersion() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element root = factory.newDocumentBuilder()
                .parse(Files.newInputStream(Path.of("pom.xml")))
                .getDocumentElement();
        Element version = null;
        for (var node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && "version".equals(element.getTagName())) {
                if (version != null) {
                    throw new IllegalStateException("pom.xml must contain exactly one direct version");
                }
                version = element;
            }
        }
        if (version == null) {
            throw new IllegalStateException("pom.xml is missing a direct version element");
        }
        return version.getTextContent().trim();
    }
}
