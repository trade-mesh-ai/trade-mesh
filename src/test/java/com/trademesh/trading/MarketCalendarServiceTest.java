package com.trademesh.trading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for MarketCalendarService.isTradingDay() method.
 * Tests trading day detection for weekends, holidays, and years without NSE_HOLIDAYS entries.
 */
@DisplayName("MarketCalendarService Tests")
class MarketCalendarServiceTest {

    private final MarketCalendarService marketCalendarService = new MarketCalendarService();

    /**
     * Test: isTradingDay() returns false for a date in a year with no NSE_HOLIDAYS entry.
     * <p>
     * Verifies that when a year is not configured in NSE_HOLIDAYS map (e.g., 2027),
     * isTradingDay() returns false (fails safe) instead of throwing an exception.
     * This ensures the service gracefully handles unconfigured years.
     */
    @Test
    @DisplayName("isTradingDay: Returns false for year with no NSE_HOLIDAYS entry (2027)")
    void testIsTradingDayReturnsFalseForUnconfiguredYear() {
        // Arrange: Use a date in 2027 (a year not in NSE_HOLIDAYS map)
        LocalDate dateIn2027 = LocalDate.of(2027, 6, 15); // Tuesday in 2027

        // Act & Assert: isTradingDay() should return false (fail-safe behavior)
        assertFalse(marketCalendarService.isTradingDay(dateIn2027),
                "isTradingDay() should return false for a date in a year with no NSE_HOLIDAYS entry");
    }
}
