package io.github.fulingsnow.youshuiauth.runtime;

/**
 * 未登录提醒用哪个文案：注册开放 / QQ 注册可用 / 全部关闭，三种说法。
 * 平台只负责把配置状态传进来，避免各端写死键名后说法不一致。
 */
public final class AuthPromptPolicy {

    public static final String GENERIC = "auth.prompt.generic";
    public static final String QQ = "auth.prompt.generic_qq";
    public static final String REGISTRATION_CLOSED = "auth.prompt.generic_closed";
    public static final String FIRST_TIME = "auth.first_time";
    public static final String FIRST_TIME_QQ = "auth.first_time_qq";
    public static final String FIRST_TIME_CLOSED = "auth.first_time_closed";

    private AuthPromptPolicy() {
    }

    public static String promptKey(boolean passwordRegistrationEnabled, boolean qqEnabled) {
        if (passwordRegistrationEnabled) {
            return GENERIC;
        }
        return qqEnabled ? QQ : REGISTRATION_CLOSED;
    }

    /** 首次进服（账号不存在）时的提示：注册开放 / 仅 QQ / 全部关闭。 */
    public static String firstTimePromptKey(boolean passwordRegistrationEnabled, boolean qqEnabled) {
        if (passwordRegistrationEnabled) {
            return FIRST_TIME;
        }
        return qqEnabled ? FIRST_TIME_QQ : FIRST_TIME_CLOSED;
    }

    public static String promptKeyForQqAccount() {
        return QQ;
    }
}
