package io.github.fulingsnow.youshuiauth.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ReadUtils {

    private ReadUtils() {
    }

    static String readFirstLine(Path file, String fallback) {
        if (!Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            String line = Files.readString(file).strip();
            return line.isEmpty() ? fallback : line;
        } catch (IOException e) {
            return fallback;
        }
    }
}
