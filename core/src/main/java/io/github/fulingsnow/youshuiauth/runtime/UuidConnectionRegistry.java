package io.github.fulingsnow.youshuiauth.runtime;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ConnectionOps;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单实例服务端（Paper 等）使用的连接登记：玩家在同一时刻只有一个连接，
 * 因此只要 uuid + 代际就能判定「当前连接」，不需要按玩家实例比较。
 */
public final class UuidConnectionRegistry implements ConnectionOps {

    private final ConcurrentHashMap<java.util.UUID, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public void beginSession(AuthPlayer player) {
        if (player != null) {
            generations.put(player.uniqueId(), sequence.incrementAndGet());
        }
    }

    @Override
    public ConnectionRef capture(AuthPlayer player) {
        if (player == null) {
            return null;
        }
        long generation = generations.computeIfAbsent(player.uniqueId(),
                ignored -> sequence.incrementAndGet());
        return new Ref(player.uniqueId(), generation);
    }

    @Override
    public boolean isCurrent(ConnectionRef operation) {
        if (operation == null) {
            return false;
        }
        Long generation = generations.get(operation.playerId());
        return generation != null && generation == operation.generation();
    }

    @Override
    public boolean runIfCurrent(ConnectionRef operation, Runnable effect) {
        Objects.requireNonNull(effect, "effect");
        if (!isCurrent(operation)) {
            return false;
        }
        effect.run();
        return true;
    }

    @Override
    public boolean retire(AuthPlayer player) {
        return player != null && generations.remove(player.uniqueId()) != null;
    }

    private record Ref(java.util.UUID playerId, long generation) implements ConnectionRef {
    }
}
