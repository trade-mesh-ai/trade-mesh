package com.trademesh.execution;

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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for multi-lot exit scaling in PaperTradingEngine.
 * <p>
 * Tests verify that shouldExitIronCondor() and shouldExitSpread() correctly
 * scale entry credit and max loss by the actual lot count when determining
 * stop-loss and profit-target thresholds.
 * <p>
 * These tests use 7 lots (matching real production data from 2026-07-20)
 * to verify the scaling fix for the confirmed bug where thresholds were
 * previously unscaled, causing exits to trigger ~1/7th of intended values.
 * <p>
 * These are isolated unit tests using Mockito to mock Redis and StrategyProperties.
 * No Spring context or database dependencies are required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine Multi-Lot Exit Scaling Tests")
class PaperTradingEngineMultiLotExitTest {

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
    private OptionChainService optionChainService;

    @Mock
    private ReactiveValueOperations<String, String> reactiveValueOps;

    @BeforeEach
    void setUp() {
        // Create a minimal PaperTradingEngine instance with mocked Redis and MarketCalendarService
        lenient().when(redisTemplate.opsForValue()).thenReturn(reactiveValueOps);
        lenient().when(reactiveValueOps.get("price:NIFTY50")).thenReturn(Mono.just("24000"));

        engine = new PaperTradingEngine(null, redisTemplate, null, null, null, null, null, marketCalendarService, optionChainService);

        // Stub marketCalendarService for all tests (safe DTE value)
        lenient().when(marketCalendarService.countTradingDays(any(), any())).thenReturn(5);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IRON CONDOR TESTS (7 LOTS)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Iron Condor at 7 lots - Profit target hit at scaled threshold.
     * <p>
     * Setup: entryCredit=10.0, targetProfitPercent=50, 7 lots
     * Expected threshold: 10.0 × 7 × 0.5 = 35.0
     * <p>
     * Verifies that shouldExitIronCondor() returns true when unrealizedPnl >= 35.0
     * (the correctly scaled target), and false when unrealizedPnl < 35.0.
     */
    @Test
    @DisplayName("Iron Condor 7 lots: TARGET_HIT at scaled threshold (35.0)")
    void ironCondor_7lots_targetHit_atScaledThreshold() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createIronCondorTrade7Lots();
        double currentVix = 15.0; // Below panic threshold
        java.time.LocalTime currentTime = java.time.LocalTime.of(10, 0);

        // Act & Assert: At exactly 35.0, should exit with TARGET_HIT
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, BigDecimal.valueOf(35.0), ironCondorConfig, currentVix, currentTime))
                .expectNext(true)
                .verifyComplete();
        assertEquals("TARGET_HIT", trade.getExitReason(), "Should exit with TARGET_HIT at scaled threshold");

        // Act & Assert: Just below 35.0 (34.99), should NOT exit
        trade = createIronCondorTrade7Lots();
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, BigDecimal.valueOf(34.99), ironCondorConfig, currentVix, currentTime))
                .expectNext(false)
                .verifyComplete();
        assertNull(trade.getExitReason(), "Should not exit when below scaled threshold");
    }

    /**
     * Test 2: Iron Condor at 7 lots - Stop loss hit at scaled threshold.
     * <p>
     * Setup: entryCredit=10.0, stopLossPercent=100, 7 lots
     * Expected threshold: -(10.0 × 7 × 1.0) = -70.0
     * <p>
     * Verifies that shouldExitIronCondor() returns true when unrealizedPnl <= -70.0
     * (the correctly scaled stop loss), and false when unrealizedPnl > -70.0.
     */
    @Test
    @DisplayName("Iron Condor 7 lots: STOP_LOSS at scaled threshold (-70.0)")
    void ironCondor_7lots_stopLoss_atScaledThreshold() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createIronCondorTrade7Lots();
        double currentVix = 15.0; // Below panic threshold
        java.time.LocalTime currentTime = java.time.LocalTime.of(10, 0);

        // Act & Assert: Just past -70.0 (-70.01), should exit with STOPLOSS_HIT
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, BigDecimal.valueOf(-70.01), ironCondorConfig, currentVix, currentTime))
                .expectNext(true)
                .verifyComplete();
        assertEquals("STOPLOSS_HIT", trade.getExitReason(), "Should exit with STOPLOSS_HIT at scaled threshold");

        // Act & Assert: Just above -70.0 (-69.99), should NOT exit
        trade = createIronCondorTrade7Lots();
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, BigDecimal.valueOf(-69.99), ironCondorConfig, currentVix, currentTime))
                .expectNext(false)
                .verifyComplete();
        assertNull(trade.getExitReason(), "Should not exit when above scaled threshold");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SPREAD TESTS (7 LOTS)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 3: Spread (Bull Put/Bear Call) at 7 lots - Profit target hit at scaled threshold.
     * <p>
     * Setup: entryCredit=10.0, targetProfitPercent=50, 7 lots
     * Expected threshold: 10.0 × 7 × 0.5 = 35.0
     * <p>
     * Verifies that shouldExitSpread() returns true when unrealizedPnl >= 35.0
     * (the correctly scaled target), and false when unrealizedPnl < 35.0.
     */
    @Test
    @DisplayName("Spread 7 lots: TARGET_HIT at scaled threshold (35.0)")
    void spread_7lots_targetHit_atScaledThreshold() {
        // Arrange
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(spreadConfig.getTargetProfitPercent()).thenReturn(50.0);
        when(spreadConfig.getStopLossPercent()).thenReturn(100.0);
        when(spreadConfig.getExtraInt("wing-width", 100)).thenReturn(100);

        PaperTrade trade = createBullPutSpreadTrade7Lots();
        double currentVix = 15.0; // Below panic threshold
        java.time.LocalTime currentTime = java.time.LocalTime.of(10, 0);

        // Act & Assert: At exactly 35.0, should exit with TARGET_HIT
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, BigDecimal.valueOf(35.0), spreadConfig, currentVix, currentTime))
                .expectNext(true)
                .verifyComplete();
        assertEquals("TARGET_HIT", trade.getExitReason(), "Should exit with TARGET_HIT at scaled threshold");

        // Act & Assert: Just below 35.0 (34.99), should NOT exit
        trade = createBullPutSpreadTrade7Lots();
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, BigDecimal.valueOf(34.99), spreadConfig, currentVix, currentTime))
                .expectNext(false)
                .verifyComplete();
        assertNull(trade.getExitReason(), "Should not exit when below scaled threshold");
    }

    /**
     * Test 4: Spread (Bull Put/Bear Call) at 7 lots - Stop loss hit at scaled threshold.
     * <p>
     * Setup: wingWidth=100, lotSize=65 (NIFTY50), stopLossPercent=100, 7 lots
     * Expected maxLoss: 100 × 65 × 7 = 45500
     * Expected threshold: -(45500 × 1.0) = -45500
     * <p>
     * Verifies that shouldExitSpread() returns true when unrealizedPnl <= -45500
     * (the correctly scaled stop loss), and false when unrealizedPnl > -45500.
     */
    @Test
    @DisplayName("Spread 7 lots: STOP_LOSS at scaled threshold (-45500)")
    void spread_7lots_stopLoss_atScaledThreshold() {
        // Arrange
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(spreadConfig.getTargetProfitPercent()).thenReturn(50.0);
        when(spreadConfig.getStopLossPercent()).thenReturn(100.0);
        when(spreadConfig.getExtraInt("wing-width", 100)).thenReturn(100);

        PaperTrade trade = createBullPutSpreadTrade7Lots();
        double currentVix = 15.0; // Below panic threshold
        java.time.LocalTime currentTime = java.time.LocalTime.of(10, 0);

        // Act & Assert: At exactly -45500, should exit with STOPLOSS_HIT
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, BigDecimal.valueOf(-45500.0), spreadConfig, currentVix, currentTime))
                .expectNext(true)
                .verifyComplete();
        assertEquals("STOPLOSS_HIT", trade.getExitReason(), "Should exit with STOPLOSS_HIT at scaled threshold");

        // Act & Assert: Just above -45500 (-45499), should NOT exit
        trade = createBullPutSpreadTrade7Lots();
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, BigDecimal.valueOf(-45499.0), spreadConfig, currentVix, currentTime))
                .expectNext(false)
                .verifyComplete();
        assertNull(trade.getExitReason(), "Should not exit when above scaled threshold");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates an Iron Condor trade with 7 lots.
     * entryCredit=10.0 (per lot), so total entry credit = 10.0
     * (entry credit is stored per-lot in the database)
     */
    private PaperTrade createIronCondorTrade7Lots() {
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("IRON_CONDOR");
        trade.setStatus(TradeStatus.OPEN);
        trade.setEntryCredit(10.0); // Per-lot entry credit
        trade.setCurrentValue(10.0);
        trade.setUnrealizedPnl(0.0);
        trade.setEntryTime(LocalDateTime.now());
        trade.setTradeDate(LocalDate.now());

        // 4 legs with quantity=7 (7 lots)
        List<TradeLeg> legs = List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.PE).strikePrice(23900.0)
                        .limitPrice(5.0).quantity(7).expiryDate(LocalDate.now().plusDays(7)).build(),
                TradeLeg.builder().action(Action.BUY).optionType(OptionType.PE).strikePrice(23800.0)
                        .limitPrice(3.0).quantity(7).expiryDate(LocalDate.now().plusDays(7)).build(),
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.CE).strikePrice(24100.0)
                        .limitPrice(5.0).quantity(7).expiryDate(LocalDate.now().plusDays(7)).build(),
                TradeLeg.builder().action(Action.BUY).optionType(OptionType.CE).strikePrice(24200.0)
                        .limitPrice(3.0).quantity(7).expiryDate(LocalDate.now().plusDays(7)).build()
        );
        trade.setLegs(legs);

        return trade;
    }

    /**
     * Creates a Bull Put Spread trade with 7 lots.
     * entryCredit=10.0 (per lot), so total entry credit = 10.0
     * (entry credit is stored per-lot in the database)
     */
    private PaperTrade createBullPutSpreadTrade7Lots() {
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("BULL_PUT_SPREAD");
        trade.setStatus(TradeStatus.OPEN);
        trade.setEntryCredit(10.0); // Per-lot entry credit
        trade.setCurrentValue(10.0);
        trade.setUnrealizedPnl(0.0);
        trade.setEntryTime(LocalDateTime.now());
        trade.setTradeDate(LocalDate.now());

        // 2 legs with quantity=7 (7 lots)
        List<TradeLeg> legs = List.of(
                TradeLeg.builder().action(Action.SELL).optionType(OptionType.PE).strikePrice(23900.0)
                        .limitPrice(5.0).quantity(7).expiryDate(LocalDate.now().plusDays(7)).build(),
                TradeLeg.builder().action(Action.BUY).optionType(OptionType.PE).strikePrice(23800.0)
                        .limitPrice(3.0).quantity(7).expiryDate(LocalDate.now().plusDays(7)).build()
        );
        trade.setLegs(legs);

        return trade;
    }
}
