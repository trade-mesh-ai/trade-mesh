package com.trademesh.zerodha.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks token availability per scheduler to suppress repeated error logs.
 * <p>
 * When a Zerodha access token is missing from Redis:
 * - First occurrence: logs a single WARN with context
 * - Subsequent occurrences: logs a simple one-line WARN (no stack trace)
 * - Token becomes available: automatically resets tracking
 * <p>
 * This prevents log spam during development/testing while ensuring operators
 * are alerted on first occurrence.
 */
@Slf4j
@Component
public class TokenAvailabilityTracker {

    /**
     * Tracks whether we've already logged the first warning for each scheduler.
     * Key: scheduler name, Value: true if first warning has been logged
     */
    private final ConcurrentHashMap<String, AtomicBoolean> firstWarningLogged = new ConcurrentHashMap<>();

    /**
     * Logs a warning when token is missing, suppressing stack traces on subsequent calls.
     *
     * @param schedulerName name of the scheduler (e.g., "TickerConnectionManager", "IntelligenceScheduler")
     */
    public void trackTokenMissing(String schedulerName) {
        AtomicBoolean logged = firstWarningLogged.computeIfAbsent(
                schedulerName,
                key -> new AtomicBoolean(false)
        );

        if (!logged.getAndSet(true)) {
            // First occurrence: log detailed warning
            log.warn("Zerodha not authenticated. Skipping [{}] until login.", schedulerName);
        } else {
            // Subsequent occurrences: log simple one-liner (no stack trace)
            log.warn("[{}] Zerodha token still missing.", schedulerName);
        }
    }

    /**
     * Resets tracking for a scheduler when token becomes available.
     * This allows the next missing token event to log the full warning again.
     *
     * @param schedulerName name of the scheduler
     */
    public void resetTracking(String schedulerName) {
        AtomicBoolean logged = firstWarningLogged.get(schedulerName);
        if (logged != null && logged.getAndSet(false)) {
            log.info("[{}] Zerodha token is now available. Resuming normal operations.", schedulerName);
        }
    }

    /**
     * Checks if a scheduler has already logged the first warning.
     *
     * @param schedulerName name of the scheduler
     * @return true if first warning has been logged
     */
    public boolean hasLoggedFirstWarning(String schedulerName) {
        AtomicBoolean logged = firstWarningLogged.get(schedulerName);
        return logged != null && logged.get();
    }
}
