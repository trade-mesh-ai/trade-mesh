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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for frozen field (stopDistancePoints, targetThreshold) behavior
 * in shouldExitIronCondor() and shouldExitSpread().
 * <p>
 * Tests verify that:
 * 1. Frozen fields produce identical thresholds as live formula (zero behavior change)
 * 2. Null frozen fields fall back to live computation (backward compatibility)
 * 3. Asymmetric operators are preserved (Iron Condor: < 0, Spread: <= 0)
 * <p>
 * Uses real builder-constructed PaperTrade/StrategyProperties objects.
 * Mocks only external dependencies: Redis, MarketCalendarService, OptionChainService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine Frozen Fields Tests")
class PaperTradingEngineFrozenFieldsTest {

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
        lenient().when(redisTemplate.opsForValue()).thenReturn(reactiveValueOps);
        lenient().when(reactiveValueOps.get("price:NIFTY50")).thenReturn(Mono.just("24000"));

        engine = new PaperTradingEngine(null, redisTemplate, null, null, null, null, null, marketCalendarService, optionChainService);

        // Stub marketCalendarService for all tests (safe DTE value)
        lenient().when(marketCalendarService.countTradingDays(any(), any())).thenReturn(5);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IRON CONDOR TESTS (FROZEN STOP LOSS)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Iron Condor frozen stopDistancePoints matches live formula.
     * <p>
     * Setup: entryCredit=10.0, stopDistancePoints=10.0 (per-lot), 7 lots
     * Expected threshold: 10.0 × 7 × -1 = -70.0
     * <p>
     * Verifies strict < 0 operator (Iron Condor asymmetry):
     * - unrealizedPnl = -70.01 → exit triggers (strictly below threshold)
     * - unrealizedPnl = -70.0  → exit does NOT trigger (exactly at threshold, strict inequality)
     * - unrealizedPnl = -69.99 → exit does NOT trigger (above threshold)
     */
    @Test
    @DisplayName("Iron Condor: Frozen stopDistancePoints matches live formula (strict < 0)")
    void ironCondor_frozenStopLoss_matchesLiveFormula() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createIronCondorTrade7LotsWithFrozenStopLoss();
        BigDecimal unrealizedPnl;

        // Act & Assert: At -70.01, should exit (strictly below -70.0)
        unrealizedPnl = BigDecimal.valueOf(-70.01);
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("STOPLOSS_HIT", trade.getExitReason());

        // Act & Assert: At exactly -70.0, should NOT exit (strict inequality)
        trade = createIronCondorTrade7LotsWithFrozenStopLoss();
        unrealizedPnl = BigDecimal.valueOf(-70.0);
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();

        // Act & Assert: At -69.99, should NOT exit (above threshold)
        trade = createIronCondorTrade7LotsWithFrozenStopLoss();
        unrealizedPnl = BigDecimal.valueOf(-69.99);
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();
    }

    /**
     * Test 2: Iron Condor frozen targetThreshold matches live formula.
     * <p>
     * Setup: entryCredit=10.0, targetThreshold=5.0 (per-lot), 7 lots
     * Expected threshold: 5.0 × 7 = 35.0
     * <p>
     * Verifies >= 0 operator (same for both Iron Condor and Spread):
     * - unrealizedPnl = 35.0  → exit triggers (at or above threshold)
     * - unrealizedPnl = 34.99 → exit does NOT trigger (below threshold)
     */
    @Test
    @DisplayName("Iron Condor: Frozen targetThreshold matches live formula")
    void ironCondor_frozenTargetThreshold_matchesLiveFormula() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createIronCondorTrade7LotsWithFrozenTargetThreshold();
        BigDecimal unrealizedPnl;

        // Act & Assert: At exactly 35.0, should exit (at threshold)
        unrealizedPnl = BigDecimal.valueOf(35.0);
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("TARGET_HIT", trade.getExitReason());

        // Act & Assert: At 34.99, should NOT exit (below threshold)
        trade = createIronCondorTrade7LotsWithFrozenTargetThreshold();
        unrealizedPnl = BigDecimal.valueOf(34.99);
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();
    }

    /**
     * Test 3: Iron Condor null frozen fields fall back to live computation.
     * <p>
     * Setup: entryCredit=10.0, stopDistancePoints=null, targetThreshold=null, 7 lots
     * Expected behavior: same as live formula (backward compatibility)
     * <p>
     * Verifies that null fields trigger fallback to live computation.
     */
    @Test
    @DisplayName("Iron Condor: Null frozen fields fall back to live computation")
    void ironCondor_nullFrozenFields_fallbackToLive() {
        // Arrange
        when(ironCondorConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(ironCondorConfig.getExtraInt("dte-exit-threshold", 2)).thenReturn(2);
        when(ironCondorConfig.getStopLossPercent()).thenReturn(100.0);
        when(ironCondorConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createIronCondorTrade7LotsWithoutFrozenFields();
        BigDecimal unrealizedPnl;

        // Act & Assert: Stop loss at -70.01 should trigger (live formula: 10.0 × 7 × 1.0 = 70.0)
        unrealizedPnl = BigDecimal.valueOf(-70.01);
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("STOPLOSS_HIT", trade.getExitReason());

        // Act & Assert: Target at 35.0 should trigger (live formula: 10.0 × 7 × 0.5 = 35.0)
        trade = createIronCondorTrade7LotsWithoutFrozenFields();
        unrealizedPnl = BigDecimal.valueOf(35.0);
        reactor.test.StepVerifier.create(engine.shouldExitIronCondor(trade, unrealizedPnl, ironCondorConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("TARGET_HIT", trade.getExitReason());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SPREAD TESTS (FROZEN STOP LOSS)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 4: Spread frozen stopDistancePoints matches live formula.
     * <p>
     * Setup: entryCredit=10.0, stopDistancePoints=10.0 (per-lot), 7 lots
     * Expected threshold: 10.0 × 7 × -1 = -70.0
     * <p>
     * Verifies <= 0 operator (Spread asymmetry — different from Iron Condor):
     * - unrealizedPnl = -70.01 → exit triggers (below threshold)
     * - unrealizedPnl = -70.0  → exit DOES trigger (exactly at threshold, <= includes equality)
     * - unrealizedPnl = -69.99 → exit does NOT trigger (above threshold)
     */
    @Test
    @DisplayName("Spread: Frozen stopDistancePoints matches live formula (loose <= 0)")
    void spread_frozenStopLoss_matchesLiveFormula() {
        // Arrange
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(spreadConfig.getStopLossPercent()).thenReturn(100.0);
        when(spreadConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createBullPutSpreadTrade7LotsWithFrozenStopLoss();
        BigDecimal unrealizedPnl;

        // Act & Assert: At -70.01, should exit (below -70.0)
        unrealizedPnl = BigDecimal.valueOf(-70.01);
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("STOPLOSS_HIT", trade.getExitReason());

        // Act & Assert: At exactly -70.0, SHOULD exit (spread uses <=, not <)
        trade = createBullPutSpreadTrade7LotsWithFrozenStopLoss();
        unrealizedPnl = BigDecimal.valueOf(-70.0);
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("STOPLOSS_HIT", trade.getExitReason());

        // Act & Assert: At -69.99, should NOT exit (above threshold)
        trade = createBullPutSpreadTrade7LotsWithFrozenStopLoss();
        unrealizedPnl = BigDecimal.valueOf(-69.99);
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();
    }

    /**
     * Test 5: Spread frozen targetThreshold matches live formula.
     * <p>
     * Setup: entryCredit=10.0, targetThreshold=5.0 (per-lot), 7 lots
     * Expected threshold: 5.0 × 7 = 35.0
     * <p>
     * Verifies >= 0 operator (same for both Iron Condor and Spread):
     * - unrealizedPnl = 35.0  → exit triggers (at or above threshold)
     * - unrealizedPnl = 34.99 → exit does NOT trigger (below threshold)
     */
    @Test
    @DisplayName("Spread: Frozen targetThreshold matches live formula")
    void spread_frozenTargetThreshold_matchesLiveFormula() {
        // Arrange
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(spreadConfig.getStopLossPercent()).thenReturn(100.0);
        when(spreadConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createBullPutSpreadTrade7LotsWithFrozenTargetThreshold();
        BigDecimal unrealizedPnl;

        // Act & Assert: At exactly 35.0, should exit (at threshold)
        unrealizedPnl = BigDecimal.valueOf(35.0);
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("TARGET_HIT", trade.getExitReason());

        // Act & Assert: At 34.99, should NOT exit (below threshold)
        trade = createBullPutSpreadTrade7LotsWithFrozenTargetThreshold();
        unrealizedPnl = BigDecimal.valueOf(34.99);
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(false)
                .verifyComplete();
    }

    /**
     * Test 6: Spread null frozen fields fall back to live computation.
     * <p>
     * Setup: entryCredit=10.0, stopDistancePoints=null, targetThreshold=null, 7 lots
     * Expected behavior: same as live formula (backward compatibility)
     * <p>
     * Verifies that null fields trigger fallback to live computation.
     */
    @Test
    @DisplayName("Spread: Null frozen fields fall back to live computation")
    void spread_nullFrozenFields_fallbackToLive() {
        // Arrange
        when(spreadConfig.getExtraDouble("vix-panic-threshold", 25.0)).thenReturn(25.0);
        when(spreadConfig.getExtraInt("dte-exit-threshold", 1)).thenReturn(1);
        when(spreadConfig.getStopLossPercent()).thenReturn(100.0);
        when(spreadConfig.getTargetProfitPercent()).thenReturn(50.0);

        PaperTrade trade = createBullPutSpreadTrade7LotsWithoutFrozenFields();
        BigDecimal unrealizedPnl;

        // Act & Assert: Stop loss at -70.0 should trigger (live formula: 10.0 × 7 × 1.0 = 70.0, spread uses <=)
        unrealizedPnl = BigDecimal.valueOf(-70.0);
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("STOPLOSS_HIT", trade.getExitReason());

        // Act & Assert: Target at 35.0 should trigger (live formula: 10.0 × 7 × 0.5 = 35.0)
        trade = createBullPutSpreadTrade7LotsWithoutFrozenFields();
        unrealizedPnl = BigDecimal.valueOf(35.0);
        reactor.test.StepVerifier.create(engine.shouldExitSpread(trade, unrealizedPnl, spreadConfig, -1.0, java.time.LocalTime.of(10, 0)))
                .expectNext(true)
                .verifyComplete();
        assertEquals("TARGET_HIT", trade.getExitReason());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private PaperTrade createIronCondorTrade7LotsWithFrozenStopLoss() {
        LocalDate expiryDate = LocalDate.now().plusDays(7);

        TradeLeg shortCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 25000 CE")
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(25000))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(50))
                .build();

        TradeLeg shortPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 23000 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(23000))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeLeg longCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 25100 CE")
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(25100))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(20))
                .build();

        TradeLeg longPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 22900 PE")
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(22900))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(15))
                .build();

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .status(TradeStatus.OPEN)
                .legs(List.of(shortCallLeg, shortPutLeg, longCallLeg, longPutLeg))
                .entryCredit(BigDecimal.valueOf(10.0))
                .currentValue(BigDecimal.valueOf(10.0))
                .unrealizedPnl(BigDecimal.ZERO)
                .entryTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .stopDistancePoints(BigDecimal.valueOf(10.0))  // Per-lot: 10.0
                .targetThreshold(null)
                .build();
    }

    private PaperTrade createIronCondorTrade7LotsWithFrozenTargetThreshold() {
        LocalDate expiryDate = LocalDate.now().plusDays(7);

        TradeLeg shortCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 25000 CE")
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(25000))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(50))
                .build();

        TradeLeg shortPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 23000 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(23000))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeLeg longCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 25100 CE")
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(25100))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(20))
                .build();

        TradeLeg longPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 22900 PE")
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(22900))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(15))
                .build();

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .status(TradeStatus.OPEN)
                .legs(List.of(shortCallLeg, shortPutLeg, longCallLeg, longPutLeg))
                .entryCredit(BigDecimal.valueOf(10.0))
                .currentValue(BigDecimal.valueOf(10.0))
                .unrealizedPnl(BigDecimal.ZERO)
                .entryTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .stopDistancePoints(null)
                .targetThreshold(BigDecimal.valueOf(5.0))  // Per-lot: 5.0
                .build();
    }

    private PaperTrade createIronCondorTrade7LotsWithoutFrozenFields() {
        LocalDate expiryDate = LocalDate.now().plusDays(7);

        TradeLeg shortCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 25000 CE")
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(25000))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(50))
                .build();

        TradeLeg shortPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 23000 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(23000))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeLeg longCallLeg = TradeLeg.builder()
                .instrument("NIFTY50 25100 CE")
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(BigDecimal.valueOf(25100))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(20))
                .build();

        TradeLeg longPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 22900 PE")
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(22900))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(15))
                .build();

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .status(TradeStatus.OPEN)
                .legs(List.of(shortCallLeg, shortPutLeg, longCallLeg, longPutLeg))
                .entryCredit(BigDecimal.valueOf(10.0))
                .currentValue(BigDecimal.valueOf(10.0))
                .unrealizedPnl(BigDecimal.ZERO)
                .entryTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .stopDistancePoints(null)
                .targetThreshold(null)
                .build();
    }

    private PaperTrade createBullPutSpreadTrade7LotsWithFrozenStopLoss() {
        LocalDate expiryDate = LocalDate.now().plusDays(7);

        TradeLeg shortPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19200 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19200))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeLeg longPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19100 PE")
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19100))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(15))
                .build();

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.OPEN)
                .legs(List.of(shortPutLeg, longPutLeg))
                .entryCredit(BigDecimal.valueOf(10.0))
                .currentValue(BigDecimal.valueOf(10.0))
                .unrealizedPnl(BigDecimal.ZERO)
                .entryTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .stopDistancePoints(BigDecimal.valueOf(10.0))  // Per-lot: 10.0
                .targetThreshold(null)
                .build();
    }

    private PaperTrade createBullPutSpreadTrade7LotsWithFrozenTargetThreshold() {
        LocalDate expiryDate = LocalDate.now().plusDays(7);

        TradeLeg shortPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19200 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19200))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeLeg longPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19100 PE")
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19100))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(15))
                .build();

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.OPEN)
                .legs(List.of(shortPutLeg, longPutLeg))
                .entryCredit(BigDecimal.valueOf(10.0))
                .currentValue(BigDecimal.valueOf(10.0))
                .unrealizedPnl(BigDecimal.ZERO)
                .entryTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .stopDistancePoints(null)
                .targetThreshold(BigDecimal.valueOf(5.0))  // Per-lot: 5.0
                .build();
    }

    private PaperTrade createBullPutSpreadTrade7LotsWithoutFrozenFields() {
        LocalDate expiryDate = LocalDate.now().plusDays(7);

        TradeLeg shortPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19200 PE")
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19200))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(45))
                .build();

        TradeLeg longPutLeg = TradeLeg.builder()
                .instrument("NIFTY50 19100 PE")
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(BigDecimal.valueOf(19100))
                .expiryDate(expiryDate)
                .quantity(7)
                .limitPrice(BigDecimal.valueOf(15))
                .build();

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.OPEN)
                .legs(List.of(shortPutLeg, longPutLeg))
                .entryCredit(BigDecimal.valueOf(10.0))
                .currentValue(BigDecimal.valueOf(10.0))
                .unrealizedPnl(BigDecimal.ZERO)
                .entryTime(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .stopDistancePoints(null)
                .targetThreshold(null)
                .build();
    }
}
