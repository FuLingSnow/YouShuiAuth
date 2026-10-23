package io.github.fulingsnow.youshuiauth.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Internationalization manager for YouShuiAuth messages.
 * Thread-safe message loading and formatting with support for external language files.
 */
public class Messages {

    private static final Logger logger = LoggerFactory.getLogger(Messages.class);
    private static final Map<String, String> DEPRECATED_KEY_ALIASES = Map.of(
            "auth.register.password_too_short", "validation.password.too_short",
            "error.connection.generic", "connection.error.generic"
    );
    private static final Pattern MINIMESSAGE_HEX_COLOR = Pattern.compile("(?i)<#([0-9a-f]{6})>");
    private static final Pattern SECTION_LEGACY_CODE = Pattern.compile("(?i)§([0-9a-fk-orx#])");
    private static final Pattern AMPERSAND_LEGACY_CODE = Pattern.compile(
            "(?i)&(?:#[0-9a-f]{6}|x(?:&[0-9a-f]){6}|[0-9a-fk-or])");
    private static final String LITERAL_AMPERSAND_MARKER = "\uFDD0";
    private static final String LITERAL_SECTION_MARKER = "\uFDD1";
    private static final String LITERAL_MINIMESSAGE_HEX_MARKER = "\uFDD2";
    private static final LegacyComponentSerializer LEGACY_COMPONENT_SERIALIZER =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private static final Map<String, Properties> messageCache = new ConcurrentHashMap<>();
    private static final Map<String, String> normalizedPatternCache = new ConcurrentHashMap<>();

    private String currentLanguage;

    private final LanguageFileManager languageFileManager;

    private ResourceBundle bundle;

    private ResourceBundle englishBundle;

    private final Map<String, ResourceBundle> playerBundles = new ConcurrentHashMap<>();

    private final boolean useExternalFiles;

    /**
     * Creates a Messages instance with external language file support.
     *
     * @param dataDirectory The plugin's data directory
     * @param language The language code (e.g., "en", "pl")
     */
    public Messages(Path dataDirectory, String language) throws IOException {
        this.languageFileManager = new LanguageFileManager(dataDirectory);
        this.currentLanguage = language != null ? language.toLowerCase(Locale.ROOT) : "zh_cn";
        this.useExternalFiles = true;

        try {
            languageFileManager.initializeLanguageFiles();
            reload();
        } catch (IOException e) {
            throw new IOException("Language initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a Messages instance using JAR-embedded language files (legacy mode).
     * Used for testing and backward compatibility.
     */
    public Messages() {
        this.languageFileManager = null;
        this.useExternalFiles = false;
        this.currentLanguage = "en";
    }

    /**
     * Sets the current language for messages.
     *
     * @param language Language code (e.g., "en", "pl")
     */
    public void setLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            logger.warn("语言参数无效，使用默认值 'zh_cn'");
            this.currentLanguage = "zh_cn";
            return;
        }

        if (!isLanguageSupported(language)) {
            logger.warn("不支持语言 '{}'，回退到 'zh_cn'", language);
            this.currentLanguage = "zh_cn";
            return;
        }

        this.currentLanguage = language.toLowerCase(Locale.ROOT);
        logger.info("语言已设为：{}", this.currentLanguage);

        if (useExternalFiles) {
            try {
                reload();
            } catch (IOException e) {
                logger.error("重新加载语言文件失败", e);
            }
        } else {

            loadMessages(this.currentLanguage);
        }
    }

    /**
     * Reloads language files from disk.
     * Only works when using external files.
     *
     * @throws IOException if language file loading fails
     */
    public void reload() throws IOException {
        if (!useExternalFiles) {
            logger.warn("无法重载：当前未使用外部语言文件");

            clearCache();
            return;
        }

        clearCache();

        languageFileManager.initializeLanguageFiles();

        playerBundles.clear();
        this.bundle = languageFileManager.loadLanguageBundle(currentLanguage);

        this.englishBundle = "en".equalsIgnoreCase(currentLanguage)
                ? this.bundle
                : languageFileManager.loadLanguageBundle("en");
        logger.info("已重载语言文件：{}（外部文件模式）", currentLanguage);
    }

    /**
     * Reloads language files with a new language setting.
     * This should be called after config reload to pick up language changes.
     *
     * @param newLanguage The new language code from config
     * @throws IOException if language file loading fails
     */
    public void reloadWithLanguage(String newLanguage) throws IOException {
        String oldLanguage = this.currentLanguage;

        if (newLanguage != null && !newLanguage.trim().isEmpty()) {
            this.currentLanguage = newLanguage.toLowerCase(java.util.Locale.ROOT);
        }

        if (!oldLanguage.equals(this.currentLanguage)) {
            logger.info("语言已从 '{}' 切换为 '{}'", oldLanguage, currentLanguage);
        }

        reload();
    }

    /**
     * Gets the current language.
     *
     * @return Current language code
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * Gets a formatted message for the current language.
     *
     * @param key  Message key
     * @param args Arguments for message formatting
     * @return Formatted message or the key if not found
     */
    public String get(String key, Object... args) {
        String resolvedKey = resolveMessageKey(key);
        if (useExternalFiles) {
            return getFromBundle(resolvedKey, args);
        } else {
            return getForLanguage(currentLanguage, resolvedKey, args);
        }
    }

    /**
     * Resolves a localized player-facing message and parses its supported color codes.
     * Placeholder values are protected from accidental color parsing, so color syntax belongs
     * exclusively to the trusted translation template.
     *
     * @param key message key
     * @param fallbackColor color used when the template contains no formatting codes
     * @param args message format arguments
     * @return rendered Adventure component
     */
    public Component component(String key, NamedTextColor fallbackColor, Object... args) {
        String text = get(key, protectFormattingArguments(args));
        return restoreFormattingArguments(parseWithColors(text, fallbackColor));
    }

    /**
     * Maps a Minecraft client locale to a supported language code (issues #50/#52).
     * Tries the full {@code lang_country} form first (e.g. {@code pt_br}, {@code zh_hk}),
     * then the bare language, then falls back to the configured default language.
     * Missing keys inside a resolved language still fall back to English per key.
     *
     * @param clientLocale locale reported by the client, may be {@code null}
     * @return supported language code, never {@code null}
     */
    public String resolvePlayerLanguage(@javax.annotation.Nullable Locale clientLocale) {
        if (clientLocale == null) {
            return currentLanguage;
        }
        String lang = clientLocale.getLanguage().toLowerCase(Locale.ROOT);
        String country = clientLocale.getCountry().toLowerCase(Locale.ROOT);
        String exactVariant = exactSupportedVariant(lang, country);
        if (exactVariant != null) {
            return exactVariant;
        }
        String baseLanguage = supportedBaseLanguage(lang);
        if (baseLanguage != null) {
            return baseLanguage;
        }

        String familyVariant = singleBuiltInVariantOf(lang);
        return familyVariant == null ? currentLanguage : familyVariant;
    }

    @javax.annotation.Nullable
    private String exactSupportedVariant(String lang, String country) {
        if (lang.isEmpty() || country.isEmpty()) {
            return null;
        }
        String full = lang + "_" + country;
        return isLanguageSupported(full) ? full : null;
    }

    @javax.annotation.Nullable
    private String supportedBaseLanguage(String lang) {
        return !lang.isEmpty() && isLanguageSupported(lang) ? lang : null;
    }

    @javax.annotation.Nullable
    private String singleBuiltInVariantOf(String lang) {
        String match = null;
        for (String code : BuiltInLanguages.codes()) {
            if (code.startsWith(lang + "_") && isLanguageSupported(code)) {
                if (match != null) {
                    return null;
                }
                match = code;
            }
        }
        return match;
    }

    /**
     * Resolves a player-facing message for the given client locale — the per-player entry
     * point for client-language detection. A {@code null} locale (or an unsupported one)
     * falls back to the configured default language; missing keys fall back to English.
     */
    public Component componentForLocale(@javax.annotation.Nullable Locale clientLocale,
                                        String key, NamedTextColor fallbackColor, Object... args) {
        String language = resolvePlayerLanguage(clientLocale);
        if (language.equals(currentLanguage)) {
            return component(key, fallbackColor, args);
        }
        String text = getForPlayerLanguage(language, resolveMessageKey(key), protectFormattingArguments(args));
        return restoreFormattingArguments(parseWithColors(text, fallbackColor));
    }

    private String getForPlayerLanguage(String language, String resolvedKey, Object... args) {
        if (!useExternalFiles) {
            return getForLanguage(language, resolvedKey, args);
        }
        ResourceBundle playerBundle = playerBundles.computeIfAbsent(language, this::loadPlayerBundleSafely);
        try {
            return formatMessageSafely(playerBundle.getString(resolvedKey), resolvedKey, args);
        } catch (MissingResourceException missing) {
            if (englishBundle != null && !"en".equals(language)) {
                try {
                    return formatMessageSafely(englishBundle.getString(resolvedKey), resolvedKey, args);
                } catch (MissingResourceException ignoredEn) {
                    logger.debug("英文回退中也缺少键 '{}'", resolvedKey);
                }
            }

            if (!currentLanguage.equalsIgnoreCase(language)
                    && !"en".equalsIgnoreCase(currentLanguage)) {
                try {
                    ResourceBundle fallback =
                            playerBundles.computeIfAbsent(currentLanguage, this::loadPlayerBundleSafely);
                    return formatMessageSafely(fallback.getString(resolvedKey), resolvedKey, args);
                } catch (MissingResourceException ignoredDefault) {
                    logger.debug("默认语言 '{}' 也缺少键 '{}'", currentLanguage, resolvedKey);
                }
            }
            logger.warn("语言 '{}' 找不到翻译键 '{}'", language, resolvedKey);
            return "Missing: " + resolvedKey;
        }
    }

    private ResourceBundle loadPlayerBundleSafely(String language) {
        try {
            return languageFileManager.loadLanguageBundle(language);
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("无法为客户端语言 '{}' 加载消息，使用默认值：{}",
                    language, e.getMessage());
            return bundle;
        }
    }

    /**
     * Parses a localized message that has already been resolved by a collaborator.
     * Prefer {@link #component(String, NamedTextColor, Object...)} when the key and
     * formatting arguments are still available, because that path keeps arguments literal.
     *
     * @param resolvedText already localized text
     * @param fallbackColor color used when the text contains no formatting codes
     * @return rendered Adventure component
     */
    public Component componentFromResolvedText(String resolvedText, NamedTextColor fallbackColor) {
        return parseWithColors(resolvedText, fallbackColor);
    }

    private static Component parseWithColors(String text, NamedTextColor fallbackColor) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }

        String normalizedText = MINIMESSAGE_HEX_COLOR.matcher(text).replaceAll("&#$1");
        normalizedText = SECTION_LEGACY_CODE.matcher(normalizedText).replaceAll("&$1");
        if (AMPERSAND_LEGACY_CODE.matcher(normalizedText).find()) {
            return LEGACY_COMPONENT_SERIALIZER.deserialize(normalizedText);
        }
        return Component.text(normalizedText, fallbackColor);
    }

    private static Object[] protectFormattingArguments(Object[] args) {
        if (args.length == 0) {
            return args;
        }

        Object[] protectedArgs = args.clone();
        for (int index = 0; index < protectedArgs.length; index++) {
            Object argument = protectedArgs[index];
            if (argument instanceof String stringArgument) {
                protectedArgs[index] = protectFormattingArgument(stringArgument);
            } else if (argument instanceof Character characterArgument) {
                protectedArgs[index] = protectFormattingArgument(characterArgument.toString());
            }
        }
        return protectedArgs;
    }

    private static String protectFormattingArgument(String argument) {
        return argument
                .replace("&", LITERAL_AMPERSAND_MARKER)
                .replace("§", LITERAL_SECTION_MARKER)
                .replace("<#", LITERAL_MINIMESSAGE_HEX_MARKER);
    }

    private static Component restoreFormattingArguments(Component component) {
        return component
                .replaceText(builder -> builder.matchLiteral(LITERAL_AMPERSAND_MARKER).replacement("&"))
                .replaceText(builder -> builder.matchLiteral(LITERAL_SECTION_MARKER).replacement("§"))
                .replaceText(builder -> builder.matchLiteral(LITERAL_MINIMESSAGE_HEX_MARKER).replacement("<#"));
    }

    /**
     * Gets a formatted message for a specific language.
     *
     * @param language Language code
     * @param key      Message key
     * @param args     Arguments for message formatting
     * @return Formatted message or the key if not found
     */
    public String getForLanguage(String language, String key, Object... args) {
        String resolvedKey = resolveMessageKey(key);
        Properties messages = loadMessages(language);
        String message = messages.getProperty(resolvedKey);

        if (message == null) {
            logger.debug("语言 '{}' 缺少消息键 '{}'，回退到英文",
                    resolvedKey, language);

            if (!"en".equals(language)) {
                Properties englishMessages = loadMessages("en");
                message = englishMessages.getProperty(resolvedKey);
            }

            if (message == null) {
                logger.warn("所有语言文件中都找不到消息键 '{}'", resolvedKey);
                message = resolvedKey;
            }
        }

        return formatMessageSafely(message, resolvedKey, args);
    }

    /**
     * Gets a message from the external ResourceBundle.
     * Handles missing keys gracefully by returning a fallback.
     *
     * @param key  Message key
     * @param args Arguments for message formatting
     * @return Formatted message or fallback if not found
     */
    private String getFromBundle(String key, Object... args) {
        String resolvedKey = resolveMessageKey(key);
        try {
            String message = bundle.getString(resolvedKey);
            return formatMessageSafely(message, resolvedKey, args);
        } catch (MissingResourceException e) {

            if (englishBundle != null && !"en".equals(currentLanguage)) {
                try {
                    String message = englishBundle.getString(resolvedKey);
                    logger.warn("语言 '{}' 缺少翻译键 '{}'——回退到英文",
                            resolvedKey, currentLanguage);
                    return formatMessageSafely(message, resolvedKey, args);
                } catch (MissingResourceException ignoredEn) {

                    logger.debug("英文回退中也缺少键 '{}'", resolvedKey);
                }
            }
            logger.warn("所有可用语言都缺少翻译键 '{}'", resolvedKey);
            return "Missing: " + resolvedKey;
        }
    }

    private String formatMessageSafely(String message, String key, Object... args) {
        if (args.length == 0) {
            return message;
        }

        try {
            return MessageFormat.format(normalizeMessagePattern(message), args);
        } catch (IllegalArgumentException e) {
            logger.warn("格式化消息 '{}' 失败：{}", key, e.getMessage());
            return message;
        }
    }

    private String normalizeMessagePattern(String message) {
        if (message.indexOf('{') < 0 && message.indexOf('\'') < 0) {
            return message;
        }
        return normalizedPatternCache.computeIfAbsent(message, Messages::normalizeMessageFormatPattern);
    }

    private static String normalizeMessageFormatPattern(String message) {
        return escapeMessageFormatApostrophes(convertBracePlaceholders(message));
    }

    private static String convertBracePlaceholders(String message) {
        if (message.indexOf("{}") < 0 || containsIndexedPlaceholders(message)) {
            return message;
        }

        StringBuilder sb = new StringBuilder(message.length() + 8);
        int start = 0;
        int index = 0;
        int pos;
        while ((pos = message.indexOf("{}", start)) >= 0) {
            sb.append(message, start, pos);
            sb.append('{').append(index++).append('}');
            start = pos + 2;
        }
        sb.append(message.substring(start));
        return sb.toString();
    }

    private static String escapeMessageFormatApostrophes(String message) {
        if (message.indexOf('\'') < 0) {
            return message;
        }

        StringBuilder sb = new StringBuilder(message.length() + 8);

        int index = 0;
        while (index < message.length()) {
            char character = message.charAt(index);
            if (character != '\'') {
                sb.append(character);
                index++;
            } else if (index + 1 < message.length() && message.charAt(index + 1) == '\'') {
                sb.append("''");
                index += 2;
            } else {
                sb.append("''");
                index++;
            }
        }
        return sb.toString();
    }

    private static boolean containsIndexedPlaceholders(String message) {
        for (int index = 0; index < message.length() - 2; index++) {
            if (message.charAt(index) != '{' || !Character.isDigit(message.charAt(index + 1))) {
                continue;
            }

            int end = index + 2;
            while (end < message.length() && Character.isDigit(message.charAt(end))) {
                end++;
            }
            if (end < message.length() && message.charAt(end) == '}') {
                return true;
            }
        }
        return false;
    }

    private static String resolveMessageKey(String key) {
        return DEPRECATED_KEY_ALIASES.getOrDefault(key, key);
    }

    /**
     * Checks if a language is supported.
     * For external file mode: checks if the language file exists.
     * For legacy JAR mode: checks if the resource exists.
     *
     * @param language Language code to check
     * @return true if supported
     */
    public boolean isLanguageSupported(String language) {
        if (language == null) return false;

        String lang = language.toLowerCase(Locale.ROOT);

        if (useExternalFiles && languageFileManager != null) {
            return BuiltInLanguages.isBuiltIn(lang) || languageFileManager.fileExists(lang);
        }
        return BuiltInLanguages.isBuiltIn(lang);
    }

    /**
     * Gets all supported language codes.
    * Returns built-in languages bundled with the plugin. Users can still add custom languages by placing
     * messages_*.properties files in the lang directory.
     *
     * @return Array of built-in language codes
     */
    public String[] getSupportedLanguages() {
        return BuiltInLanguages.codes();
    }

    /**
     * Clears the message cache.
     * This should be called during reload to ensure fresh messages are loaded.
     */
    public void clearCache() {
        int sizeBefore = messageCache.size();
        messageCache.clear();
        normalizedPatternCache.clear();
        if (sizeBefore > 0) {
            logger.info("消息缓存已清空（移除 {} 项）", sizeBefore);
        }
    }

    /**
     * Loads messages for a specific language from properties file.
     * Uses caching to avoid repeated file reads.
     *
     * @param language Language code
     * @return Properties containing messages
     */
    private Properties loadMessages(String language) {
        return messageCache.computeIfAbsent(language, this::loadMessagesFromFile);
    }

    /**
     * Loads messages from file system.
     *
     * @param language Language code
     * @return Properties containing messages
     */
    private Properties loadMessagesFromFile(String language) {
        Properties properties = new Properties();
        String fileName = "lang/messages_" + language + ".properties";

        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(fileName)) {
            if (inputStream != null) {
                properties.load(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
                logger.debug("从 {} 加载了 {} 条消息", properties.size(), fileName);
            } else {
                logger.error("找不到语言文件：{}", fileName);
            }
        } catch (IOException e) {
            logger.error("加载语言文件出错：{}", fileName, e);
        }

        return properties;
    }

}
