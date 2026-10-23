package io.github.fulingsnow.youshuiauth.platform;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PlatformAccess {

    Optional<AuthPlayer> player(UUID uniqueId);

    Optional<AuthPlayer> player(String username);

    Collection<AuthPlayer> onlinePlayers();

    int playerCount();

    PlatformScheduler scheduler();

    Path dataDirectory();

    String platformId();
}
