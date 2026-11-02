package io.github.fulingsnow.youshuiauth.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionGeneratorTest {

    @TempDir
    Path root;

    @Test
    void generatesVersionProjectFromOrigin() throws IOException {
        writePlatformsYml("""
                platforms:
                  paper:
                    versions: [1.21.4, 1.21.5]
                    java_version: 21
                """);
        Path origin = root.resolve("platforms/paper/origin");
        Files.createDirectories(origin);
        Files.writeString(origin.resolve("gradle.properties"),
                "api_version=${mc_version}\njava_version=${java_version}\nmod_version=${mod_version}\n");
        Files.writeString(origin.resolve("build.gradle"),
                "def v = '${mc_version}'\r\ndef j = '${java_version}'\r\n");
        Files.createDirectories(origin.resolve("src/main/java"));
        Files.writeString(origin.resolve("src/main/java/Main.java"), "class Main {}\n");
        Files.writeString(root.resolve("version.txt"), "1.1.2\n");
        Files.writeString(root.resolve("tool_version.txt"), "0.1.0\n");

        int count = new VersionGenerator().generate(root);

        assertEquals(2, count);
        Path project = root.resolve("platforms/paper/paper-1.21.4");
        String properties = Files.readString(project.resolve("gradle.properties"));
        assertTrue(properties.contains("api_version=1.21.4"));
        assertTrue(properties.contains("java_version=21"));
        assertTrue(properties.contains("mod_version=1.1.2"));
        String buildScript = Files.readString(project.resolve("build.gradle"));
        assertTrue(buildScript.contains("def v = '1.21.4'"));
        assertTrue(buildScript.contains("def j = '21'"));
        assertTrue(Files.exists(project.resolve("src/main/java/Main.java")));
        assertEquals("1.21.4", Files.readString(project.resolve("support_version.txt")).strip());
    }

    @Test
    void skipsPlatformWithoutOrigin() throws IOException {
        writePlatformsYml("""
                platforms:
                  fabric:
                    versions: [1.21.4]
                """);
        assertEquals(0, new VersionGenerator().generate(root));
    }

    @Test
    void refreshesExistingTargetSoTemplateFixesPropagate() throws IOException {
        writePlatformsYml("""
                platforms:
                  paper:
                    versions: [1.21.4]
                """);
        Path origin = root.resolve("platforms/paper/origin");
        Files.createDirectories(origin);
        Files.writeString(origin.resolve("template.txt"), "v=${mc_version}");
        Path existing = root.resolve("platforms/paper/paper-1.21.4");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("marker.txt"), "stale");

        // 生成目录是派生物：存在时整目录重建，origin 的修复才会生效。
        assertEquals(1, new VersionGenerator().generate(root));
        assertEquals("v=1.21.4",
                Files.readString(existing.resolve("template.txt"), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(Files.exists(existing.resolve("marker.txt")), "旧的派生文件应被清理");
    }

    @Test
    void rejectsSpecWithoutPlatforms() throws IOException {
        writePlatformsYml("# nothing here\n");
        assertThrows(IOException.class, () -> new VersionGenerator().generate(root));
    }

    private void writePlatformsYml(String content) throws IOException {
        Files.writeString(root.resolve("platforms.yml"), content);
    }
}
