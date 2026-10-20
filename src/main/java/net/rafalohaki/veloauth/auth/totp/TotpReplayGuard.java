package net.rafalohaki.veloauth.auth.totp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.UUID;

/**
 * Per-player one-time-only enforcement for TOTP codes (RFC 6238 §5.2).
 * <p>
 * Stores the absolute window counter ({@code epochSeconds / 30}) of the last successfully
 * accepted code for each player. A second verify with a code from the <em>same</em> window
 * (or any window already consumed within the tolerance band) is rejected even if the code
 * itself is otherwise valid.
 * <p>
 * Caffeine entries are evicted after {@link #ENTRY_TTL} — long enough to cover the entire
 * tolerance band several times over, short enough to bound memory under churn. After
 * eviction, replay protection naturally rolls forward; an attacker who waits that long has
 * lost the live code anyway.
 *
 * <h2>Race</h2>
 * Concurrent verify calls for the same player on the same window: only the first
 * {@link #consume} returns {@code true}; the other returns {@code false}. The check is a
 * {@code compute} on Caffeine, which holds a per-key lock for the lambda — so two threads
 * cannot both observe "not yet consumed" and both succeed.
 */
public final class TotpReplayGuard {

    /** Bound the cache lifetime to a few tolerance windows so memory stays small under
     *  churn. 10 minutes covers ±5 windows of clock skew (the {@code MAX_TOLERANCE} cap
     *  in {@link TotpService}) twenty times over. */
    private static final Duration ENTRY_TTL = Duration.ofMinutes(10);

    /** Upper bound on tracked players. Each entry is ~24 bytes — 100k entries ≈ 2.4 MB,
     *  which is plenty even for the largest plausible proxy populations. */
    private static final int MAX_ENTRIES = 100_000;

    private final Cache<UUID, Long> lastConsumedWindow;

    public TotpReplayGuard() {
        this.lastConsumedWindow = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(ENTRY_TTL)
                .build();
    }

    /**
     * Attempts to claim {@code window} as "just used" for {@code playerUuid}. Returns
     * {@code true} if the claim succeeded (caller may treat the code as accepted),
     * {@code false} if any window {@code >= window} was already consumed (replay).
     * <p>
     * The {@code >=} comparison covers the tolerance band: if window N was consumed and
     * the same code happens to also match window N-1 (different physical OTP slot but same
     * 6-digit value by accident), a replay at N-1 is rejected — the player would have to
     * roll forward to N+1 with a freshly-generated code.
     */
    public boolean consume(UUID playerUuid, long window) {
        if (playerUuid == null) {
            return false;
        }
        // boolean[1] holds the decision out of the compute lambda. Cannot rely on comparing
        // {@code updated == window} because the "replay of identical window" case would still
        // satisfy that equality (the stored value already <em>is</em> {@code window}).
        boolean[] accepted = new boolean[1];
        lastConsumedWindow.asMap().compute(playerUuid, (uuid, lastWindow) -> {
            if (lastWindow == null || window > lastWindow) {
                accepted[0] = true;
                return window;
            }
            accepted[0] = false;
            return lastWindow;
        });
        return accepted[0];
    }

    /** Test seam: clears all tracked windows. Production callers do not invalidate manually —
     *  Caffeine handles TTL eviction. */
    void clear() {
        lastConsumedWindow.invalidateAll();
    }

    /** Test seam: returns the last consumed window for a player, or {@code null}. */
    Long lastConsumed(UUID playerUuid) {
        return lastConsumedWindow.getIfPresent(playerUuid);
    }
}
