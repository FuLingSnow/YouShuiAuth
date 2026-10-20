package net.rafalohaki.veloauth.auth.totp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link TotpReplayGuard} — RFC 6238 §5.2 one-time-only enforcement.
 */
class TotpReplayGuardTest {

    private TotpReplayGuard guard;
    private UUID player;

    @BeforeEach
    void setUp() {
        guard = new TotpReplayGuard();
        player = UUID.randomUUID();
    }

    @Test
    void consume_firstWindow_succeeds() {
        assertTrue(guard.consume(player, 100L), "first consume on window 100 should succeed");
    }

    @Test
    void consume_sameWindowTwice_secondAttemptIsRejected() {
        assertTrue(guard.consume(player, 100L));
        assertFalse(guard.consume(player, 100L),
                "replaying the same window must be rejected");
    }

    @Test
    void consume_olderWindowAfterNewer_isRejected() {
        // If the player just used window 100, a code that matches window 99 (drift band)
        // should be rejected as a replay.
        assertTrue(guard.consume(player, 100L));
        assertFalse(guard.consume(player, 99L),
                "older window after a newer one is a replay in the tolerance band");
    }

    @Test
    void consume_strictlyNewerWindow_succeeds() {
        assertTrue(guard.consume(player, 100L));
        assertTrue(guard.consume(player, 101L),
                "rolling forward to the next window with a fresh code is allowed");
    }

    @Test
    void consume_differentPlayer_independent() {
        UUID other = UUID.randomUUID();
        assertTrue(guard.consume(player, 100L));
        assertTrue(guard.consume(other, 100L),
                "each player's replay history is independent");
    }

    @Test
    void consume_nullPlayer_returnsFalse() {
        assertFalse(guard.consume(null, 100L));
    }

    @Test
    void consume_concurrentSameWindow_onlyOneWinner() throws InterruptedException {
        // Twenty threads racing on the same (player, window) — at most one consume() must succeed.
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (guard.consume(player, 500L)) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "all threads should finish quickly");
        assertEquals(1, winners.get(),
                "exactly one thread out of " + threads + " must win the race");
    }

    @Test
    void clear_resetsAllPlayers() {
        guard.consume(player, 100L);
        guard.clear();
        assertTrue(guard.consume(player, 100L),
                "after clear() the same window can be consumed again");
    }
}
