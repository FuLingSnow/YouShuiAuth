package io.github.fulingsnow.youshuiauth.qq;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;
import io.github.fulingsnow.youshuiauth.platform.QqOps;

import java.util.Optional;
import java.util.UUID;

/**
 * QQ 未启用时的空实现。
 *
 * <p>返回 null 会让所有 QqOps 调用点各自判空——漏一处就是运行期 NPE（/qqbind 就踩过），
 * 所以这里给一个「一切皆禁用」的对象，让调用点只处理结果枚举。</p>
 */
@SuppressWarnings("PMD.UncommentedEmptyMethodBody")
public final class DisabledQqOps implements QqOps {

    public static final DisabledQqOps INSTANCE = new DisabledQqOps();

    private DisabledQqOps() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String getJoinUrl() {
        return "";
    }

    @Override
    public int getLoginCodeTtlSeconds() {
        return 0;
    }

    @Override
    public int getDeviceApprovalTimeoutSeconds() {
        return 0;
    }

    @Override
    public boolean isAccountQqBound(AuthPlayer player) {
        return false;
    }

    @Override
    public boolean isDeviceApprovalPending(AuthPlayer player) {
        return false;
    }

    @Override
    public BindCodeIssue createBindCodeOutcome(AuthPlayer player) {
        return new BindCodeIssue(QqResult.DISABLED, null, 0, false);
    }

    @Override
    public Optional<String> issueLoginCodeManual(AuthPlayer player, ConnectionRef operation) {
        return Optional.empty();
    }

    @Override
    public Optional<io.github.fulingsnow.youshuiauth.qq.QqLoginChallenges.Challenge> peekLoginCode(
            String code) {
        return Optional.empty();
    }

    @Override
    public Optional<io.github.fulingsnow.youshuiauth.qq.QqLoginChallenges.Challenge> consumeLoginCode(
            String code) {
        return Optional.empty();
    }

    @Override
    public void rememberDevice(AuthPlayer player) {
        /* no-op */
    }

    @Override
    public void revokeRememberTokens(UUID playerId) {

    }
}
