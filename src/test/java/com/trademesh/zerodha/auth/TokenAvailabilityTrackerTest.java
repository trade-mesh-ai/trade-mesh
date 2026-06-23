package com.trademesh.zerodha.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TokenAvailabilityTracker.
 *
 * Verifies that:
 * - First token missing event logs a detailed warning
 * - Subsequent token missing events log simple one-liners
 * - Token availability resets tracking
 * - Multiple schedulers can be tracked independently
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
        tracker.trackTokenMissing(schedulerName);
        assertTrue(tracker.hasLoggedFirstWarning(schedulerName));
    }
}
