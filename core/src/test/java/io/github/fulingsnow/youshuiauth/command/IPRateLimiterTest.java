package io.github.fulingsnow.youshuiauth.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for IPRateLimiter.
 * Tests rate limiting behavior, thread safety, and cleanup logic.
 */
@SuppressWarnings("java:S100")
class IPRateLimiterTest {

    private IPRateLimiter rateLimiter;
    private InetAddress testAddress1;
    private InetAddress testAddress2;

    @BeforeEach
    void setUp() throws UnknownHostException {
        rateLimiter = new IPRateLimiter(5, 5);
        testAddress1 = InetAddress.getByName("192.168.1.1");
        testAddress2 = InetAddress.getByName("192.168.1.2");
    }

    @Test
    void testIsRateLimit_NewAddress_ReturnsFalse() {
        boolean result = rateLimiter.isRateLimited(testAddress1);

        assertFalse(result);
    }

    @Test
    void testIncrementAttempts_NewAddress_ReturnsOne() {
        rateLimiter.incrementAttempts(testAddress1);

        int attempts = rateLimiter.getAttempts(testAddress1);

        assertEquals(1, attempts);
    }

    @Test
    void testIncrementAttempts_MultipleCalls_ReturnsCorrectCount() {
        rateLimiter.incrementAttempts(testAddress1);
        rateLimiter.incrementAttempts(testAddress1);
        rateLimiter.incrementAttempts(testAddress1);

        int attempts = rateLimiter.getAttempts(testAddress1);

        assertEquals(3, attempts);
    }

    @Test
    void testIsRateLimit_AfterThreshold_ReturnsTrue() {

        for (int i = 0; i < 5; i++) {
            rateLimiter.incrementAttempts(testAddress1);
        }

        boolean result = rateLimiter.isRateLimited(testAddress1);

        assertTrue(result);
    }

    @Test
    void testIsRateLimit_BeforeThreshold_ReturnsFalse() {

        for (int i = 0; i < 4; i++) {
            rateLimiter.incrementAttempts(testAddress1);
        }

        boolean result = rateLimiter.isRateLimited(testAddress1);

        assertFalse(result);
    }

    @Test
    void testGetAttempts_NonExistentAddress_ReturnsZero() {
        int attempts = rateLimiter.getAttempts(testAddress1);

        assertEquals(0, attempts);
    }

    @Test
    void testMultipleAddresses_IndependentCounting() {
        rateLimiter.incrementAttempts(testAddress1);
        rateLimiter.incrementAttempts(testAddress1);
        rateLimiter.incrementAttempts(testAddress2);

        int attempts1 = rateLimiter.getAttempts(testAddress1);
        int attempts2 = rateLimiter.getAttempts(testAddress2);

        assertEquals(2, attempts1);
        assertEquals(1, attempts2);
    }

    @Test
    void testThreadSafety_ConcurrentIncrementAttempts() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        rateLimiter.incrementAttempts(testAddress1);
                    }
                } finally {
                    latch.countDown();
                }
            }));
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(threadCount, futures.size());
        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                fail("Future should complete without exceptions: " + e.getMessage());
            }
        }

        int finalAttempts = rateLimiter.getAttempts(testAddress1);
        assertEquals(threadCount * incrementsPerThread, finalAttempts);
    }

    @Test
    void testThreadSafety_ConcurrentIsRateLimited() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {

                    rateLimiter.incrementAttempts(testAddress1);
                    rateLimiter.isRateLimited(testAddress1);
                } finally {
                    latch.countDown();
                }
            }));
        }

        @SuppressWarnings("java:S2699")
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed);

        for (Future<?> future : futures) {
            assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS), "Future should complete within 5 seconds");
        }

        executor.shutdown();
    }

    @Test
    void testResetAttempts_ClearsCount() {

        rateLimiter.incrementAttempts(testAddress1);
        rateLimiter.incrementAttempts(testAddress1);
        assertEquals(2, rateLimiter.getAttempts(testAddress1));

        rateLimiter.reset(testAddress1);

        assertEquals(0, rateLimiter.getAttempts(testAddress1));
        assertFalse(rateLimiter.isRateLimited(testAddress1));
    }

    @Test
    void testResetAttempts_NonExistentAddress_NoException() {

        assertDoesNotThrow(() -> rateLimiter.reset(testAddress1));

        assertEquals(0, rateLimiter.getAttempts(testAddress1));
    }

    @Test
    void testGetSize_ReturnsCorrectInformation() {

        rateLimiter.incrementAttempts(testAddress1);
        rateLimiter.incrementAttempts(testAddress2);

        int size = rateLimiter.size();

        assertEquals(2, size);
    }

    @Test
    void testClearAll_RemovesAllEntries() {

        rateLimiter.incrementAttempts(testAddress1);
        rateLimiter.incrementAttempts(testAddress2);

        assertEquals(2, rateLimiter.size());

        rateLimiter.clearAll();

        assertEquals(0, rateLimiter.size());
        assertEquals(0, rateLimiter.getAttempts(testAddress1));
        assertEquals(0, rateLimiter.getAttempts(testAddress2));
    }
}
