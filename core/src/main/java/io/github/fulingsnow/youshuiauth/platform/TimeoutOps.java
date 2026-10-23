package io.github.fulingsnow.youshuiauth.platform;

import java.util.UUID;

public interface TimeoutOps {

    void renewForCode(AuthPlayer player, int codeTtlSeconds);

    void beginSession(AuthPlayer player);

    void suspend(UUID playerId);

    void cancel(UUID playerId);
}
