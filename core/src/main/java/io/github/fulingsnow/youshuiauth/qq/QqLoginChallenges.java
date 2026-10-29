package io.github.fulingsnow.youshuiauth.qq;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.fulingsnow.youshuiauth.platform.ConnectionRef;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory quick-login challenges.
 *
 * <p>Codes are never persisted: they are bound to a concrete connection generation, expire
 * after {@code qq.login-code-ttl-seconds} and are consumed exactly once. Losing them on
 * restart is correct — the player can request a new code with {@code /login}.
 */
public final class QqLoginChallenges {

    private static final int CODE_LENGTH = 6;
    private static final int MAX_ISSUE_ATTEMPTS = 16;
    private static final int CODE_UPPER_BOUND = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Cache<String, Challenge> challenges;
    private final Cache<UUID, String> activeCodes;
    private final long ttlMillis;

    public QqLoginChallenges(int ttlSeconds) {
        this.ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.challenges = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .build();
        this.activeCodes = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .build();
    }

    /**
     * Returns the live code for this connection, or creates one. A player never has two valid
     * login codes at the same time: re-requesting before expiry returns the same code, and a
     * code for a new connection generation invalidates the previous one.
     *
     * @return empty only when the six-digit space is saturated by live challenges
     */
    public Optional<String> issue(UUID uuid, ConnectionRef operation) {
        long now = System.currentTimeMillis();
        String existing = activeCodes.getIfPresent(uuid);
        if (existing != null) {
            Challenge challenge = challenges.getIfPresent(existing);
            if (challenge != null && !challenge.isExpired(now)
                    && sameConnection(challenge.operation(), operation)) {
                return Optional.of(existing);
            }

            challenges.invalidate(existing);
            activeCodes.invalidate(uuid);
        }

        long expiresAt = now + ttlMillis;
        Challenge challenge = new Challenge(uuid, operation, expiresAt);
        for (int attempt = 0; attempt < MAX_ISSUE_ATTEMPTS; attempt++) {
            String code = String.format("%0" + CODE_LENGTH + "d", RANDOM.nextInt(CODE_UPPER_BOUND));
            if (challenges.asMap().putIfAbsent(code, challenge) == null) {
                activeCodes.put(uuid, code);
                return Optional.of(code);
            }
        }
        return Optional.empty();
    }

    private boolean sameConnection(ConnectionRef first,
                                   ConnectionRef second) {
        if (first == null || second == null) {
            return Objects.equals(first, second);
        }
        return first.generation() == second.generation()
                && first.playerId().equals(second.playerId());
    }

    /** 只读查看，不占用：用于在真正消费之前完成过期/连接/已授权等校验。 */
    public Optional<Challenge> peek(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(challenges.getIfPresent(code.trim()));
    }

    /**
     * Removes and returns the challenge. The single consumer wins; a replay sees empty.
     */
    public Optional<Challenge> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Challenge challenge = challenges.asMap().remove(code.trim());
        if (challenge != null) {
            activeCodes.asMap().remove(challenge.uuid(), code.trim());
        }
        return Optional.ofNullable(challenge);
    }

    public void clear() {
        challenges.invalidateAll();
        activeCodes.invalidateAll();
    }

    /**
     * A live challenge: {@code operation} is used by the caller to prove the connection is still
     * the one that received the code.
     */
    public record Challenge(UUID uuid, ConnectionRef operation, long expiresAt) {

        public boolean isExpired(long now) {
            return expiresAt <= now;
        }
    }
}
