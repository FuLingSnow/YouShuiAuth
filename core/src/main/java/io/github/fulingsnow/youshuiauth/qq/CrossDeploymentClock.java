package io.github.fulingsnow.youshuiauth.qq;

/**
 * 跨机房部署共享一个数据库时，写入端与校验端各用本地时钟，偏差会让
 * 免密记录/绑定码在某端提前失效。所有数据库时间戳的过期判断统一放宽
 * 一个固定容差（进程内的挑战与 TOTP 不经数据库，不受影响）。
 */
public final class CrossDeploymentClock {

    public static final long TOLERANCE_MS = 120_000L;

    private CrossDeploymentClock() {
    }

    public static long lowerBound(long now) {
        return now - TOLERANCE_MS;
    }

    public static boolean isUsable(long expiresAt, long now) {
        return expiresAt > lowerBound(now);
    }
}
