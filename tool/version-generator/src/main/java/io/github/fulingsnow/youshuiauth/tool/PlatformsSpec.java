package io.github.fulingsnow.youshuiauth.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

record PlatformsSpec(List<PlatformsSpec.Entry> entries) {

    record Entry(String platform, List<String> versions, String javaVersion) {
    }

    static PlatformsSpec parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        List<Entry> entries = new ArrayList<>();
        String current = null;
        List<String> versions = List.of();
        String javaVersion = "21";
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            boolean isRootKey = !line.startsWith(" ") && line.endsWith(":");
            boolean isPlatformKey = line.startsWith("  ") && !line.startsWith("   ")
                    && line.strip().endsWith(":");
            if (isRootKey) {
                continue;
            }
            if (isPlatformKey) {
                if (current != null) {
                    if (versions.isEmpty()) {
                throw new IOException("platform " + current
                        + " declares no versions (check indentation of the versions block)");
            }
            entries.add(new Entry(current, versions, javaVersion));
                }
                current = line.strip();
                current = current.substring(0, current.length() - 1).trim();
                versions = List.of();
                javaVersion = "21";
            } else if (current != null && line.strip().startsWith("versions:")) {
                versions = parseVersions(line.substring(line.indexOf(':') + 1));
            } else if (current != null && line.strip().startsWith("java_version:")) {
                javaVersion = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        if (current != null) {
            entries.add(new Entry(current, versions, javaVersion));
        }
        if (entries.isEmpty()) {
            throw new IOException("platforms.yml declares no platforms");
        }
        return new PlatformsSpec(entries);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }

    private static List<String> parseVersions(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1, trimmed.endsWith("]") ? trimmed.length() - 1 : trimmed.length());
        }
        List<String> out = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }
}
