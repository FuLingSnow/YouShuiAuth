package net.rafalohaki.veloauth.qq;

/**
 * One pending notification for the bot poller; routing and delivery are the bot's job.
 *
 * <p>{@code type} lets the bot distinguish a plain auto-login notice from a new-device approval
 * request. For {@link QqNotify#TYPE_DEVICE_APPROVAL}, {@code approvalId} is the opaque token the
 * bot must send back with the group reply (approval endpoint) so the server can match the reply
 * to the pending request and verify the replier owns the binding. The field is additive: bots
 * that only read {@code qqId} and {@code message} keep working.
 */
public record QqNotify(String qqId, String message, String type, String approvalId) {

    public static final String TYPE_AUTO_LOGIN = "auto_login";
    public static final String TYPE_DEVICE_APPROVAL = "device_approval";

    /**
     * Backwards-compatible constructor for ordinary auto-login notifications.
     */
    public QqNotify(String qqId, String message) {
        this(qqId, message, TYPE_AUTO_LOGIN, null);
    }

    /**
     * Backwards-compatible constructor for typed notifications without an approval token.
     */
    public QqNotify(String qqId, String message, String type) {
        this(qqId, message, type, null);
    }

    public QqNotify {
        if (type == null || type.isBlank()) {
            type = TYPE_AUTO_LOGIN;
        }
        approvalId = approvalId == null || approvalId.isBlank() ? null : approvalId.trim();
    }
}
