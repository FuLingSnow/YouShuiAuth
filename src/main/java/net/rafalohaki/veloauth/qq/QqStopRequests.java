package net.rafalohaki.veloauth.qq;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory "stop my cookie auto-login" requests waiting for a quote-reply in the bound QQ
 * group. One stopId is issued with every auto-login notification; consuming it kicks the
 * online player and revokes the account's device records. Entries expire unused and are
 * consumed exactly once; losing them on restart is harmless because the bot then simply
 * cannot block that historical login.
 */
public final class QqStopRequests {

    private static final int MAX_PENDING = 10_000;

    private final Cache<String, StopRequest> requests;

    public QqStopRequests(int ttlSeconds) {
        long ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.requests = Caffeine.newBuilder()
                .maximumSize(MAX_PENDING)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .build();
    }

    public void put(StopRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requests.put(request.stopId(), request);
    }

    /**
     * @return the live request identified by this opaque stopId without consuming it
     */
    public Optional<StopRequest> peek(String stopId) {
        if (isBlank(stopId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(requests.getIfPresent(stopId.trim()));
    }

    /**
     * Removes and returns the request identified by this opaque stopId.
     */
    public Optional<StopRequest> consume(String stopId) {
        if (isBlank(stopId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(requests.asMap().remove(stopId.trim()));
    }

    public void clear() {
        requests.invalidateAll();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * One pending block request. {@code qqId} is the owner snapshot at issue time; the live
     * binding table remains the authority and is re-checked before consuming.
     */
    public record StopRequest(String platform, String qqId, UUID uuid, String stopId,
                              long expiresAt) {

        public StopRequest {
            Objects.requireNonNull(platform, "platform must not be null");
            Objects.requireNonNull(qqId, "qqId must not be null");
            Objects.requireNonNull(uuid, "uuid must not be null");
            if (stopId == null || stopId.isBlank()) {
                throw new IllegalArgumentException("stopId must not be blank");
            }
        }

        public boolean isExpired(long now) {
            return expiresAt <= now;
        }
    }
}
