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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for VIX panic exit logic in PaperTradingEngine.
 * <p>
 * Tests verify that shouldExitIronCondor() and shouldExitSpread() correctly:
 * - Trigger panic exit when VIX exceeds threshold
 * - Skip panic exit when VIX is below threshold
 * - Handle sentinel value (-1.0) safely without throwing exceptions
 * - Respect exit priority (VIX panic checked first)
 * <p>
 * These are isolated unit tests using Mockito to mock StrategyProperties.
 * No Spring context, Redis, or database dependencies are required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine VIX Panic Exit Logic Tests")
class PaperTradingEngineVixPanicTest {

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
        // Constructor: (paperTradeRepository, redisTemplate, telegramNotificationBot, objectMapper, strategiesConfig, lotSizeService, vixMonitorService, marketCalendarService)
        lenient().when(redisTemplate.opsForValue()).thenReturn(reactiveValueOps);
        lenient().when(reactiveValueOps.get("price:NIFTY50")).thenReturn(Mono.just("19400"));

        engine = new PaperTradingEngine(null, redisTemplate, null, null, null, null, null, marketCalendarService, optionChainService);

        // Stub marketCalendarService for all tests (safe DTE value)
        lenient().when(marketCalendarService.countTradingDays(any(), any())).thenReturn(5);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IRON CONDOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Iron Condor - VIX above threshold triggers panic exit.
     * <p>
     * Verifies that when currentVix (30.0) exceeds the threshold (25.0),
     * shouldExitIronCondor() returns true AND sets exitReason to "VIX_PANIC".
     */
    @Test
    @DisplayName("Iron Condor: VIX above threshold triggers panic exit")
    void ironCondor_vixAboveThreshold_triggersPanicExit() {
        // Arrange
        when(ironCondorConfig.getName()).thenReturn("IRON_CONDOR");
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);

        PaperTrade trade = createIronCondorTrade();
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0); // Positive P&L (not at stop loss)
        double currentVix = 30.0; // Above threshold of 25.0

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("VIX_PANIC", trade.getExitReason(),
                "exitReason should be set to VIX_PANIC when VIX panic exit is triggered");
    }

    /**
     * Test 2: Iron Condor - VIX below threshold does NOT trigger panic exit.
     * <p>
     * Verifies that when currentVix (15.0) is below the threshold (25.0),
     * and no other exit conditions are met, shouldExitIronCondor() returns false.
     */
    @Test
    @DisplayName("Iron Condor: VIX below threshold does NOT trigger panic exit")
    void ironCondor_vixBelowThreshold_noPanicExit() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(200.0); // Target = 100 * 2.0 = 200.0
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0); // Stop loss = -100.0

        PaperTrade trade = createIronCondorTrade();
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0); // Below profit target (200.0), above stop loss (-100.0)
        double currentVix = 15.0; // Below threshold of 25.0

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        // Assert
        assertNull(trade.getExitReason(), "exitReason should not be set when no exit condition is triggered");
    }

    /**
     * Test 3: Iron Condor - Sentinel value (-1.0) handled safely.
     * <p>
     * Verifies that when currentVix is -1.0 (sentinel for unavailable data),
     * no exception is thrown and the panic exit is not triggered.
     * The method should fall through to evaluate other conditions normally.
     */
    @Test
    @DisplayName("Iron Condor: Sentinel value (-1.0) handled safely")
    void ironCondor_sentinelValue_handledSafely() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(200.0); // Target = 100 * 2.0 = 200.0

        PaperTrade trade = createIronCondorTrade();
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0); // Below profit target (200.0), above stop loss (-100.0)
        double currentVix = -1.0; // Sentinel value for unavailable VIX

        // Act & Assert: No exception should be thrown
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        assertNull(trade.getExitReason(), "exitReason should not be set when VIX is unavailable");
    }

    /**
     * Test 4: Iron Condor - Exit priority: VIX panic fires even when stop-loss also true.
     * <p>
     * Verifies that VIX panic is checked FIRST and takes priority over stop-loss.
     * When both VIX panic and stop-loss conditions are true, exitReason should be "VIX_PANIC".
     */
    @Test
    @DisplayName("Iron Condor: Exit priority - VIX panic fires even when stop-loss also true")
    void ironCondor_exitPriority_vixPanicBeforeStopLoss() {
        // Arrange
        when(ironCondorConfig.getName()).thenReturn("IRON_CONDOR");
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);

        PaperTrade trade = createIronCondorTrade();
        // Stop loss threshold: entryCredit * (stopLossPercent / 100) = 100 * 1.0 = 100
        // Set unrealizedPnl to -150 (worse than stop loss threshold of -100)
        BigDecimal unrealizedPnl = BigDecimal.valueOf(-150.0);
        double currentVix = 30.0; // Above threshold, triggers VIX panic

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("VIX_PANIC", trade.getExitReason(),
                "exitReason should be VIX_PANIC (not STOPLOSS_HIT), confirming VIX panic is checked first");
    }

    /**
     * Test 5b: Spread - Exit priority: VIX panic fires even when profit target also true.
     * <p>
     * Verifies that VIX panic is checked FIRST and takes priority over profit target.
     * When both VIX panic and profit target conditions are true, exitReason should be "VIX_PANIC".
     */
    @Test
    @DisplayName("Spread: Exit priority - VIX panic fires even when profit target also true")
    void spread_exitPriority_vixPanicBeforeProfitTarget() {
        // Arrange
        when(spreadConfig.getName()).thenReturn("BULL_PUT_SPREAD");
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);

        PaperTrade trade = createSpreadTrade();
        double entryCredit = trade.getEntryCredit().doubleValue();
        // Profit target threshold: entryCredit * (targetProfitPercent / 100) = 100 * 0.5 = 50
        // Set unrealizedPnl to 75 (exceeds profit target of 50)
        BigDecimal unrealizedPnl = BigDecimal.valueOf(75.0);
        double currentVix = 30.0; // Above threshold, triggers VIX panic

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("VIX_PANIC", trade.getExitReason(),
                "exitReason should be VIX_PANIC (not TARGET_HIT), confirming VIX panic is checked first");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SPREAD TESTS (BULL_PUT_SPREAD / BEAR_CALL_SPREAD)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 4a: Spread - VIX above threshold triggers panic exit.
     * <p>
     * Verifies that when currentVix (30.0) exceeds the threshold (25.0),
     * shouldExitSpread() returns true AND sets exitReason to "VIX_PANIC".
     */
    @Test
    @DisplayName("Spread: VIX above threshold triggers panic exit")
    void spread_vixAboveThreshold_triggersPanicExit() {
        // Arrange
        when(spreadConfig.getName()).thenReturn("BULL_PUT_SPREAD");
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);

        PaperTrade trade = createSpreadTrade();
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0); // Positive P&L
        double currentVix = 30.0; // Above threshold of 25.0

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();

        // Assert
        assertEquals("VIX_PANIC", trade.getExitReason(),
                "exitReason should be set to VIX_PANIC when VIX panic exit is triggered");
    }

    /**
     * Test 4b: Spread - VIX below threshold does NOT trigger panic exit.
     * <p>
     * Verifies that when currentVix (15.0) is below the threshold (25.0),
     * and no other exit conditions are met, shouldExitSpread() returns false.
     */
    @Test
    @DisplayName("Spread: VIX below threshold does NOT trigger panic exit")
    void spread_vixBelowThreshold_noPanicExit() {
        // Arrange
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getTargetProfitPercent()).thenReturn(200.0); // Target = 100 * 2.0 = 200.0
        when(spreadConfig.getStopLossPercent()).thenReturn(100.0); // Stop loss = -100.0
        when(spreadConfig.getExtraInt("wing-width", 100)).thenReturn(100);

        PaperTrade trade = createSpreadTrade();
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0); // Below profit target (200.0), above stop loss (-100.0)
        double currentVix = 15.0; // Below threshold of 25.0

        // Act
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        // Assert
        assertNull(trade.getExitReason(), "exitReason should not be set when no exit condition is triggered");
    }

    /**
     * Test 4c: Spread - Sentinel value (-1.0) handled safely.
     * <p>
     * Verifies that when currentVix is -1.0 (sentinel for unavailable data),
     * no exception is thrown and the panic exit is not triggered.
     */
    @Test
    @DisplayName("Spread: Sentinel value (-1.0) handled safely")
    void spread_sentinelValue_handledSafely() {
        // Arrange
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getTargetProfitPercent()).thenReturn(200.0); // Target = 100 * 2.0 = 200.0
        when(spreadConfig.getStopLossPercent()).thenReturn(100.0); // Stop loss = -100.0
        when(spreadConfig.getExtraInt("wing-width", 100)).thenReturn(100);

        PaperTrade trade = createSpreadTrade();
        BigDecimal unrealizedPnl = BigDecimal.valueOf(10.0); // Below profit target (200.0), above stop loss (-100.0)
        double currentVix = -1.0; // Sentinel value for unavailable VIX

        // Act & Assert: No exception should be thrown
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, currentVix, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        assertNull(trade.getExitReason(), "exitReason should not be set when VIX is unavailable");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a test Iron Condor trade with 4 legs (2 SELL, 2 BUY).
     *
     * @return a PaperTrade configured as an Iron Condor
     */
    private PaperTrade createIronCondorTrade() {
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(19500.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .quantity(1)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(19700.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
                        .build(),
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19300.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .quantity(1)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19100.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
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
     * Creates a test Bull Put Spread trade with 2 legs (1 SELL, 1 BUY).
     *
     * @return a PaperTrade configured as a Bull Put Spread
     */
    private PaperTrade createSpreadTrade() {
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19300.0))
                        .limitPrice(BigDecimal.valueOf(60.0))
                        .quantity(1)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19200.0))
                        .limitPrice(BigDecimal.valueOf(40.0))
                        .quantity(1)
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
