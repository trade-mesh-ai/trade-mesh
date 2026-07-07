package com.trademesh.execution;

import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.trading.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PaperTradingEngine.isDteExitDue() method.
 * Tests DTE boundary conditions and time-gate logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine DTE Exit Tests")
class PaperTradingEngineTest {

    @Mock
    private MarketCalendarService marketCalendarService;

    @InjectMocks
    private PaperTradingEngine paperTradingEngine;

    private PaperTrade trade;
    private StrategyProperties strategyConfig;
    private LocalDate today;
    private LocalDate expiryDate;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        expiryDate = today.plusDays(3); // 3 days from today

        // Create a trade with one leg
        trade = PaperTrade.createNew();
        trade.setId(UUID.randomUUID());
        trade.setStrategyName("BULL_PUT_SPREAD");

        TradeLeg leg = new TradeLeg();
        leg.setExpiryDate(expiryDate);
        trade.setLegs(List.of(leg));

        // Create strategy config with DTE settings
        strategyConfig = new StrategyProperties();
        strategyConfig.setName("BULL_PUT_SPREAD");
        strategyConfig.setEnabled(true);

        Map<String, Object> extra = new HashMap<>();
        extra.put("dte-min", 2);
        extra.put("dte-exit-threshold", 1);
        extra.put("dte-exit-earliest-time", "10:00");
        strategyConfig.setExtra(extra);
    }

    // ─── Test Case 1: tradingDaysRemaining > threshold (not yet at threshold) ───
    @Test
    @DisplayName("Should NOT exit when tradingDaysRemaining > threshold")
    void testDteExitNotDueWhenAboveThreshold() {
        // Setup: 3 trading days remaining, threshold is 1
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(3);

        LocalTime currentTime = LocalTime.of(14, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertFalse(result, "Should not exit when above threshold");
    }

    // ─── Test Case 2: tradingDaysRemaining < threshold (past threshold day) ───
    @Test
    @DisplayName("Should exit immediately when tradingDaysRemaining < threshold (past threshold day)")
    void testDteExitDueWhenBelowThreshold() {
        // Setup: 0 trading days remaining (expiry day), threshold is 1
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(0);

        LocalTime currentTime = LocalTime.of(9, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertTrue(result, "Should exit immediately when past threshold day");
    }

    // ─── Test Case 3: Exact boundary - currentTime == dte-exit-earliest-time ───
    @Test
    @DisplayName("Should exit when currentTime == dte-exit-earliest-time (exact boundary)")
    void testDteExitDueAtExactTimeGate() {
        // Setup: 1 trading day remaining (threshold day), current time is exactly 10:00
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(1);

        LocalTime currentTime = LocalTime.of(10, 0);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertTrue(result, "Should exit when currentTime equals dte-exit-earliest-time");
    }

    // ─── Test Case 4: One minute before time gate ───
    @Test
    @DisplayName("Should NOT exit when currentTime is 1 minute before dte-exit-earliest-time")
    void testDteExitBlockedBeforeTimeGate() {
        // Setup: 1 trading day remaining (threshold day), current time is 09:59
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(1);

        LocalTime currentTime = LocalTime.of(9, 59);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertFalse(result, "Should NOT exit when currentTime is before dte-exit-earliest-time");
    }

    // ─── Test Case 5: One minute after time gate ───
    @Test
    @DisplayName("Should exit when currentTime is 1 minute after dte-exit-earliest-time")
    void testDteExitAllowedAfterTimeGate() {
        // Setup: 1 trading day remaining (threshold day), current time is 10:01
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(1);

        LocalTime currentTime = LocalTime.of(10, 1);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertTrue(result, "Should exit when currentTime is after dte-exit-earliest-time");
    }

    // ─── Test Case 6: Guard - no legs ───
    @Test
    @DisplayName("Should return false when trade has no legs")
    void testDteExitWithNoLegs() {
        trade.setLegs(null);

        LocalTime currentTime = LocalTime.of(14, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertFalse(result, "Should return false when no legs found");
    }

    // ─── Test Case 7: Guard - no expiry date ───
    @Test
    @DisplayName("Should return false when leg has no expiry date")
    void testDteExitWithNoExpiryDate() {
        TradeLeg leg = new TradeLeg();
        leg.setExpiryDate(null);
        trade.setLegs(List.of(leg));

        LocalTime currentTime = LocalTime.of(14, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertFalse(result, "Should return false when expiry date is null");
    }

    // ─── Test Case 8: Invalid time format fallback ───
    @Test
    @DisplayName("Should allow exit as fallback when dte-exit-earliest-time has invalid format")
    void testDteExitWithInvalidTimeFormat() {
        // Setup: 1 trading day remaining (threshold day), invalid time format
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(1);

        Map<String, Object> extra = new HashMap<>();
        extra.put("dte-min", 2);
        extra.put("dte-exit-threshold", 1);
        extra.put("dte-exit-earliest-time", "invalid-time");
        strategyConfig.setExtra(extra);

        LocalTime currentTime = LocalTime.of(14, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        // Should allow exit as fallback when time parsing fails
        assertTrue(result, "Should allow exit as fallback when time format is invalid");
    }

    // ─── Test Case 9: Missing dte-exit-earliest-time uses default ───
    @Test
    @DisplayName("Should use default dte-exit-earliest-time when not configured")
    void testDteExitWithDefaultTimeGate() {
        // Setup: 1 trading day remaining (threshold day), no dte-exit-earliest-time configured
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(1);

        Map<String, Object> extra = new HashMap<>();
        extra.put("dte-min", 2);
        extra.put("dte-exit-threshold", 1);
        // dte-exit-earliest-time not set
        strategyConfig.setExtra(extra);

        LocalTime currentTime = LocalTime.of(10, 0);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        // Should use default time gate of 10:00 and exit at exactly 10:00
        assertTrue(result, "Should use default time gate of 10:00 and exit at that time");
    }

    // ─── Test Case 10: Multiple trading days remaining ───
    @Test
    @DisplayName("Should not exit when multiple trading days remain")
    void testDteExitWithMultipleDaysRemaining() {
        // Setup: 5 trading days remaining, threshold is 1
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(5);

        LocalTime currentTime = LocalTime.of(14, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertFalse(result, "Should not exit when multiple days remain");
    }

    // ─── Test Case 11: Exactly at threshold with early morning time ───
    @Test
    @DisplayName("Should not exit at threshold day before time gate (early morning)")
    void testDteExitAtThresholdEarlyMorning() {
        // Setup: 1 trading day remaining (threshold day), current time is 09:30 (early morning)
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(1);

        LocalTime currentTime = LocalTime.of(9, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertFalse(result, "Should NOT exit at threshold day before time gate");
    }

    // ─── Test Case 12: Exactly at threshold with late morning time ───
    @Test
    @DisplayName("Should exit at threshold day after time gate (late morning)")
    void testDteExitAtThresholdLateMorning() {
        // Setup: 1 trading day remaining (threshold day), current time is 11:30 (late morning)
        when(marketCalendarService.countTradingDays(today, expiryDate)).thenReturn(1);

        LocalTime currentTime = LocalTime.of(11, 30);
        boolean result = paperTradingEngine.isDteExitDue(trade, strategyConfig, 1, currentTime);

        assertTrue(result, "Should exit at threshold day after time gate");
    }
}
