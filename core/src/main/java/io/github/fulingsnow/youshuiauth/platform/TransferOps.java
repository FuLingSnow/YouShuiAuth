package io.github.fulingsnow.youshuiauth.platform;

public interface TransferOps {

    boolean isPlayerOnAuthServer(java.util.UUID playerId);

    boolean transferToBackend(AuthPlayer player);
}
