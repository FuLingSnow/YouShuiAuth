package io.github.fulingsnow.youshuiauth.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossDeploymentClockTest {

    @Test
    void toleranceWidensUsabilityWindowOnlyBackwards() {
        long now = 1_000_000L;
        long expiresAt = now + 30_000;

        assertTrue(CrossDeploymentClock.isUsable(expiresAt, now), "未过期且在容差内应可用");
        assertEquals(now - CrossDeploymentClock.TOLERANCE_MS, CrossDeploymentClock.lowerBound(now),
                "下界只会向前放宽，不影响未来时间");
    }

    @Test
    void slightlyExpiredRecordsStayUsableWithinTolerance() {
        long now = 1_000_000L;
        long expiresAt = now - 60_000;

        assertTrue(CrossDeploymentClock.isUsable(expiresAt, now),
                "刚过期但仍在 2 分钟容差内的记录应保持可用（时钟偏差场景）");
    }

    @Test
    void farExpiredRecordsAreRejected() {
        long now = 1_000_000L;
        long expiresAt = now - CrossDeploymentClock.TOLERANCE_MS - 1;

        assertFalse(CrossDeploymentClock.isUsable(expiresAt, now), "超过容差的记录必须过期");
    }
}
