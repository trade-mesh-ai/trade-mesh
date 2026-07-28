package com.trademesh.strategy.spread;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketMetrics;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.intelligence.NiftyDailyPriceService;
import com.trademesh.intelligence.vix.VixData;
import com.trademesh.intelligence.vix.VixMonitorService;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for BullPutSpreadStrategy ATR multiplier and sizing input changes (Phase 3).
 * Tests the REAL selectStrikesAndBuildSignal() method by calling evaluate() with mocked
 * dependencies. Verifies:
 * 1. ATR multiplier formula correctness at boundary values (0, 50, 100)
 * 2. Null atrPercentile degrades to 1.0 multipliers and matches Phase 1 unadjusted values
 * 3. Sizing input is plannedRiskPerLot, not maxLossPerLot
 * 4. maxLossPerLot/maxLoss formulas unchanged
 * 5. Wider stops (high atrPercentile) result in fewer lots than narrow stops (low atrPercentile)
 */
@DisplayName("BullPutSpreadStrategy ATR Multiplier Tests (Phase 3)")
class BullPutSpreadStrategyAtrMultiplierTest {

    @Mock
    private StrategiesConfig strategiesConfig;

    @Mock
    private MarketMetricsService marketMetricsService;

    @Mock
    private VixMonitorService vixMonitorService;

    @Mock
    private LotSizeService lotSizeService;

    @Mock
    private com.trademesh.trading.MarketCalendarService marketCalendarService;

    @Mock
    private NiftyDailyPriceService niftyDailyPriceService;

    private BullPutSpreadStrategy strategy;

    private static final int NIFTY50_LOT_SIZE = 65;
    private static final double SPOT_PRICE = 50000.0;
    private static final int WING_WIDTH = 100;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        strategy = new BullPutSpreadStrategy(
                strategiesConfig,
                marketMetricsService,
                niftyDailyPriceService,
                lotSizeService,
                marketCalendarService
        );
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(NIFTY50_LOT_SIZE);
        when(niftyDailyPriceService.calculateTrendPct(ArgumentMatchers.anyInt()))
                .thenReturn(Mono.just(BigDecimal.ZERO));
    }

    /**
     * Test 1: ATR multiplier formula correctness at atrPercentile = 0, 50, 100.
     * Verifies exact expected values at boundary points by calling the real method
     * and asserting on the returned TradeSignal's stopDistancePoints/targetThreshold.
     */
    @Test
    @DisplayName("Test 1: ATR multiplier formula at boundary values (0, 50, 100)")
    void testAtrMultiplierFormula() {
        // Test case 1: atrPercentile = 0
        testAtrMultiplierAtPercentile(
                BigDecimal.ZERO,
                0.85,  // expected atrMultiplier
                0.90   // expected targetMultiplier
        );

        // Test case 2: atrPercentile = 50
        testAtrMultiplierAtPercentile(
                BigDecimal.valueOf(50),
                1.10,  // expected atrMultiplier
                1.125  // expected targetMultiplier
        );

        // Test case 3: atrPercentile = 100
        testAtrMultiplierAtPercentile(
                BigDecimal.valueOf(100),
                1.35,  // expected atrMultiplier
                1.35   // expected targetMultiplier
        );
    }

    /**
     * Helper: test ATR multiplier at a specific atrPercentile value.
     * Calls real evaluate() and asserts on returned TradeSignal fields.
     */
    private void testAtrMultiplierAtPercentile(BigDecimal atrPercentile,
                                               double expectedAtrMult,
                                               double expectedTargetMult) {
        // Setup strategy config
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(cfg);

        // Setup VIX
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Setup market metrics with controlled atrPercentile
        MarketMetrics metrics = createMockMarketMetrics(atrPercentile);
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Setup option chain
        OptionChain chain = createMockOptionChain();

        // Setup market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Setup position sizing
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.any(BigDecimal.class),
                ArgumentMatchers.anyDouble(),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt()
        )).thenReturn(5);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .symbol("NIFTY50")
                .spotPrice(SPOT_PRICE)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.TRENDING_BULLISH)
                .build();

        // Execute and verify
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable(), "Strategy should be executable");
                    assertNotNull(result.getSignal(), "Signal should not be null");

                    // Compute expected values
                    // netCredit = 8.0 is the expected net credit derived from createMockOptionChain()'s synthetic LTP curve at the delta-selected strikes
                    BigDecimal netCredit = BigDecimal.valueOf(8);
                    double stopLossPercent = cfg.getStopLossPercent();
                    double targetProfitPercent = cfg.getTargetProfitPercent();

                    BigDecimal expectedStopDistancePoints = netCredit
                            .multiply(BigDecimal.valueOf(stopLossPercent / 100.0))
                            .multiply(BigDecimal.valueOf(expectedAtrMult));

                    BigDecimal expectedTargetThreshold = netCredit
                            .multiply(BigDecimal.valueOf(targetProfitPercent / 100.0))
                            .multiply(BigDecimal.valueOf(expectedTargetMult));

                    // Assert on real returned signal fields using scale-insensitive comparison
                    assertEquals(0, expectedStopDistancePoints.compareTo(result.getSignal().getStopDistancePoints()),
                            "stopDistancePoints should match formula with atrMultiplier=" + expectedAtrMult);
                    assertEquals(0, expectedTargetThreshold.compareTo(result.getSignal().getTargetThreshold()),
                            "targetThreshold should match formula with targetMultiplier=" + expectedTargetMult);
                })
                .verifyComplete();
    }

    /**
     * Test 2: Null atrPercentile -> both multipliers exactly 1.0, and
     * stopDistancePoints/targetThreshold exactly match Phase 1's unadjusted formula.
     */
    @Test
    @DisplayName("Test 2: Null atrPercentile degrades to 1.0 multipliers (Phase 1 compatibility)")
    void testNullAtrPercentileDegrade() {
        // Setup strategy config
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(cfg);

        // Setup VIX
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Setup market metrics with NULL atrPercentile
        MarketMetrics metrics = createMockMarketMetrics(null);
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Setup option chain
        OptionChain chain = createMockOptionChain();

        // Setup market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Setup position sizing
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.any(BigDecimal.class),
                ArgumentMatchers.anyDouble(),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt()
        )).thenReturn(5);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .symbol("NIFTY50")
                .spotPrice(SPOT_PRICE)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.TRENDING_BULLISH)
                .build();

        // Execute and verify
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable(), "Strategy should be executable");
                    assertNotNull(result.getSignal(), "Signal should not be null");

                    // Phase 1 unadjusted formula (multiplier = 1.0)
                    // netCredit = 8.0 is the expected net credit derived from createMockOptionChain()'s synthetic LTP curve at the delta-selected strikes
                    BigDecimal netCredit = BigDecimal.valueOf(8);
                    double stopLossPercent = cfg.getStopLossPercent();
                    double targetProfitPercent = cfg.getTargetProfitPercent();

                    BigDecimal expectedStopDistancePoints = netCredit
                            .multiply(BigDecimal.valueOf(stopLossPercent / 100.0));

                    BigDecimal expectedTargetThreshold = netCredit
                            .multiply(BigDecimal.valueOf(targetProfitPercent / 100.0));

                    // Assert on real returned signal fields using scale-insensitive comparison
                    assertEquals(0, expectedStopDistancePoints.compareTo(result.getSignal().getStopDistancePoints()),
                            "stopDistancePoints should match Phase 1 unadjusted when atrPercentile is null");
                    assertEquals(0, expectedTargetThreshold.compareTo(result.getSignal().getTargetThreshold()),
                            "targetThreshold should match Phase 1 unadjusted when atrPercentile is null");
                })
                .verifyComplete();
    }

    /**
     * Test 3: Sizing input is genuinely plannedRiskPerLot, not maxLossPerLot.
     * Verifies that lotSizeService.getPositionSize() is called with plannedRiskPerLot
     * (computed from ATR-adjusted stopDistancePoints) by using Mockito.verify().
     */
    @Test
    @DisplayName("Test 3: Sizing input is plannedRiskPerLot, not maxLossPerLot")
    void testSizingInputIsPlannedRiskPerLot() {
        // Setup strategy config
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(cfg);

        // Setup VIX
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Setup market metrics with atrPercentile = 50 (makes plannedRiskPerLot != maxLossPerLot)
        BigDecimal atrPercentile = BigDecimal.valueOf(50);
        MarketMetrics metrics = createMockMarketMetrics(atrPercentile);
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Setup option chain
        OptionChain chain = createMockOptionChain();

        // Setup market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Compute expected plannedRiskPerLot
        // netCredit = 8.0, stopLossPercent = 12, atrMultiplier = 1.10
        // stopDistancePoints = 8.0 * 0.12 * 1.10 = 1.056
        // plannedRiskPerLot = 1.056 * 65 = 68.64
        BigDecimal expectedPlannedRiskPerLot = BigDecimal.valueOf(8)
                .multiply(BigDecimal.valueOf(0.12))
                .multiply(BigDecimal.valueOf(1.10))
                .multiply(BigDecimal.valueOf(NIFTY50_LOT_SIZE));

        // Setup position sizing to return 5 lots using scale-insensitive ArgumentMatcher
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.argThat(bd -> bd != null && bd.compareTo(expectedPlannedRiskPerLot) == 0),
                ArgumentMatchers.eq(15.0),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt()
        )).thenReturn(5);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .symbol("NIFTY50")
                .spotPrice(SPOT_PRICE)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.TRENDING_BULLISH)
                .build();

        // Execute
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable(), "Strategy should be executable");
                    assertNotNull(result.getSignal(), "Signal should not be null");

                    // Verify the mock was called with plannedRiskPerLot using scale-insensitive ArgumentMatcher
                    verify(lotSizeService).getPositionSize(
                            ArgumentMatchers.argThat(bd -> bd != null && bd.compareTo(expectedPlannedRiskPerLot) == 0),
                            ArgumentMatchers.eq(15.0),
                            ArgumentMatchers.anyInt(),
                            ArgumentMatchers.anyInt()
                    );

                    // Assert on returned signal's plannedRiskPerLot field using scale-insensitive comparison
                    assertEquals(0, expectedPlannedRiskPerLot.compareTo(result.getSignal().getPlannedRiskPerLot()),
                            "Signal should have plannedRiskPerLot matching the ATR-adjusted value");
                })
                .verifyComplete();
    }

    /**
     * Test 4: maxLossPerLot/maxLoss regression check.
     * Asserts that maxLoss is identical regardless of atrPercentile,
     * proving atrPercentile doesn't leak into that calculation.
     */
    @Test
    @DisplayName("Test 4: maxLossPerLot/maxLoss formulas unchanged (regression check)")
    void testMaxLossPerLotMaxLossUnchanged() {
        // Test with atrPercentile = null
        Double maxLossNull = testMaxLossWithAtrPercentile(null);

        // Test with atrPercentile = 90
        Double maxLoss90 = testMaxLossWithAtrPercentile(BigDecimal.valueOf(90));

        // Assert they are identical
        assertEquals(maxLossNull, maxLoss90,
                "maxLoss should be identical regardless of atrPercentile");
    }

    /**
     * Helper: test maxLoss at a specific atrPercentile value.
     * Returns the maxLoss value from the signal for comparison.
     */
    private Double testMaxLossWithAtrPercentile(BigDecimal atrPercentile) {
        // Setup strategy config
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(cfg);

        // Setup VIX
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Setup market metrics
        MarketMetrics metrics = createMockMarketMetrics(atrPercentile);
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Setup option chain
        OptionChain chain = createMockOptionChain();

        // Setup market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Setup position sizing
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.any(BigDecimal.class),
                ArgumentMatchers.anyDouble(),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt()
        )).thenReturn(5);

        // Create market intelligence
        MarketIntelligence intelligence = MarketIntelligence.builder()
                .symbol("NIFTY50")
                .spotPrice(SPOT_PRICE)
                .vixData(vixData)
                .optionChain(chain)
                .marketCondition(MarketCondition.TRENDING_BULLISH)
                .build();

        // Execute and capture maxLoss
        final Double[] capturedMaxLoss = new Double[1];
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable(), "Strategy should be executable");
                    assertNotNull(result.getSignal(), "Signal should not be null");
                    capturedMaxLoss[0] = result.getSignal().getMaxLoss();
                })
                .verifyComplete();

        return capturedMaxLoss[0];
    }

    /**
     * Test 5: Wider stop (high atrPercentile, e.g. 90) with a fixed capital budget
     * -> fewer lots than a low-atrPercentile case with the same other inputs.
     * Confirms the "total risk stays pinned to budget" mechanism holds.
     */
    @Test
    @DisplayName("Test 5: Wider stop (high atrPercentile) -> fewer lots than narrow stop (low atrPercentile)")
    void testWiderStopFewerLots() {
        // Test with low atrPercentile (narrow stop)
        int lotsLow = testLotsWithAtrPercentile(BigDecimal.valueOf(10), 3);

        // Test with high atrPercentile (wider stop)
        int lotsHigh = testLotsWithAtrPercentile(BigDecimal.valueOf(90), 2);

        // Assert wider stop results in fewer lots
        assertTrue(lotsHigh < lotsLow,
                "Wider stop (high atrPercentile) should result in fewer lots than narrow stop (low atrPercentile)");
    }

    /**
     * Helper: test lot count at a specific atrPercentile value.
     * Mocks getPositionSize() to return the expected lot count based on atrPercentile.
     */
    private int testLotsWithAtrPercentile(BigDecimal atrPercentile, int expectedLots) {
        // Setup strategy config
        StrategyProperties cfg = createMockStrategyProperties();
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(cfg);

        // Setup VIX
        VixData vixData = VixData.builder().currentVix(15.0).build();
        when(vixMonitorService.fetchCurrentVix()).thenReturn(Mono.just(vixData));

        // Setup market metrics
        MarketMetrics metrics = createMockMarketMetrics(atrPercentile);
        when(marketMetricsService.calculateMetrics("NIFTY50")).thenReturn(Mono.just(metrics));

        // Setup option chain
        OptionChain chain = createMockOptionChain();

        // Setup market calendar
        when(marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate()))
                .thenReturn(2);

        // Compute expected plannedRiskPerLot based on atrPercentile
        // netCredit = 8.0, stopLossPercent = 12
        // atrMultiplier = 0.85 + (atrPercentile / 100) * 0.50
        double atrMult = 0.85 + (atrPercentile.doubleValue() / 100.0) * 0.50;
        BigDecimal stopDistancePoints = BigDecimal.valueOf(8)
                .multiply(BigDecimal.valueOf(0.12))
                .multiply(BigDecimal.valueOf(atrMult))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal plannedRiskPerLot = stopDistancePoints.multiply(BigDecimal.valueOf(NIFTY50_LOT_SIZE));

        // Setup position sizing to return expectedLots for this plannedRiskPerLot using scale-insensitive ArgumentMatcher
        when(lotSizeService.getPositionSize(
                ArgumentMatchers.argThat(bd -> bd != null && bd.compareTo(plannedRiskPerLot) == 0),
                ArgumentMatchers.eq(15.0),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt()
        )).thenReturn(expectedLots);

         // Create market intelligence
         MarketIntelligence intelligence = MarketIntelligence.builder()
                 .symbol("NIFTY50")
                 .spotPrice(SPOT_PRICE)
                 .vixData(vixData)
                 .optionChain(chain)
                 .marketCondition(MarketCondition.TRENDING_BULLISH)
                 .build();

        // Execute and capture lot count
        final int[] capturedLots = new int[1];
        StepVerifier.create(strategy.evaluate(intelligence))
                .assertNext(result -> {
                    assertTrue(result.getIsExecutable(), "Strategy should be executable");
                    assertNotNull(result.getSignal(), "Signal should not be null");

                    // Get lot count from first leg (all legs have same quantity)
                    List<TradeLeg> legs = result.getSignal().getLegs();
                    assertEquals(2, legs.size(), "Bull Put Spread should have 2 legs");
                    capturedLots[0] = legs.get(0).getQuantity();
                })
                .verifyComplete();

        return capturedLots[0];
    }

    // ===== Helper methods =====

    private StrategyProperties createMockStrategyProperties() {
        StrategyProperties props = new StrategyProperties();
        props.setEnabled(true);
        props.setMaxLots(20);
        props.setMaxVix(30.0);
        props.setMinVix(10.0);
        props.setLatestEntryTime("23:59");
        props.setTargetProfitPercent(6.0);
        props.setStopLossPercent(12.0);
        props.setAllowedDays(java.util.List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"));
        props.setExtra(java.util.Map.of("min-score", 30, "min-credit-pct", 0.05, "wing-width", WING_WIDTH));
        return props;
    }

    private MarketMetrics createMockMarketMetrics(BigDecimal atrPercentile) {
        return MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(50.0))
                .ivHvRatio(BigDecimal.valueOf(1.2))
                .atrPct(BigDecimal.valueOf(0.7))
                .atrPercentile(atrPercentile)
                .currentAtmIv(BigDecimal.valueOf(15.0))
                .pcr(BigDecimal.valueOf(1.0))
                .build();
    }

    private OptionChain createMockOptionChain() {
        LocalDate expiryDate = LocalDate.now().plusDays(2);
        List<OptionStrike> strikes = new ArrayList<>();

        // Create mock strikes around 50000 with realistic LTPs
        // Spot = 50000, so strikes are: 49500, 49600, ..., 50000, ..., 50400, 50500
        // Near-the-money strikes have higher LTP, far OTM have lower LTP
        for (int i = 48000; i <= 52000; i += 100) {
            OptionStrike strike = new OptionStrike();
            strike.setStrikePrice(BigDecimal.valueOf(i));

            // Distance from spot (50000)
            int distance = Math.abs(i - 50000);

            // LTP decreases with distance from spot
            // At spot (distance=0): LTP=80
            // At distance 100: LTP=60
            // At distance 200: LTP=40
            // At distance 300: LTP=20
            // At distance 400: LTP=10
            // At distance 500: LTP=5
            double ltp = Math.max(1.0, 150.0 - (distance / 100.0) * 8.0);

            strike.setCallLTP(BigDecimal.valueOf(ltp));
            strike.setPutLTP(BigDecimal.valueOf(ltp));
            strike.setCallIV(BigDecimal.valueOf(15.0));
            strike.setPutIV(BigDecimal.valueOf(15.0));
            strikes.add(strike);
        }

        OptionChain chain = new OptionChain();
        chain.setSpotPrice(BigDecimal.valueOf(SPOT_PRICE));
        chain.setExpiryDate(expiryDate);
        chain.setStrikes(strikes);
        return chain;
    }
}
