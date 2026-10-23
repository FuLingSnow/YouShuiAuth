package io.github.fulingsnow.youshuiauth.platform;

import java.util.Optional;
import java.util.UUID;

public interface QqOps {

    String PLATFORM_QQ = "qq";
    String QQ_ONLY_HASH = "!qq-only";

    boolean isEnabled();

    String getJoinUrl();

    int getLoginCodeTtlSeconds();

    int getDeviceApprovalTimeoutSeconds();

    boolean isAccountQqBound(AuthPlayer player);

    boolean isDeviceApprovalPending(AuthPlayer player);

    BindCodeIssue createBindCodeOutcome(AuthPlayer player);

    Optional<String> issueLoginCodeManual(AuthPlayer player, ConnectionRef operation);

    /** 只读查看登录码，不消费；确认所有前置条件通过后再调 consumeLoginCode。 */
    Optional<io.github.fulingsnow.youshuiauth.qq.QqLoginChallenges.Challenge> peekLoginCode(
            String code);

    Optional<io.github.fulingsnow.youshuiauth.qq.QqLoginChallenges.Challenge> consumeLoginCode(
            String code);

    void rememberDevice(AuthPlayer player);

    void revokeRememberTokens(UUID playerId);

    record BindCodeIssue(io.github.fulingsnow.youshuiauth.qq.QqResult result, String code,
                         int ttlSeconds, boolean createsAccount) {
    }
}
