package io.github.fulingsnow.youshuiauth.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

final class CopyUtils {

    private static final List<String> TEXT_EXTENSIONS =
            List.of(".gradle", ".properties", ".yml", ".yaml", ".json", ".txt", ".toml",
                    ".java", ".xml", ".md", ".sh", ".kts");

    private CopyUtils() {
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> doomed = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.forEach(doomed::add);
        }
        // 逆序：先删子项再删父目录。
        for (int i = doomed.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(doomed.get(i));
        }
    }

    static void copyTree(Path source, Path target, List<String> excludedDirNames,
                         UnaryOperator<String> lineSubstitutor) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String relative = source.relativize(path).toString();
                if (relative.isEmpty()) {
                    continue;
                }
                if (isExcluded(relative, excludedDirNames)) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    if (isTextFile(path)) {
                        substitute(path, destination, lineSubstitutor);
                    } else {
                        Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static boolean isExcluded(String relative, List<String> excludedDirNames) {
        for (String part : relative.split("[/\\\\]")) {
            if (excludedDirNames.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        for (String extension : TEXT_EXTENSIONS) {
            if (name.endsWith(extension) || name.equals("gradlew") || name.equals("gradlew.bat")) {
                return true;
            }
        }
        return false;
    }

    private static void substitute(Path source, Path target, UnaryOperator<String> lineSubstitutor)
            throws IOException {
        List<String> lines = Files.readAllLines(source);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            out.append(lineSubstitutor.apply(lines.get(i)));
            if (i < lines.size() - 1) {
                out.append(System.lineSeparator());
            }
        }
        Files.writeString(target, out.toString());
    }
}
