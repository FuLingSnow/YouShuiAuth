package io.github.fulingsnow.youshuiauth.config;

import io.github.fulingsnow.youshuiauth.i18n.BuiltInLanguages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Copies the canonical bundled configuration for a new YouShuiAuth installation. */
final class DefaultConfigGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DefaultConfigGenerator.class);
    private static final String DEFAULT_CONFIG_RESOURCE = "/default-config.yml";
    private static final String BUILT_IN_LANGUAGE_CODES_PLACEHOLDER =
            "__BUILT_IN_LANGUAGE_CODES__";

    private DefaultConfigGenerator() {}

    /** 内置模板原文（config-version 占位未替换），迁移器以此为准重建配置文件。 */
    static String bundledTemplate() throws IOException {
        try (InputStream resource = DefaultConfigGenerator.class.getResourceAsStream(
                DEFAULT_CONFIG_RESOURCE)) {
            if (resource == null) {
                throw new IOException("Bundled default configuration resource is missing: "
                        + DEFAULT_CONFIG_RESOURCE);
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(BUILT_IN_LANGUAGE_CODES_PLACEHOLDER,
                            BuiltInLanguages.quotedCodeList());
        }
    }

    /**
     * Creates a new config file from the versioned resource. Existing operator files are never
     * overwritten, even if a caller races the normal existence check in {@link Settings#load()}.
     */
    static void createDefaultConfig(Path configFile) throws IOException {
        String defaultConfig;
        try (InputStream resource = DefaultConfigGenerator.class.getResourceAsStream(
                DEFAULT_CONFIG_RESOURCE)) {
            if (resource == null) {
                throw new IOException("Bundled default configuration resource is missing: "
                        + DEFAULT_CONFIG_RESOURCE);
            }
            defaultConfig = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(BUILT_IN_LANGUAGE_CODES_PLACEHOLDER,
                            BuiltInLanguages.quotedCodeList())
                    .replace(Settings.CONFIG_VERSION_PLACEHOLDER, Settings.CONFIG_VERSION);
        }

        Files.writeString(configFile, defaultConfig, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        logger.info("已创建默认配置文件");
    }
}
