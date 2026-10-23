package io.github.fulingsnow.youshuiauth.config;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 升级时的配置合并器：以内置模板为骨架重建用户 config.yml，
 * 用户的叶子键值优先保留，模板新增键落默认值，模板已删除的键自动清理。
 * 模板文本按行处理（注释与结构原样保留），仅替换叶子键的值。
 */
public final class ConfigMigrator {

    private static final Pattern KEY_LINE =
            Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):\\s*(.*)$");
    private static final int MAX_BACKUPS = 3;
    private static final String BACKUP_PREFIX = "config-backup-";

    private ConfigMigrator() {}

    public record MigrationResult(boolean migrated, int keptValues, int addedKeys, int removedKeys,
                           Path backupPath) {
    }

    static MigrationResult migrateIfNeeded(Path configFile, String currentVersion,
                                           Logger logger) throws IOException {
        if (!Files.exists(configFile)) {
            return new MigrationResult(false, 0, 0, 0, null);
        }
        String oldVersion = readConfigVersion(configFile);
        if (currentVersion.equals(oldVersion)) {
            return new MigrationResult(false, 0, 0, 0, null);
        }
        Map<String, Object> userTree = loadUserTree(configFile);
        if (userTree.isEmpty()) {

            return new MigrationResult(false, 0, 0, 0, null);
        }
        try {

            SettingsLoader.load(configFile, logger);
        } catch (IOException | RuntimeException e) {
            logger.warn("配置迁移已跳过：现有配置文件加载失败（{}）",
                    e.toString());
            return new MigrationResult(false, 0, 0, 0, null);
        }

        return migrateWithTemplate(configFile, DefaultConfigGenerator.bundledTemplate(),
                currentVersion, logger);
    }

    private static Set<String> topLevelKeys(String yamlText) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : yamlText.split("\n", -1)) {
            Matcher m = KEY_LINE.matcher(line);
            if (m.matches() && m.group(1).isEmpty()) {
                keys.add(m.group(2));
            }
        }
        return keys;
    }

    /** 提取用户文件中顶层键不属于模板的段（含其全部缩进行），供合并后追加保留。 */
    private static String collectUnknownTopLevelSections(String userText,
                                                         Set<String> templateTopKeys) {
        StringBuilder preserved = new StringBuilder();
        boolean inUnknown = false;
        for (String line : userText.split("\n", -1)) {
            Matcher m = KEY_LINE.matcher(line);
            if (m.matches() && m.group(1).isEmpty()) {
                inUnknown = !templateTopKeys.contains(m.group(2));
            }
            if (inUnknown) {
                preserved.append(line).append('\n');
            }
        }
        return preserved.toString();
    }

    /** 模板由调用方提供：config.yml 与 platform.yml 共用同一套合并逻辑。 */
    public static MigrationResult migrateWithTemplate(Path configFile, String template,
                                                      String currentVersion,
                                                      Logger logger) throws IOException {
        return migrateWithTemplate(configFile, template, currentVersion, BACKUP_PREFIX, logger);
    }

    /** 同上，但允许自定义备份文件名前缀（避免平台配置备份到 config-backup-*）。 */
    public static MigrationResult migrateWithTemplate(Path configFile, String template,
                                                      String currentVersion, String backupPrefix,
                                                      Logger logger) throws IOException {
        String oldVersion = readConfigVersion(configFile);
        Map<String, Object> userValues = flatten(loadUserTree(configFile));
        Path backup = backupExisting(configFile, oldVersion, backupPrefix);

        MergeReport report = new MergeReport();
        String merged = mergeTemplate(template, userValues, currentVersion, report);
        String preserved = collectUnknownTopLevelSections(
                Files.readString(configFile, StandardCharsets.UTF_8),
                topLevelKeys(template));
        if (!preserved.isEmpty()) {
            merged = merged + "\n# ---- 以下配置段不属于当前模板，按原样保留 ----\n" + preserved;
        }

        Files.writeString(configFile, merged, StandardCharsets.UTF_8);
        logger.info("配置已迁移 {} -> {}：保留 {} 项用户自定义值，新增 {} 项配置，"
                        + "清理 {} 项废弃配置，备份位于 {}",
                oldVersion == null ? "legacy" : oldVersion, currentVersion,
                report.kept, report.added, report.removed, backup);
        return new MigrationResult(true, report.kept, report.added, report.removed, backup);
    }

    private static String readConfigVersion(Path configFile) throws IOException {
        for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
            Matcher m = KEY_LINE.matcher(line);
            if (m.matches() && "config-version".equals(m.group(2))) {
                return stripQuotes(m.group(3));
            }
        }
        return null;
    }

    private static String stripQuotes(String value) {
        String v = value.trim();
        return v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'"))) ? v.substring(1, v.length() - 1) : v;
    }

    private static Path backupExisting(Path configFile, String oldVersion,
                                       String backupPrefix) throws IOException {
        String label = oldVersion == null || oldVersion.isBlank() ? "legacy" : oldVersion;
        Path backup = configFile.resolveSibling(backupPrefix + label + ".yml");
        Files.copy(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
        pruneOldBackups(configFile.getParent(), backupPrefix);
        return backup;
    }

    private static void pruneOldBackups(Path dir, String backupPrefix) throws IOException {
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith(backupPrefix))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(backups::add);
        }
        while (backups.size() > MAX_BACKUPS) {
            Files.deleteIfExists(backups.remove(0));
        }
    }

    private static Map<String, Object> loadUserTree(Path configFile) throws IOException {
        ConfigurationNode root = YamlConfigurationLoader.builder()
                .path(configFile)
                .build()
                .load();
        Map<String, Object> tree = new LinkedHashMap<>();
        if (root != null && root.isMap()) {
            for (var entry : root.childrenMap().entrySet()) {
                tree.put(String.valueOf(entry.getKey()), toJava(entry.getValue()));
            }
        }
        return tree;
    }

    private static Object toJava(ConfigurationNode node) {
        if (node.isMap()) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (var entry : node.childrenMap().entrySet()) {
                nested.put(String.valueOf(entry.getKey()), toJava(entry.getValue()));
            }
            return nested;
        }
        if (node.isList()) {
            return node.childrenList().stream().map(ConfigMigrator::toJava).toList();
        }
        Object raw = node.raw();
        return raw == null ? "" : raw;
    }

    private static Map<String, Object> flatten(Map<String, Object> tree) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flattenInto("", tree, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flattenInto(String prefix, Map<String, Object> tree,
                                    Map<String, Object> out) {
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flattenInto(key, (Map<String, Object>) nested, out);
            } else {
                out.put(key, entry.getValue());
            }
        }
    }

    private record PathLevel(int indent, String key) {
    }

    private static String mergeTemplate(String template, Map<String, Object> userValues,
                                        String currentVersion, MergeReport report) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        List<PathLevel> pathStack = new ArrayList<>();
        Set<String> consumed = new LinkedHashSet<>();

        String normalized = template.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                out.append(line).append('\n');
                continue;
            }
            if (line.contains("__CONFIG_VERSION__")) {
                consumed.add("config-version");
                out.append(line).append('\n');
                continue;
            }
            int indent = m.group(1).length();
            String key = m.group(2);
            String value = m.group(3).strip();
            while (!pathStack.isEmpty()
                    && pathStack.get(pathStack.size() - 1).indent() >= indent) {
                pathStack.remove(pathStack.size() - 1);
            }
            String fullPath = buildPath(pathStack, key);

            if (value.isEmpty()) {
                pathStack.add(new PathLevel(indent, key));
                out.append(line).append('\n');
                continue;
            }

            if (userValues.containsKey(fullPath)) {
                out.append(m.group(1)).append(key).append(": ")
                        .append(render(userValues.get(fullPath)));
                int commentAt = line.indexOf(" #");
                if (commentAt >= 0) {
                    out.append(' ').append(line.substring(commentAt + 1).stripTrailing());
                }
                out.append('\n');
                report.kept++;
                consumed.add(fullPath);
                continue;
            }
            out.append(line).append('\n');
            report.added++;
        }
        report.removed = userValues.size() - consumed.size();
        return out.toString().replace("__CONFIG_VERSION__", currentVersion);
    }

    private static String buildPath(List<PathLevel> stack, String key) {
        StringBuilder builder = new StringBuilder();
        for (var level : stack) {
            builder.append(level.key()).append('.');
        }
        return builder.append(key).toString();
    }

    private static String render(Object value) {
        if (value instanceof String s) {
            return needsQuoting(s)
                    ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                    : s;
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(render(list.get(i)));
            }
            return builder.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static boolean needsQuoting(String s) {
        if (s.isBlank()) {
            return true;
        }
        return s.contains(": ") || s.endsWith(":") || s.contains(" #") || s.startsWith("#")
                || s.contains("{") || s.contains("[") || s.contains(", ");
    }

    private static final class MergeReport {
        int kept;
        int added;
        int removed;
    }
}
