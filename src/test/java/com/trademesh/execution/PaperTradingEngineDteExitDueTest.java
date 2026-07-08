package com.trademesh.execution;

import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.trading.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for isDteExitDue() method in PaperTradingEngine.
 * <p>
 * Tests verify that isDteExitDue() correctly:
 * - Triggers exit when trading days remaining <= threshold
 * - Skips exit when trading days remaining > threshold
 * - Handles null/empty legs gracefully without throwing exceptions
 * - Handles null expiry date gracefully without throwing exceptions
 * - Falls back to defaultThreshold when config key is missing
 * <p>
 * These are isolated unit tests using Mockito to mock StrategyProperties and
 * MarketCalendarService. No Spring context, Redis, or database dependencies are required.
 * Pattern matches PaperTradingEngineDteExitTest.java and PaperTradingEngineVixPanicTest.java.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine isDteExitDue() Tests")
class PaperTradingEngineDteExitDueTest {

    private PaperTradingEngine engine;

    @Mock
    private StrategyProperties ironCondorConfig;

    @Mock
    private StrategyProperties spreadConfig;

    @Mock
    private MarketCalendarService marketCalendarService;

    @BeforeEach
    void setUp() {
        // Create a minimal PaperTradingEngine instance with mocked MarketCalendarService
        engine = new PaperTradingEngine(null, null, null, null, null, null, null, marketCalendarService);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IRON CONDOR TESTS (default threshold = 2)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Iron Condor - Above threshold (3 days > 2) → false.
     * <p>
     * Verifies that when tradingDaysRemaining (3) > threshold (2),
     * isDteExitDue() returns false.
     */
    @Test
    @DisplayName("Iron Condor: Above threshold (3 days > 2) → false")
    void ironCondor_aboveThreshold_returnsFalse() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(5); // 3 trading days away
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(3);

        PaperTrade trade = createIronCondorTrade(expiryDate);

        // Act
        boolean result = engine.isDteExitDue(trade, ironCondorConfig, 2, LocalTime.of(10, 1));

        // Assert
        assertFalse(result, "isDteExitDue should return false when DTE > threshold");
    }

    /**
     * Test 2: Iron Condor - Below threshold (1 day < 2) → true.
     * <p>
     * Verifies that when tradingDaysRemaining (1) < threshold (2),
     * isDteExitDue() returns true.
     */
    @Test
    @DisplayName("Iron Condor: Below threshold (1 day < 2) → true")
    void ironCondor_belowThreshold_returnsTrue() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2); // 1 trading day away
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(1);

        PaperTrade trade = createIronCondorTrade(expiryDate);

        // Act
        boolean result = engine.isDteExitDue(trade, ironCondorConfig, 2, LocalTime.of(10, 1));

        // Assert
        assertTrue(result, "isDteExitDue should return true when DTE < threshold");
    }

    /**
     * Test 3: Iron Condor - Equal to threshold (2 days == 2) → true.
     * <p>
     * Verifies that when tradingDaysRemaining (2) == threshold (2),
     * isDteExitDue() returns true (boundary condition: <= threshold).
     */
    @Test
    @DisplayName("Iron Condor: Equal to threshold (2 days == 2) → true")
    void ironCondor_equalToThreshold_returnsTrue() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(4); // 2 trading days away
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(2);

        PaperTrade trade = createIronCondorTrade(expiryDate);

        // Act
        boolean result = engine.isDteExitDue(trade, ironCondorConfig, 2, LocalTime.of(10, 1));

        // Assert
        assertTrue(result, "isDteExitDue should return true when DTE == threshold");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SPREAD TESTS (default threshold = 1)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 4: Spread - Above threshold (2 days > 1) → false.
     * <p>
     * Verifies that when tradingDaysRemaining (2) > threshold (1),
     * isDteExitDue() returns false.
     */
    @Test
    @DisplayName("Spread: Above threshold (2 days > 1) → false")
    void spread_aboveThreshold_returnsFalse() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(4); // 2 trading days away
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(2);

        PaperTrade trade = createSpreadTrade(expiryDate);

        // Act
        boolean result = engine.isDteExitDue(trade, spreadConfig, 1, LocalTime.of(10, 1));

        // Assert
        assertFalse(result, "isDteExitDue should return false when DTE > threshold");
    }

    /**
     * Test 5: Spread - Below threshold (0 days < 1) → true.
     * <p>
     * Verifies that when tradingDaysRemaining (0) < threshold (1),
     * isDteExitDue() returns true.
     */
    @Test
    @DisplayName("Spread: Below threshold (0 days < 1) → true")
    void spread_belowThreshold_returnsTrue() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")); // 0 trading days away (expiry is today)
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(0);

        PaperTrade trade = createSpreadTrade(expiryDate);

        // Act
        boolean result = engine.isDteExitDue(trade, spreadConfig, 1, LocalTime.of(10, 1));

        // Assert
        assertTrue(result, "isDteExitDue should return true when DTE < threshold");
    }

    /**
     * Test 6: Spread - Equal to threshold (1 day == 1) → true.
     * <p>
     * Verifies that when tradingDaysRemaining (1) == threshold (1),
     * isDteExitDue() returns true (boundary condition: <= threshold).
     */
    @Test
    @DisplayName("Spread: Equal to threshold (1 day == 1) → true")
    void spread_equalToThreshold_returnsTrue() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2); // 1 trading day away
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(1);

        PaperTrade trade = createSpreadTrade(expiryDate);

        // Act
        boolean result = engine.isDteExitDue(trade, spreadConfig, 1, LocalTime.of(10, 1));

        // Assert
        assertTrue(result, "isDteExitDue should return true when DTE == threshold");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONFIG FALLBACK TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 7a: Config key missing → falls back to defaultThreshold = 2.
     * <p>
     * Verifies that when config key "dte-exit-threshold" is missing,
     * the method correctly falls back to the passed defaultThreshold (2).
     * For the same DTE value (2 days), with threshold 2 it should return true,
     * but with threshold 1 it would also return true. So we test with 3 days:
     * - With threshold 2: 3 > 2 → false
     * - With threshold 1: 3 > 1 → false
     * <p>
     * Better test: 2 days remaining
     * - With threshold 2: 2 <= 2 → true
     * - With threshold 1: 2 > 1 → false
     */
    @Test
    @DisplayName("Config key missing → falls back to defaultThreshold = 2, produces true for 2 days")
    void configKeyMissing_fallbackToDefault2_returnsTrueFor2Days() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(4); // 2 trading days away
        // Config returns the default (2) when key is missing
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(2);

        PaperTrade trade = createIronCondorTrade(expiryDate);

        // Act: Pass defaultThreshold = 2
        boolean result = engine.isDteExitDue(trade, ironCondorConfig, 2, LocalTime.of(10, 1));

        // Assert: 2 days <= 2 threshold → true
        assertTrue(result, "isDteExitDue should return true when DTE (2) == threshold (2)");
    }

    /**
     * Test 7b: Config key missing → falls back to defaultThreshold = 1.
     * <p>
     * Verifies that when config key "dte-exit-threshold" is missing,
     * the method correctly falls back to the passed defaultThreshold (1).
     * For 2 days remaining:
     * - With threshold 1: 2 > 1 → false
     */
    @Test
    @DisplayName("Config key missing → falls back to defaultThreshold = 1, produces false for 2 days")
    void configKeyMissing_fallbackToDefault1_returnsFalseFor2Days() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(4); // 2 trading days away
        // Config returns the default (1) when key is missing
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(2);

        PaperTrade trade = createSpreadTrade(expiryDate);

        // Act: Pass defaultThreshold = 1
        boolean result = engine.isDteExitDue(trade, spreadConfig, 1, LocalTime.of(10, 1));

        // Assert: 2 days > 1 threshold → false
        assertFalse(result, "isDteExitDue should return false when DTE (2) > threshold (1)");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EDGE CASE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 8: Null legs → false, no exception.
     * <p>
     * Verifies that when trade.getLegs() is null,
     * the method logs a warning and returns false without throwing NPE.
     */
    @Test
    @DisplayName("Null legs → false, no exception")
    void nullLegs_returnsFalse_noException() {
        // Arrange
        PaperTrade trade = createIronCondorTrade(null);
        trade.setLegs(null); // Explicitly set legs to null

        // Act & Assert: No exception should be thrown
        assertDoesNotThrow(() -> {
            boolean result = engine.isDteExitDue(trade, ironCondorConfig, 2, LocalTime.of(10, 1));
            assertFalse(result, "isDteExitDue should return false when legs are null");
        }, "isDteExitDue should not throw exception for null legs");
    }

    /**
     * Test 9: Null expiry date → false, no exception.
     * <p>
     * Verifies that when legs exist but expiryDate is null,
     * the method logs a warning and returns false without throwing NPE.
     */
    @Test
    @DisplayName("Null expiry date → false, no exception")
    void nullExpiryDate_returnsFalse_noException() {
        // Arrange
        PaperTrade trade = createIronCondorTrade(null); // expiryDate is null
        // Legs are created with null expiryDate

        // Act & Assert: No exception should be thrown
        assertDoesNotThrow(() -> {
            boolean result = engine.isDteExitDue(trade, ironCondorConfig, 2, LocalTime.of(10, 1));
            assertFalse(result, "isDteExitDue should return false when expiry date is null");
        }, "isDteExitDue should not throw exception for null expiry date");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a test Iron Condor trade with 4 legs.
     *
     * @param expiryDate the expiry date for all legs (can be null for edge case tests)
     * @return a PaperTrade configured as an Iron Condor
     */
    private PaperTrade createIronCondorTrade(LocalDate expiryDate) {
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(19500.0)
                        .limitPrice(50.0)
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(19700.0)
                        .limitPrice(30.0)
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(19300.0)
                        .limitPrice(50.0)
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(19100.0)
                        .limitPrice(30.0)
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build()
        );

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .status(TradeStatus.OPEN)
                .legs(legs)
                .entryCredit(100.0)
                .currentValue(100.0)
                .unrealizedPnl(0.0)
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
    }

    /**
     * Creates a test Bull Put Spread trade with 2 legs.
     *
     * @param expiryDate the expiry date for all legs (can be null for edge case tests)
     * @return a PaperTrade configured as a Bull Put Spread
     */
    private PaperTrade createSpreadTrade(LocalDate expiryDate) {
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(19300.0)
                        .limitPrice(60.0)
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(19200.0)
                        .limitPrice(40.0)
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build()
        );

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.OPEN)
                .legs(legs)
                .entryCredit(100.0)
                .currentValue(100.0)
                .unrealizedPnl(0.0)
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
    }
}
