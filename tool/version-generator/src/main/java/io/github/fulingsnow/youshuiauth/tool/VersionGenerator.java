package io.github.fulingsnow.youshuiauth.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class VersionGenerator {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: version-generator <repo-root>");
            System.exit(2);
            return;
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int generated = new VersionGenerator().generate(root);
        System.out.println("generated " + generated + " project(s) under " + root);
    }

    public int generate(Path repoRoot) throws IOException {
        Path specFile = repoRoot.resolve("platforms.yml");
        if (!Files.isRegularFile(specFile)) {
            throw new IOException("missing platforms.yml under " + repoRoot);
        }
        PlatformsSpec spec = PlatformsSpec.parse(specFile);
        int count = 0;
        for (PlatformsSpec.Entry entry : spec.entries()) {
            Path origin = repoRoot.resolve("platforms").resolve(entry.platform()).resolve("origin");
            if (!Files.isDirectory(origin)) {
                System.out.println("skip " + entry.platform() + ": no origin template yet");
                continue;
            }
            for (String version : entry.versions()) {
                if (generateVersion(repoRoot, entry, origin, version)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean generateVersion(Path repoRoot, PlatformsSpec.Entry entry, Path origin, String version)
            throws IOException {
        Path target = repoRoot.resolve("platforms")
                .resolve(entry.platform())
                .resolve(entry.platform() + "-" + version);
        if (Files.exists(target)) {
            // 生成目录是派生物（不入库、不应手改）：整目录重建，让 origin 的修复能传过来。
            // 早期"存在即跳过"会让生成工程永久停留在旧模板上。
            CopyUtils.deleteTree(target);
            System.out.println("refresh " + entry.platform() + " " + version);
        }
        CopyUtils.copyTree(origin, target,
                List.of("build", ".gradle", ".git", ".github"),
                line -> line
                        .replace("${mc_version}", version)
                        .replace("${java_version}", entry.javaVersion())
                        .replace("${mod_version}", ReadUtils.readFirstLine(repoRoot.resolve("version.txt"), "0.0.0"))
                        .replace("${tool_version}",
                                ReadUtils.readFirstLine(repoRoot.resolve("tool_version.txt"), "0.0.0")));
        Files.writeString(target.resolve("support_version.txt"), version + System.lineSeparator());
        return true;
    }
}
