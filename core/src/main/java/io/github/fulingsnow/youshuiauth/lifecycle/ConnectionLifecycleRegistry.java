package io.github.fulingsnow.youshuiauth.lifecycle;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns the concrete-player generation allowed to mutate UUID-keyed authentication state.
 * Publication, retirement and guarded effects for one UUID share a striped lock, so cleanup from
 * an older connection cannot overlap publication of its replacement.
 */
@SuppressWarnings("PMD.CompareObjectsWithEquals")
public class ConnectionLifecycleRegistry {

    private static final int LOCK_STRIPES = 256;
    private static final String PLAYER_PARAM = "player";

    private final ConcurrentMap<UUID, ConnectionState> connections = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock[] lifecycleLocks = createLocks();

    /**
     * Publishes a fresh generation and runs connection-manager publication plus previous-owner
     * cleanup while replacement/retirement for the same UUID is excluded.
     */
    public ConnectionRef activate(AuthPlayer player, Consumer<AuthPlayer> afterPublication) {
        Objects.requireNonNull(player, PLAYER_PARAM);
        Objects.requireNonNull(afterPublication, "afterPublication");
        UUID playerId = player.uniqueId();
        ReentrantLock lock = lockFor(playerId);
        lock.lock();
        try {
            if (closed.get()) {
                return null;
            }
            ConnectionState replacement = new ConnectionState(
                    playerId, player, generation.incrementAndGet());
            ConnectionState previous = connections.put(playerId, replacement);
            AuthPlayer previousOwner = previous == null || previous.owner == player
                    ? null
                    : previous.owner;
            try {
                afterPublication.accept(previousOwner);
            } catch (RuntimeException | Error failure) {

                replacement.retired = true;
                connections.remove(playerId, replacement);
                throw failure;
            }
            return new ConnectionRef(replacement);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retires the concrete owner after atomically clearing its connection-bound state. Cleanup
     * runs before the retirement linearization point so a transfer that observed a current token
     * cannot enter a still-owned manager generation after logout wins.
     */
    public boolean markRetired(AuthPlayer player, Runnable cleanup) {
        Objects.requireNonNull(player, PLAYER_PARAM);
        Objects.requireNonNull(cleanup, "cleanup");
        return withConnectionState(player, current -> {
            if (current == null || current.owner != player) {
                return false;
            }
            try {
                cleanup.run();
            } finally {
                current.retired = true;
            }
            return true;
        });
    }

    /**
     * Retires and cleans the concrete owner. When {@code allowUnowned} is true, successful
     * pre-publication disconnects may still run cleanup, but only while no replacement is present.
     */
    public boolean retire(AuthPlayer player, boolean allowUnowned, Runnable cleanup) {
        Objects.requireNonNull(player, PLAYER_PARAM);
        Objects.requireNonNull(cleanup, "cleanup");
        UUID playerId = player.uniqueId();
        return withConnectionState(player, current -> {
            if (current != null) {
                if (current.owner != player) {
                    return false;
                }
                current.retired = true;
                connections.remove(playerId, current);
            } else if (!allowUnowned) {
                return false;
            }
            cleanup.run();
            return true;
        });
    }

    /** Captures the current concrete generation, or {@code null} after logout/replacement. */
    public ConnectionRef capture(AuthPlayer player) {
        Objects.requireNonNull(player, PLAYER_PARAM);
        UUID playerId = player.uniqueId();
        ReentrantLock lock = lockFor(playerId);
        lock.lock();
        try {
            if (closed.get()) {
                return null;
            }
            ConnectionState current = connections.get(playerId);
            if (current == null || current.owner != player || current.retired) {
                return null;
            }
            return new ConnectionRef(current);
        } finally {
            lock.unlock();
        }
    }

    /** Returns whether the token still owns the active, non-retired generation. */
    public boolean isCurrent(ConnectionRef operation) {
        if (operation == null) {
            return false;
        }
        ConnectionState state = operation.state;
        return !closed.get() && !state.retired && connections.get(state.playerId) == state;
    }

    /** Runs a small connection-bound effect atomically with respect to logout/replacement. */
    public boolean runIfCurrent(ConnectionRef operation, Runnable effect) {
        Objects.requireNonNull(effect, "effect");
        if (operation == null) {
            return false;
        }
        ConnectionState state = operation.state;
        ReentrantLock lock = lockFor(state.playerId);
        lock.lock();
        try {
            if (closed.get() || state.retired || connections.get(state.playerId) != state) {
                return false;
            }
            effect.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef ref) {
        if (ref == null) {
            return false;
        }
        ConnectionState state = connections.get(ref.playerId());
        return !closed.get() && state != null && !state.retired
                && state.generation == ref.generation();
    }

    public boolean runIfCurrent(io.github.fulingsnow.youshuiauth.platform.ConnectionRef ref, Runnable effect) {
        Objects.requireNonNull(effect, "effect");
        if (ref == null) {
            return false;
        }
        ReentrantLock lock = lockFor(ref.playerId());
        lock.lock();
        try {
            ConnectionState state = connections.get(ref.playerId());
            if (closed.get() || state == null || state.retired
                    || state.generation != ref.generation()) {
                return false;
            }
            effect.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the registry at a lock-linearized barrier. Once this returns, a racing activation
     * cannot publish a generation and every captured operation is terminally stale.
     */
    public void close() {
        closed.compareAndSet(false, true);
        for (ReentrantLock lock : lifecycleLocks) {
            lock.lock();
        }
        try {
            connections.clear();
        } finally {
            for (int index = lifecycleLocks.length - 1; index >= 0; index--) {
                lifecycleLocks[index].unlock();
            }
        }
    }

    private ReentrantLock lockFor(UUID playerId) {
        int index = playerId.hashCode() & (LOCK_STRIPES - 1);
        return lifecycleLocks[index];
    }

    private boolean withConnectionState(
            AuthPlayer player, Predicate<ConnectionState> operation) {
        UUID playerId = player.uniqueId();
        ReentrantLock lock = lockFor(playerId);
        lock.lock();
        try {
            return !closed.get() && operation.test(connections.get(playerId));
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    /** Opaque concrete-generation capability carried by connection-bound operations. */
    public static final class ConnectionRef implements io.github.fulingsnow.youshuiauth.platform.ConnectionRef {
        private final ConnectionState state;

        private ConnectionRef(ConnectionState state) {
            this.state = state;
        }

        @Override
        public long generation() {
            return state.generation;
        }

        @Override
        public UUID playerId() {
            return state.playerId;
        }
    }

    private static final class ConnectionState {
        private final UUID playerId;
        private final AuthPlayer owner;
        private final long generation;
        private volatile boolean retired;

        private ConnectionState(UUID playerId, AuthPlayer owner, long generation) {
            this.playerId = playerId;
            this.owner = owner;
            this.generation = generation;
        }
    }
}
