package com.trademesh.execution;

import com.trademesh.intelligence.vix.VixMonitorService;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.trading.MarketCalendarService;
import com.trademesh.zerodha.options.OptionChainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DTE-based exit logic in PaperTradingEngine.
 * <p>
 * Tests verify that shouldExitIronCondor() and shouldExitSpread() correctly:
 * - Trigger DTE exit when trading days remaining <= threshold
 * - Skip DTE exit when trading days remaining > threshold
 * - Handle null/empty legs gracefully without throwing exceptions
 * - Handle mismatched expiry dates across legs (defensive test)
 * - Respect exit priority (VIX panic checked before DTE exit)
 * <p>
 * These are isolated unit tests using Mockito to mock StrategyProperties and
 * MarketCalendarService. No Spring context, Redis, or database dependencies are required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine DTE Exit Logic Tests")
class PaperTradingEngineDteExitTest {

    private PaperTradingEngine engine;

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private StrategyProperties ironCondorConfig;

    @Mock
    private StrategyProperties spreadConfig;

    @Mock
    private MarketCalendarService marketCalendarService;

    @Mock
    private VixMonitorService vixMonitorService;

    @Mock
    private OptionChainService optionChainService;

    @Mock
    private ReactiveValueOperations<String, String> reactiveValueOps;

    @Mock
    private com.trademesh.zerodha.options.KiteMarginService kiteMarginService;

    @BeforeEach
    void setUp() {
        engine = new PaperTradingEngine(null, redisTemplate, null, null, null, null, vixMonitorService, marketCalendarService, optionChainService, kiteMarginService, null);
        lenient().when(redisTemplate.opsForValue()).thenReturn(reactiveValueOps);
        lenient().when(reactiveValueOps.get("price:NIFTY50")).thenReturn(Mono.just("19400"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IRON CONDOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Iron Condor - DTE > threshold → DTE exit does NOT fire.
     * <p>
     * Verifies that when tradingDaysRemaining (3) > threshold (2),
     * shouldExitIronCondor() returns false and exitReason is not set.
     */
    @Test
    @DisplayName("Iron Condor: DTE > threshold → DTE exit does NOT fire")
    void ironCondor_dteGreaterThanThreshold_noExit() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(5); // 3 trading days away
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(3);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(200.0);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);

        PaperTrade trade = createIronCondorTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0); // Below profit target, above stop loss
        double currentVix = 15.0; // Below VIX panic threshold

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        // Assert
        assertNull(trade.getExitReason(), "exitReason should not be set when DTE exit does not fire");
    }

    /**
     * Test 2: Iron Condor - DTE == threshold exactly → DTE exit FIRES.
     * <p>
     * Verifies that when tradingDaysRemaining (2) == threshold (2),
     * shouldExitIronCondor() returns true and sets exitReason to "DTE_EXIT".
     */
    @Test
    @DisplayName("Iron Condor: DTE == threshold exactly → DTE exit FIRES")
    void ironCondor_dteEqualsThreshold_exitFires() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(4); // 2 trading days away
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(2);

        PaperTrade trade = createIronCondorTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("DTE_EXIT", trade.getExitReason(), "exitReason should be DTE_EXIT");
    }

    /**
     * Test 3: Iron Condor - DTE < threshold (e.g., 0) → DTE exit FIRES.
     * <p>
     * Verifies that when tradingDaysRemaining (0) < threshold (2),
     * shouldExitIronCondor() returns true and sets exitReason to "DTE_EXIT".
     */
    @Test
    @DisplayName("Iron Condor: DTE < threshold (0) → DTE exit FIRES")
    void ironCondor_dteLessThanThreshold_exitFires() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")); // 0 trading days away (expiry is today)
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(0);

        PaperTrade trade = createIronCondorTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("DTE_EXIT", trade.getExitReason(), "exitReason should be DTE_EXIT");
    }

    /**
     * Test 4: Iron Condor - VIX panic + DTE both true → VIX_PANIC takes priority.
     * <p>
     * Verifies that when both VIX panic and DTE exit conditions are met,
     * VIX panic is checked first and takes priority (exitReason = "VIX_PANIC").
     */
    @Test
    @DisplayName("Iron Condor: VIX panic + DTE both true → VIX_PANIC takes priority")
    void ironCondor_vixPanicAndDteBothTrue_vixPanicTakesPriority() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")); // 0 trading days away
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);

        PaperTrade trade = createIronCondorTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 30.0; // Above VIX panic threshold

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("VIX_PANIC", trade.getExitReason(),
                "exitReason should be VIX_PANIC (not DTE_EXIT), confirming VIX panic is checked first");
    }

    /**
     * Test 5: Iron Condor - Null legs → DTE exit skipped, no exception.
     * <p>
     * Verifies that when trade.getLegs() is null,
     * the method logs a warning and falls through to other checks without throwing NPE.
     */
    @Test
    @DisplayName("Iron Condor: Null legs → DTE exit skipped, no exception")
    void ironCondor_nullLegs_noException() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(200.0);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);

        PaperTrade trade = createIronCondorTrade(null);
        trade.setLegs(null); // Explicitly set legs to null
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act & Assert: No exception should be thrown
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        assertNull(trade.getExitReason(), "exitReason should not be set when legs are null");
    }

    /**
     * Test 6: Iron Condor - Empty legs → DTE exit skipped, no exception.
     * <p>
     * Verifies that when trade.getLegs() is empty,
     * the method logs a warning and falls through to other checks without throwing exception.
     */
    @Test
    @DisplayName("Iron Condor: Empty legs → DTE exit skipped, no exception")
    void ironCondor_emptyLegs_noException() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(200.0);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);

        PaperTrade trade = createIronCondorTrade(null);
        trade.setLegs(List.of()); // Explicitly set legs to empty list
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act & Assert: No exception should be thrown
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        assertNull(trade.getExitReason(), "exitReason should not be set when legs are empty");
    }

    /**
     * Test 7: Iron Condor - Mismatched expiry dates across legs (defensive).
     * <p>
     * Verifies that when legs have different expiry dates,
     * the method uses the first leg's expiry date and logs a warning.
     * This is a defensive test to ensure predictable behavior.
     */
    @Test
    @DisplayName("Iron Condor: Mismatched expiry dates → uses first leg's expiry")
    void ironCondor_mismatchedExpiryDates_useFirstLegExpiry() {
        // Arrange
        LocalDate firstLegExpiry = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(4); // 2 trading days
        LocalDate secondLegExpiry = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(11); // 5 trading days

        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(firstLegExpiry))).thenReturn(2);

        PaperTrade trade = createIronCondorTrade(firstLegExpiry);
        // Manually create legs with mismatched expiry dates
        List<TradeLeg> legsWithMismatch = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(19500.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .expiryDate(firstLegExpiry)
                        .quantity(1)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(19700.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .expiryDate(secondLegExpiry) // Different expiry!
                        .quantity(1)
                        .build()
        );
        trade.setLegs(legsWithMismatch);

        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("DTE_EXIT", trade.getExitReason(), "exitReason should be DTE_EXIT");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SPREAD TESTS (BULL_PUT_SPREAD / BEAR_CALL_SPREAD)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 8: Spread - DTE > threshold → DTE exit does NOT fire.
     * <p>
     * Verifies that when tradingDaysRemaining (2) > threshold (1),
     * shouldExitSpread() returns false and exitReason is not set.
     */
     @Test
     @DisplayName("Spread: DTE > threshold → DTE exit does NOT fire")
     void spread_dteGreaterThanThreshold_noExit() {
         // Arrange
         LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(4); // 2 trading days away
         when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
         when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
         when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(2);
         when(spreadConfig.getTargetProfitPercent()).thenReturn(200.0);
         when(spreadConfig.getStopLossPercent()).thenReturn(100.0);

        PaperTrade trade = createSpreadTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        // Assert
        assertNull(trade.getExitReason(), "exitReason should not be set when DTE exit does not fire");
    }

    /**
     * Test 9: Spread - DTE == threshold exactly → DTE exit FIRES.
     * <p>
     * Verifies that when tradingDaysRemaining (1) == threshold (1),
     * shouldExitSpread() returns true and sets exitReason to "DTE_EXIT".
     */
    @Test
    @DisplayName("Spread: DTE == threshold exactly → DTE exit FIRES")
    void spread_dteEqualsThreshold_exitFires() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2); // 1 trading day away
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(1);

        PaperTrade trade = createSpreadTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("DTE_EXIT", trade.getExitReason(), "exitReason should be DTE_EXIT");
    }

    /**
     * Test 10: Spread - DTE < threshold (e.g., 0) → DTE exit FIRES.
     * <p>
     * Verifies that when tradingDaysRemaining (0) < threshold (1),
     * shouldExitSpread() returns true and sets exitReason to "DTE_EXIT".
     */
    @Test
    @DisplayName("Spread: DTE < threshold (0) → DTE exit FIRES")
    void spread_dteLessThanThreshold_exitFires() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")); // 0 trading days away
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(marketCalendarService.countTradingDays(any(LocalDate.class), eq(expiryDate))).thenReturn(0);

        PaperTrade trade = createSpreadTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("DTE_EXIT", trade.getExitReason(), "exitReason should be DTE_EXIT");
    }

    /**
     * Test 11: Spread - VIX panic + DTE both true → VIX_PANIC takes priority.
     * <p>
     * Verifies that when both VIX panic and DTE exit conditions are met,
     * VIX panic is checked first and takes priority (exitReason = "VIX_PANIC").
     */
    @Test
    @DisplayName("Spread: VIX panic + DTE both true → VIX_PANIC takes priority")
    void spread_vixPanicAndDteBothTrue_vixPanicTakesPriority() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")); // 0 trading days away
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);

        PaperTrade trade = createSpreadTrade(expiryDate);
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 30.0; // Above VIX panic threshold

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("VIX_PANIC", trade.getExitReason(),
                "exitReason should be VIX_PANIC (not DTE_EXIT), confirming VIX panic is checked first");
    }

    /**
     * Test 12: Spread - Null legs → DTE exit skipped, no exception.
     * <p>
     * Verifies that when trade.getLegs() is null,
     * the method logs a warning and falls through to other checks without throwing NPE.
     */
    @Test
    @DisplayName("Spread: Null legs → DTE exit skipped, no exception")
    void spread_nullLegs_noException() {
         // Arrange
         when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
         when(spreadConfig.getTargetProfitPercent()).thenReturn(200.0);
         when(spreadConfig.getStopLossPercent()).thenReturn(100.0);

         PaperTrade trade = createSpreadTrade(null);
         trade.setLegs(null); // Explicitly set legs to null
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act & Assert: No exception should be thrown
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        assertNull(trade.getExitReason(), "exitReason should not be set when legs are null");
    }

    /**
     * Test 13: Spread - Empty legs → DTE exit skipped, no exception.
     * <p>
     * Verifies that when trade.getLegs() is empty,
     * the method logs a warning and falls through to other checks without throwing exception.
     */
    @Test
    @DisplayName("Spread: Empty legs → DTE exit skipped, no exception")
    void spread_emptyLegs_noException() {
         // Arrange
         when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
         when(spreadConfig.getTargetProfitPercent()).thenReturn(200.0);
         when(spreadConfig.getStopLossPercent()).thenReturn(100.0);

         PaperTrade trade = createSpreadTrade(null);
         trade.setLegs(List.of()); // Explicitly set legs to empty list
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0);
        double currentVix = 15.0;

        // Act & Assert: No exception should be thrown
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        assertNull(trade.getExitReason(), "exitReason should not be set when legs are empty");
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
                        .strikePrice(BigDecimal.valueOf(19500.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(19700.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19300.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19100.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
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
                .entryCredit(BigDecimal.valueOf(100.0))
                .currentValue(BigDecimal.valueOf(100.0))
                .unrealizedPnl(BigDecimal.valueOf(0.0))
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
                        .strikePrice(BigDecimal.valueOf(19300.0))
                        .limitPrice(BigDecimal.valueOf(60.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19200.0))
                        .limitPrice(BigDecimal.valueOf(40.0))
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
                .entryCredit(BigDecimal.valueOf(100.0))
                .currentValue(BigDecimal.valueOf(100.0))
                .unrealizedPnl(BigDecimal.valueOf(0.0))
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
    }
}
