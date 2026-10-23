package io.github.fulingsnow.youshuiauth.runtime;

/**
 * 未认证会话的超时时刻计算。
 *
 * <p>代理端与服务端各自维护调度器，但「会话能撑多久、验证码能把窗口延到多久」
 * 必须是同一套算法——两端各写一遍曾导致 Paper 把会话缩短成验证码有效期。</p>
 */
public final class AuthDeadlinePolicy {

    /** 验证码窗口在 TTL 之外的宽限。 */
    public static final int RENEWAL_GRACE_SECONDS = 30;

    /** 单次会话最长窗口，防止反复续期把未认证连接永久挂住。 */
    public static final long MAX_SESSION_WINDOW_MILLIS = 600_000L;

    /** 未登录提醒的重复间隔。 */
    public static final int REMINDER_INTERVAL_SECONDS = 10;

    /** 首次提醒延迟：进服瞬间的提示常被客户端丢弃。 */
    public static final int INITIAL_REMINDER_DELAY_SECONDS = 1;

    private AuthDeadlinePolicy() {
    }

    /** 会话开启时的超时时刻。 */
    public static long initialDeadline(long now, long sessionStart, long baseMillis) {
        return clamp(Math.max(now, sessionStart) + Math.max(0L, baseMillis), sessionStart);
    }

    /** 周期性重新武装（进服/重连）时的超时时刻：不缩短已存在的计时。 */
    public static long scheduleDeadline(long now, long sessionStart,
                                        long currentDeadline, long baseMillis) {
        return clamp(Math.max(currentDeadline, now + Math.max(0L, baseMillis)), sessionStart);
    }

    /** 发出验证码后的超时时刻。 */
    public static long renewDeadline(long now, long sessionStart, long currentDeadline,
                                     long baseMillis, int codeTtlSeconds) {
        long codeWindow = (Math.max(0, codeTtlSeconds) + RENEWAL_GRACE_SECONDS) * 1000L;
        long candidate = Math.max(currentDeadline,
                Math.max(sessionStart + Math.max(0L, baseMillis), now + codeWindow));
        return clamp(candidate, sessionStart);
    }

    /** 会话窗口截断：两端的 deadline 计算都必须经过这里，避免各写一份 min。 */
    public static long clamp(long deadline, long sessionStart) {
        return Math.min(deadline, sessionStart + MAX_SESSION_WINDOW_MILLIS);
    }
}
