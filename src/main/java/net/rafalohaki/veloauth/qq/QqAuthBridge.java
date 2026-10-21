package net.rafalohaki.veloauth.qq;

/**
 * Narrow surface the internal HTTP bridge may use; implemented in the {@code command} package.
 */
public interface QqAuthBridge {

    /**
     * Consumes a bind code and creates the QQ-to-UUID binding.
     *
     * @param platform bot platform ("qq" for NapCat / real QQ numbers)
     * @param groupId  QQ group id (already allow-listed by the bridge)
     * @param qqId     sender QQ number
     * @param code     plaintext bind code typed in the group
     * @param nickname nickname reported by the bot (display only)
     * @return stable result code
     */
    QqResult bind(String platform, String groupId, String qqId, String code, String nickname);

    /**
     * Consumes a limbo quick-login code and authorizes the matching live connection.
     *
     * @param platform bot platform ("qq")
     * @param groupId  QQ group id (already allow-listed by the bridge)
     * @param qqId     sender QQ number
     * @param code     six-digit code shown in the game chat
     * @return stable result code
     */
    QqResult confirmLogin(String platform, String groupId, String qqId, String code);

    /**
     * Applies the group reply to a pending new-device approval.
     *
     * @param platform   bot platform ("qq")
     * @param groupId    QQ group id (already allow-listed by the bridge)
     * @param qqId       replier QQ number; must be the QQ bound to the pending account
     * @param decision   {@code Y}/{@code approve} to allow, {@code N}/{@code deny} to refuse
     * @param approvalId opaque token delivered with the approval notification
     * @return stable result code
     */
    QqResult decideDeviceApproval(String platform, String groupId, String qqId,
                                  String decision, String approvalId);

    /**
     * Applies the quote-reply {@code 阻止} to a cookie auto-login notification: kicks the
     * online player and revokes the account's device records.
     *
     * @param platform bot platform ("qq")
     * @param groupId  QQ group id (already allow-listed by the bridge)
     * @param qqId     replier QQ number; must be the QQ bound to the notified account
     * @param stopId   opaque token delivered with the auto-login notification
     * @return stable result code
     */
    QqResult stopAutoLogin(String platform, String groupId, String qqId, String stopId);

    /**
     * Returns and clears pending group notifications (auto-login alerts). The bot poller is
     * responsible for delivering them.
     *
     * @return never-null list; empty when nothing to deliver
     */
    java.util.List<QqNotify> pollNotifications();
}
