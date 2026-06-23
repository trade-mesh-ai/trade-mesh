package com.trademesh.zerodha.ticker;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TokenAvailabilityTracker with TickerConnectionManager.
 * <p>
 * Verifies that the race condition fix prevents duplicate token tracking logs
 * during startup when the token becomes available immediately after a connection attempt.
 */
@DisplayName("TokenAvailabilityTracker Integration Tests")
@ExtendWith(MockitoExtension.class)
class TokenAvailabilityTrackerIntegrationTest {

    private TokenAvailabilityTracker tracker;

    @Mock
    private ZerodhaTickerService tickerService;

    @BeforeEach
    void setUp() {
        tracker = new TokenAvailabilityTracker();
    }

    @Test
    @DisplayName("Startup race condition: token missing then immediately available should log once")
    void testStartupRaceConditionNoDoubleLogs() {
        String schedulerName = "TickerConnectionManager";

        // Simulate startup sequence:
        // 1. Connection attempt fails due to missing token
        tracker.setTokenUnavailable();
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName),
                "Should log first warning on token missing");

        // 2. Token becomes available (from another source)
        tracker.resetTracking(schedulerName);
        assertTrue(tracker.isTokenAvailable(),
                "Token should be marked as available after reset");

        // 3. Another component tries to log missing token (race condition scenario)
        // Should NOT log because token is marked as available
        tracker.trackTokenMissing(schedulerName);
        assertFalse(tracker.hasLoggedFirstWarning(schedulerName),
                "Should NOT log missing token when token is marked as available");
    }

    @Test
    @DisplayName("Multiple concurrent schedulers should not interfere with each other")
    void testConcurrentSchedulersIndependent() throws InterruptedException {
        String scheduler1 = "TickerConnectionManager";
        String scheduler2 = "IntelligenceScheduler";

        AtomicInteger scheduler1Warnings = new AtomicInteger(0);
        AtomicInteger scheduler2Warnings = new AtomicInteger(0);

        // Simulate concurrent token missing events
        Thread t1 = new Thread(() -> {
            tracker.setTokenUnavailable();
            tracker.trackTokenMissing(scheduler1);
            if (tracker.hasLoggedFirstWarning(scheduler1)) {
                scheduler1Warnings.incrementAndGet();
            }
        });

        Thread t2 = new Thread(() -> {
            tracker.setTokenUnavailable();
            tracker.trackTokenMissing(scheduler2);
            if (tracker.hasLoggedFirstWarning(scheduler2)) {
                scheduler2Warnings.incrementAndGet();
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Both should have logged exactly once
        assertEquals(1, scheduler1Warnings.get(), "Scheduler1 should log exactly once");
        assertEquals(1, scheduler2Warnings.get(), "Scheduler2 should log exactly once");
        assertTrue(tracker.hasLoggedFirstWarning(scheduler1));
        assertTrue(tracker.hasLoggedFirstWarning(scheduler2));
    }

    @Test
    @DisplayName("Token state transitions: unavailable -> available -> unavailable -> available")
    void testTokenStateTransitions() {
        String schedulerName = "TickerConnectionManager";

        // Initial state: token unavailable
        assertFalse(tracker.isTokenAvailable());

        // First missing event
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));

        // Token becomes available
        tracker.resetTracking(schedulerName);
        assertTrue(tracker.isTokenAvailable());
        assertFalse(tracker.hasLoggedFirstWarning(schedulerName));

        // Token becomes unavailable again
        tracker.setTokenUnavailable();
        assertFalse(tracker.isTokenAvailable());

        // Should be able to log first warning again
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));

        // Token becomes available again
        tracker.resetTracking(schedulerName);
        assertTrue(tracker.isTokenAvailable());
        assertFalse(tracker.hasLoggedFirstWarning(schedulerName));
    }

    @Test
    @DisplayName("Rapid token state changes should not cause duplicate logs")
    void testRapidTokenStateChanges() {
        String schedulerName = "TickerConnectionManager";

        // Simulate rapid state changes
        for (int i = 0; i < 5; i++) {
            tracker.setTokenUnavailable();
            tracker.trackTokenMissing(schedulerName);
            assertTrue(tracker.hasLoggedFirstWarning(schedulerName),
                    "Should log on iteration " + i);

            tracker.resetTracking(schedulerName);
            assertTrue(tracker.isTokenAvailable(),
                    "Token should be available on iteration " + i);
            assertFalse(tracker.hasLoggedFirstWarning(schedulerName),
                    "Should clear logged state on iteration " + i);
        }
    }

    @Test
    @DisplayName("Token available state prevents logging from multiple schedulers simultaneously")
    void testTokenAvailableStateBlocksMultipleSchedulers() {
        String scheduler1 = "TickerConnectionManager";
        String scheduler2 = "IntelligenceScheduler";
        String scheduler3 = "VixMonitorService";

        // Token becomes available
        tracker.resetTracking(scheduler1);
        assertTrue(tracker.isTokenAvailable());

        // Multiple schedulers try to log missing token
        tracker.trackTokenMissing(scheduler1);
        tracker.trackTokenMissing(scheduler2);
        tracker.trackTokenMissing(scheduler3);

        // None should have logged because token is marked as available
        assertFalse(tracker.hasLoggedFirstWarning(scheduler1),
                "Scheduler1 should not log when token is available");
        assertFalse(tracker.hasLoggedFirstWarning(scheduler2),
                "Scheduler2 should not log when token is available");
        assertFalse(tracker.hasLoggedFirstWarning(scheduler3),
                "Scheduler3 should not log when token is available");
    }

    @Test
    @DisplayName("After token becomes unavailable, all schedulers can log again")
    void testAllSchedulersCanLogAfterTokenUnavailable() {
        String scheduler1 = "TickerConnectionManager";
        String scheduler2 = "IntelligenceScheduler";

        // Token becomes available
        tracker.resetTracking(scheduler1);
        assertTrue(tracker.isTokenAvailable());

        // Try to log (should be blocked)
        tracker.trackTokenMissing(scheduler1);
        tracker.trackTokenMissing(scheduler2);
        assertFalse(tracker.hasLoggedFirstWarning(scheduler1));
        assertFalse(tracker.hasLoggedFirstWarning(scheduler2));

        // Token becomes unavailable
        tracker.setTokenUnavailable();
        assertFalse(tracker.isTokenAvailable());

        // Now both should be able to log
        tracker.trackTokenMissing(scheduler1);
        tracker.trackTokenMissing(scheduler2);
        assertTrue(tracker.hasLoggedFirstWarning(scheduler1),
                "Scheduler1 should log after token becomes unavailable");
        assertTrue(tracker.hasLoggedFirstWarning(scheduler2),
                "Scheduler2 should log after token becomes unavailable");
    }
}
