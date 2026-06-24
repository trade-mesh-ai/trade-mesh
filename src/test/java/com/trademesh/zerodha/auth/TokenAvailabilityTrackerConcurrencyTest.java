package com.trademesh.zerodha.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Concurrency tests for TokenAvailabilityTracker.
 * <p>
 * Verifies that the deduplication mechanism prevents log bursts when multiple
 * concurrent calls from the same scheduler fire simultaneously (e.g., 4 option
 * chain calls at startup).
 */
@DisplayName("TokenAvailabilityTracker Concurrency Tests")
@ExtendWith(MockitoExtension.class)
class TokenAvailabilityTrackerConcurrencyTest {

    private TokenAvailabilityTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TokenAvailabilityTracker();
    }

    @Test
    @DisplayName("4 concurrent calls from same scheduler should log only once")
    void testConcurrentCallsFromSameSchedulerLogOnce() throws InterruptedException {
        String schedulerName = "OptionChainService";
        int concurrentCalls = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentCalls);
        AtomicInteger logCount = new AtomicInteger(0);

        // Mark token as unavailable to trigger logging
        tracker.setTokenUnavailable();

        // Create 4 threads that will call trackTokenMissing simultaneously
        for (int i = 0; i < concurrentCalls; i++) {
            new Thread(() -> {
                try {
                    // Wait for all threads to be ready
                    startLatch.await();
                    // Call trackTokenMissing
                    if (tracker.trackTokenMissing(schedulerName)) {
                        logCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        // Release all threads at the same time
        startLatch.countDown();
        // Wait for all threads to complete
        endLatch.await();

        // Only one thread should have successfully logged
        assertEquals(1, logCount.get(),
                "Only one of the 4 concurrent calls should have logged the first warning");
        // The tracker should show that the first warning was logged
        assertEquals(true, tracker.hasLoggedFirstWarning(schedulerName),
                "Tracker should show first warning was logged");
    }

    @Test
    @DisplayName("Concurrent calls from different schedulers should each log once")
    void testConcurrentCallsFromDifferentSchedulersLogIndependently() throws InterruptedException {
        String scheduler1 = "OptionChainService";
        String scheduler2 = "MaxPainScheduler";
        int callsPerScheduler = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(callsPerScheduler * 2);
        AtomicInteger scheduler1LogCount = new AtomicInteger(0);
        AtomicInteger scheduler2LogCount = new AtomicInteger(0);

        // Mark token as unavailable to trigger logging
        tracker.setTokenUnavailable();

        // Create 2 threads for scheduler1
        for (int i = 0; i < callsPerScheduler; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    if (tracker.trackTokenMissing(scheduler1)) {
                        scheduler1LogCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        // Create 2 threads for scheduler2
        for (int i = 0; i < callsPerScheduler; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    if (tracker.trackTokenMissing(scheduler2)) {
                        scheduler2LogCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        // Release all threads at the same time
        startLatch.countDown();
        // Wait for all threads to complete
        endLatch.await();

        // Each scheduler should have logged exactly once
        assertEquals(1, scheduler1LogCount.get(),
                "Scheduler1 should log exactly once despite 2 concurrent calls");
        assertEquals(1, scheduler2LogCount.get(),
                "Scheduler2 should log exactly once despite 2 concurrent calls");
        assertEquals(true, tracker.hasLoggedFirstWarning(scheduler1),
                "Tracker should show scheduler1 first warning was logged");
        assertEquals(true, tracker.hasLoggedFirstWarning(scheduler2),
                "Tracker should show scheduler2 first warning was logged");
    }

    @Test
    @DisplayName("After reset, concurrent calls should be able to log again")
    void testConcurrentCallsCanLogAgainAfterReset() throws InterruptedException {
        String schedulerName = "OptionChainService";
        int concurrentCalls = 3;

        // First round: mark unavailable and log
        tracker.setTokenUnavailable();
        CountDownLatch startLatch1 = new CountDownLatch(1);
        CountDownLatch endLatch1 = new CountDownLatch(concurrentCalls);

        for (int i = 0; i < concurrentCalls; i++) {
            new Thread(() -> {
                try {
                    startLatch1.await();
                    tracker.trackTokenMissing(schedulerName);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch1.countDown();
                }
            }).start();
        }

        startLatch1.countDown();
        endLatch1.await();

        // Verify first warning was logged
        assertEquals(true, tracker.hasLoggedFirstWarning(schedulerName),
                "First warning should be logged after first round");

        // Reset tracking (token becomes available)
        tracker.resetTracking(schedulerName);
        assertEquals(false, tracker.hasLoggedFirstWarning(schedulerName),
                "First warning flag should be cleared after reset");

        // Second round: mark unavailable again and log
        tracker.setTokenUnavailable();
        CountDownLatch startLatch2 = new CountDownLatch(1);
        CountDownLatch endLatch2 = new CountDownLatch(concurrentCalls);

        for (int i = 0; i < concurrentCalls; i++) {
            new Thread(() -> {
                try {
                    startLatch2.await();
                    tracker.trackTokenMissing(schedulerName);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch2.countDown();
                }
            }).start();
        }

        startLatch2.countDown();
        endLatch2.await();

        // Verify first warning was logged again
        assertEquals(true, tracker.hasLoggedFirstWarning(schedulerName),
                "First warning should be logged again after reset and second round");
    }
}
