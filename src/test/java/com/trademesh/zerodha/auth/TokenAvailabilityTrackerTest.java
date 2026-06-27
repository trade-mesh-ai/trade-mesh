package com.trademesh.zerodha.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TokenAvailabilityTracker.
 * <p>
 * Verifies that:
 * - First token missing event logs a detailed warning
 * - Subsequent token missing events log simple one-liners
 * - Token availability resets tracking
 * - Multiple schedulers can be tracked independently
 * - Race condition prevention: token state prevents conflicting logs
 */
@DisplayName("TokenAvailabilityTracker Tests")
class TokenAvailabilityTrackerTest {

    private TokenAvailabilityTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TokenAvailabilityTracker();
    }

    @Test
    @DisplayName("First token missing should mark as logged")
    void testFirstTokenMissingMarksAsLogged() {
        String schedulerName = "TestScheduler";

        assertFalse(tracker.hasLoggedFirstWarning(schedulerName),
                "Should not have logged first warning initially");

        tracker.trackTokenMissing(schedulerName);

        assertTrue(tracker.hasLoggedFirstWarning(schedulerName),
                "Should have logged first warning after first call");
    }

    @Test
    @DisplayName("Multiple calls to trackTokenMissing should maintain logged state")
    void testMultipleCallsMaintainLoggedState() {
        String schedulerName = "TestScheduler";

        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));

        // Call again - should still be marked as logged
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName),
                "Should remain logged after multiple calls");
    }

    @Test
    @DisplayName("Reset tracking should clear logged state")
    void testResetTrackingClearsLoggedState() {
        String schedulerName = "TestScheduler";

        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));

        tracker.resetTracking(schedulerName);
        assertFalse(tracker.hasLoggedFirstWarning(schedulerName),
                "Should clear logged state after reset");
    }

    @Test
    @DisplayName("Multiple schedulers should be tracked independently")
    void testMultipleSchedulersTrackedIndependently() {
        String scheduler1 = "Scheduler1";
        String scheduler2 = "Scheduler2";

        tracker.trackTokenMissing(scheduler1);
        assertTrue(tracker.hasLoggedFirstWarning(scheduler1));
        assertFalse(tracker.hasLoggedFirstWarning(scheduler2),
                "Scheduler2 should not be affected by Scheduler1 tracking");

        tracker.trackTokenMissing(scheduler2);
        assertTrue(tracker.hasLoggedFirstWarning(scheduler1));
        assertTrue(tracker.hasLoggedFirstWarning(scheduler2));

        tracker.resetTracking(scheduler1);
        assertFalse(tracker.hasLoggedFirstWarning(scheduler1),
                "Scheduler1 should be reset");
        assertTrue(tracker.hasLoggedFirstWarning(scheduler2),
                "Scheduler2 should remain logged");
    }

    @Test
    @DisplayName("Reset on non-existent scheduler should not throw error")
    void testResetOnNonExistentSchedulerIsNoop() {
        String schedulerName = "NonExistentScheduler";

        // Should not throw any exception
        assertDoesNotThrow(() -> tracker.resetTracking(schedulerName));
        assertFalse(tracker.hasLoggedFirstWarning(schedulerName));
    }

    @Test
    @DisplayName("Tracking cycle: missing -> reset -> missing again")
    void testTrackingCycle() {
        String schedulerName = "TestScheduler";

        // First missing
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));

        // Reset (token becomes available)
        tracker.resetTracking(schedulerName);
        assertFalse(tracker.hasLoggedFirstWarning(schedulerName));

        // Token missing again - should log first warning again
        tracker.setTokenUnavailable();
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));
    }

    @Test
    @DisplayName("Race condition prevention: token available state prevents missing logs")
    void testRaceConditionPrevention() {
        String scheduler1 = "Scheduler1";
        String scheduler2 = "Scheduler2";

        // Scheduler1 detects missing token
        tracker.setTokenUnavailable();
        tracker.trackTokenMissing(scheduler1);
        assertTrue(tracker.hasLoggedFirstWarning(scheduler1));
        assertFalse(tracker.isTokenAvailable());

        // Scheduler1 gets token and resets tracking
        tracker.resetTracking(scheduler1);
        assertTrue(tracker.isTokenAvailable());

        // Scheduler2 tries to log missing token (race condition scenario)
        // Should NOT log because token is marked as available
        tracker.trackTokenMissing(scheduler2);
        assertFalse(tracker.hasLoggedFirstWarning(scheduler2),
                "Should not log missing token when token is marked as available (race condition prevention)");
    }

    @Test
    @DisplayName("setTokenUnavailable resets global token state")
    void testSetTokenUnavailable() {
        String schedulerName = "TestScheduler";

        // Token becomes available
        tracker.resetTracking(schedulerName);
        assertTrue(tracker.isTokenAvailable());

        // Token becomes unavailable
        tracker.setTokenUnavailable();
        assertFalse(tracker.isTokenAvailable());

        // Now tracking should work again
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));
    }

    @Test
    @DisplayName("isTokenAvailable reflects current token state")
    void testIsTokenAvailable() {
        // Initially token is unavailable
        assertFalse(tracker.isTokenAvailable());

        // After reset, token is available
        tracker.resetTracking("TestScheduler");
        assertTrue(tracker.isTokenAvailable());

        // After setTokenUnavailable, token is unavailable
        tracker.setTokenUnavailable();
        assertFalse(tracker.isTokenAvailable());
    }
}
