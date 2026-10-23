package io.github.fulingsnow.youshuiauth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Utility methods for parsing YAML configuration values.
 * Extracted from Settings for single-responsibility and reuse.
 */
final class YamlParserUtils {

    private static final Logger logger = LoggerFactory.getLogger(YamlParserUtils.class);

    private YamlParserUtils() {}

    static String getString(Map<String, Object> map, String key, String defaultValue) {
        if (map == null || key == null) {
            logger.warn("getString 收到空 map 或空键，使用默认值：{}", defaultValue);
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return value.toString();
        } catch (Exception e) {
            logger.warn("键 '{}' 转字符串失败，使用默认值：{}", key, defaultValue);
            return defaultValue;
        }
    }

    static int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null || key == null) {
            logger.warn("getInt 收到空 map 或空键，使用默认值：{}", defaultValue);
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            logger.warn("键 '{}' 的整数值无效：{}，使用默认值：{}", key, value, defaultValue);
            return defaultValue;
        }
    }

    static long getLong(Map<String, Object> map, String key, long defaultValue) {
        if (map == null || key == null) {
            logger.warn("getLong 收到空 map 或空键，使用默认值：{}", defaultValue);
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            logger.warn("键 '{}' 的长整数值无效：{}，使用默认值：{}", key, value, defaultValue);
            return defaultValue;
        }
    }

    static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null || key == null) {
            logger.warn("getBoolean 收到空 map 或空键，使用默认值：{}", defaultValue);
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = value.toString().trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        logger.warn("键 '{}' 的布尔值无效：{}，使用默认值：{}", key, value, defaultValue);
        return defaultValue;
    }

    static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null || key == null) {
            logger.warn("getDouble 收到空 map 或空键，使用默认值：{}", defaultValue);
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            logger.warn("键 '{}' 的浮点值无效：{}，使用默认值：{}", key, value, defaultValue);
            return defaultValue;
        }
    }
}
