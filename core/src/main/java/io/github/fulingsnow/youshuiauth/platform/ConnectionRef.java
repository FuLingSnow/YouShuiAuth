package io.github.fulingsnow.youshuiauth.platform;

import java.util.UUID;

public interface ConnectionRef {

    UUID playerId();

    long generation();
}
