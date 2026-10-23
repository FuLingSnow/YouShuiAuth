package io.github.fulingsnow.youshuiauth.platform.quarantine;

import java.util.List;
import java.util.Locale;

/**
 * 隔离区判定策略：哪些命令能放行、玩家是否还站在隔离点所在方块上。
 *
 * <p>抽在 core 的原因：服务端平台（Paper/Fabric/NeoForge/Forge）的隔离区实现各写一份，
 * 但「放行名单」与「位置容差」必须是同一套规则——否则某个平台漏放行一条命令，
 * 玩家会在那一端卡死在隔离区。</p>
 */
public final class QuarantinePolicy {

    /** 未认证玩家可用的命令（含 Velocity 端注册的别名）。 */
    private static final List<String> ALLOWED_COMMANDS = List.of(
            "login", "log", "l", "register", "reg", "changepassword", "setpassword",
            "2fa", "totp", "twofa", "qqbind", "logout", "ysauth", "vauth");

    private QuarantinePolicy() {
    }

    /**
     * 判断玩家输入的命令是否在放行名单内。
     *
     * @param rawCommand 原始输入（可带前导斜杠、可带参数、大小写不限）
     */
    public static boolean isAllowedCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return false;
        }
        String name = rawCommand.trim().toLowerCase(Locale.ROOT).split("\\s+", 2)[0];
        if (name.startsWith("/")) {
            name = name.substring(1);
        }

        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return ALLOWED_COMMANDS.contains(name);
    }

    /** 两个坐标是否落在同一方块（隔离区只允许原地转头，不允许跨方块移动）。 */
    public static boolean isSameBlock(double fromX, double fromY, double fromZ,
                                      double toX, double toY, double toZ) {
        return block(fromX) == block(toX)
                && block(fromY) == block(toY)
                && block(fromZ) == block(toZ);
    }

    /** 与隔离点的距离是否已超出回拉容差（平方距离比较，省一次开方）。 */
    public static boolean isOutsideAnchor(double dx, double dy, double dz,
                                          double tolerance) {
        double limit = tolerance * tolerance;
        return dx * dx + dy * dy + dz * dz > limit;
    }

    public static List<String> allowedCommands() {
        return ALLOWED_COMMANDS;
    }

    private static long block(double coordinate) {
        return (long) Math.floor(coordinate);
    }
}
