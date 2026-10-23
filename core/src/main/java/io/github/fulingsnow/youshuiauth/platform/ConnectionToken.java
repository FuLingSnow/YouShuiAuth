package io.github.fulingsnow.youshuiauth.platform;

import java.util.UUID;

public record ConnectionToken(UUID playerId, long generation) {

    public boolean sameConnection(ConnectionToken other) {
        return other != null && playerId.equals(other.playerId) && generation == other.generation;
    }
}
