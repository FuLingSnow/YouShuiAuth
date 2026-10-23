package io.github.fulingsnow.youshuiauth.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuthDeadlinePolicyTest {

    private static final long NOW = 1_000_000L;
    private static final long BASE_60S = 60_000L;

    @Test
    void initialDeadlineUsesBaseWindow() {
        assertEquals(NOW + BASE_60S,
                AuthDeadlinePolicy.initialDeadline(NOW, NOW, BASE_60S));
    }

    @Test
    void initialDeadlineIsClampedToSessionWindow() {
        long sessionStart = NOW;
        long base = 3_600_000L;
        assertEquals(sessionStart + AuthDeadlinePolicy.MAX_SESSION_WINDOW_MILLIS,
                AuthDeadlinePolicy.initialDeadline(NOW, sessionStart, base));
    }

    @Test
    void scheduleNeverShortensExistingDeadline() {
        long existing = NOW + 300_000L;
        assertEquals(existing,
                AuthDeadlinePolicy.scheduleDeadline(NOW, NOW, existing, BASE_60S));
    }

    @Test
    void renewExtendsToCodeWindowPlusGrace() {
        long sessionStart = NOW;
        long deadline = AuthDeadlinePolicy.renewDeadline(
                NOW, sessionStart, NOW + BASE_60S, BASE_60S, 120);
        assertEquals(NOW + (120 + AuthDeadlinePolicy.RENEWAL_GRACE_SECONDS) * 1000L, deadline);
    }

    @Test
    void renewKeepsLongerBaseWindow() {
        long sessionStart = NOW;
        long deadline = AuthDeadlinePolicy.renewDeadline(
                NOW, sessionStart, 0L, 300_000L, 30);
        assertEquals(NOW + 300_000L, deadline);
    }

    @Test
    void renewIsClampedToSessionWindow() {
        long sessionStart = NOW;
        long deadline = AuthDeadlinePolicy.renewDeadline(
                NOW, sessionStart, 0L, 300_000L, 3_600);
        assertEquals(sessionStart + AuthDeadlinePolicy.MAX_SESSION_WINDOW_MILLIS, deadline);
    }

    @Test
    void zeroOrNegativeTtlOnlyAddsGrace() {
        long deadline = AuthDeadlinePolicy.renewDeadline(NOW, NOW, 0L, 0L, 0);
        assertEquals(NOW + AuthDeadlinePolicy.RENEWAL_GRACE_SECONDS * 1000L, deadline);
    }
}
