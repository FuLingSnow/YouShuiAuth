package net.rafalohaki.veloauth.qq;

/**
 * Stable result codes returned by the QQ bridge and rendered by the AstrBot plugin.
 *
 * <p>Codes are part of the bot contract: rename a constant only together with the plugin.
 * Business failures are returned with HTTP 200; transport/auth failures use 400/401.
 */
public enum QqResult {

    OK("ok"),
    REGISTERED("registered"),
    DISABLED("disabled"),
    NOT_AUTHENTICATED("not_authenticated"),
    INVALID_CODE("invalid_code"),
    CODE_EXPIRED("code_expired"),
    ALREADY_BOUND("already_bound"),
    ACCOUNT_EXISTS("account_exists"),
    UUID_BOUND_TO_OTHER("uuid_bound_to_other"),
    NOT_BOUND("not_bound"),
    UUID_MISMATCH("uuid_mismatch"),
    NOT_ONLINE("not_online"),
    ALREADY_AUTHORIZED("already_authorized"),
    TOTP_REQUIRED("totp_required"),
    RATE_LIMITED("rate_limited"),
    GROUP_NOT_ALLOWED("group_not_allowed"),
    ERROR("error");

    private final String code;

    QqResult(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean isOk() {
        return this == OK || this == REGISTERED;
    }
}
