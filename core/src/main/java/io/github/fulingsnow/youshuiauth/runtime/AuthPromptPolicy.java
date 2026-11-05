package io.github.fulingsnow.youshuiauth.runtime;

/**
 * 未登录提醒用哪个文案：注册开放 / QQ 注册可用 / 全部关闭，三种说法。
 * 平台只负责把配置状态传进来，避免各端写死键名后说法不一致。
 */
public final class AuthPromptPolicy {

    public static final String GENERIC = "auth.prompt.generic";
    public static final String QQ = "auth.prompt.generic_qq";
    public static final String REGISTRATION_CLOSED = "auth.prompt.generic_closed";
    public static final String ACCOUNT_EXISTS = "auth.account_exists";
    public static final String ACCOUNT_EXISTS_QQ = "auth.account_exists_qq";
    public static final String FIRST_TIME = "auth.first_time";
    public static final String FIRST_TIME_BOTH = "auth.first_time_both";
    public static final String FIRST_TIME_QQ = "auth.first_time_qq";
    public static final String FIRST_TIME_CLOSED = "auth.first_time_closed";

    private AuthPromptPolicy() {
    }

    public static final String BUTTON_LOGIN = "auth.button.login";
    public static final String BUTTON_REGISTER = "auth.button.register";
    public static final String BUTTON_QQBIND = "auth.button.qqbind";
    public static final String BUTTON_PASSWORD = "auth.button.password";

    /**
     * 未登录提醒：文案 + 可点击按钮。按钮必须与文案一致——
     * 「请使用 QQ 登录」配 /qqbind，「请注册」配 /register，全关时不给按钮。
     */
    public record Reminder(String messageKey, String buttonKey, String command) {

        public boolean hasButton() {
            return command != null && buttonKey != null;
        }
    }

    /** 账号现状：决定未登录提醒用哪套说法与哪个按钮。 */
    public enum AccountKind {
        /** 数据库中不存在该昵称。 */
        NONE,
        /** 已注册且有密码（bcrypt）。 */
        PASSWORD,
        /** 已注册且绑定了 QQ。 */
        QQ_BOUND,
        /** 已注册但只有 QQ 标记（HASH=!qq-only），无密码。 */
        QQ_ONLY,
        /** 查询失败或尚未查完，按配置兜底。 */
        UNKNOWN
    }

    public static Reminder reminder(AccountKind kind, boolean passwordRegistrationEnabled,
                                    boolean qqEnabled) {
        return switch (kind) {
            case PASSWORD -> new Reminder(ACCOUNT_EXISTS, BUTTON_PASSWORD, "/login");
            case QQ_BOUND, QQ_ONLY -> new Reminder(ACCOUNT_EXISTS_QQ, BUTTON_LOGIN, "/login");
            case NONE -> {
                if (passwordRegistrationEnabled && qqEnabled) {
                    yield new Reminder(FIRST_TIME_BOTH, BUTTON_QQBIND, "/qqbind");
                }
                if (passwordRegistrationEnabled) {
                    yield new Reminder(FIRST_TIME, BUTTON_REGISTER, "/register");
                }
                if (qqEnabled) {
                    yield new Reminder(FIRST_TIME_QQ, BUTTON_QQBIND, "/qqbind");
                }
                yield new Reminder(FIRST_TIME_CLOSED, null, null);
            }
            case UNKNOWN -> {
                if (passwordRegistrationEnabled) {
                    yield new Reminder(GENERIC, BUTTON_PASSWORD, "/login");
                }
                if (qqEnabled) {
                    yield new Reminder(QQ, BUTTON_LOGIN, "/login");
                }
                yield new Reminder(REGISTRATION_CLOSED, null, null);
            }
        };
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
