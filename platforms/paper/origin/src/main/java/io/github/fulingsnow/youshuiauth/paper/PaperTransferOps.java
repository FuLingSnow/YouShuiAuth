package io.github.fulingsnow.youshuiauth.paper;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.TransferOps;

/**
 * 单实例服务端没有「认证服 → 游戏服」的转服动作，认证完成即视为到位。
 */
public final class PaperTransferOps implements TransferOps {

    @Override
    public boolean isPlayerOnAuthServer(java.util.UUID playerId) {
        return true;
    }

    @Override
    public boolean transferToBackend(AuthPlayer player) {
        return true;
    }
}
