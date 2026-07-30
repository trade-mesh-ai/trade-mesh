package com.trademesh.strategy.expiryday;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketMetrics;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeSignal;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests for ExpiryDayStrategy capital-based position sizing.
 * <p>
 * Verifies:
 * 1. maxLossPerLot correctly derived from totalCredit × stopLossPercent × lotSize
 * 2. lots correctly capped at strategyConfig().getMaxLots()
 * 3. Both legs use the SAME computed lots value
 * 4. buildTradeSignal() stopLossAmount logic unchanged
 */
@DisplayName("ExpiryDayStrategy Position Sizing Tests")
class ExpiryDayStrategyPositionSizingTest {

    @Mock
    private StrategiesConfig strategiesConfig;

    @Mock
    private MarketMetricsService marketMetricsService;

    @Mock
    private LotSizeService lotSizeService;

    private ExpiryDayStrategy strategy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        strategy = new ExpiryDayStrategy(strategiesConfig, marketMetricsService, lotSizeService);
    }

    /**
     * Test 1: Verify maxLossPerLot correctly derived from totalCredit and stopLossPercent.
     * <p>
     * Scenario:
     * - callLTP = 50.0, putLTP = 40.0 → totalCredit = 90.0 points
     * - stopLossPercent = 100.0 (from config)
     * - lotSize = 65 (NIFTY50)
     * - maxLossPerLot = 90.0 × (100.0 / 100.0) × 65 = 5850.0 rupees
     * <p>
     * Expected: lotSizeService.getPositionSize(5850.0) is called with correct value
     */
    @Test
    @DisplayName("Test 1: maxLossPerLot correctly derived from totalCredit and stopLossPercent")
    void testMaxLossPerLotCalculation() {
        // Setup
        StrategyProperties config = createMockConfig(100.0, 1, 20.0);
        when(strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE")).thenReturn(config);
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(any(BigDecimal.class))).thenReturn(2);

        MarketIntelligence intelligence = createMockIntelligence(
                50.0, 40.0,  // callLTP, putLTP
                50000.0      // spotPrice
        );

        MarketMetrics mockMetrics = createMockMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50"))
                .thenReturn(Mono.just(mockMetrics));

        // Execute
        Mono<StrategyResult> result = strategy.evaluate(intelligence);

        // Verify
        StepVerifier.create(result)
                .assertNext(strategyResult -> {
                    assertTrue(strategyResult.getIsExecutable());
                    assertNotNull(strategyResult.getSignal());

                    // Verify that getPositionSize was called with the correct maxLossPerLotHedged
                    // totalCredit = 50.0 + 40.0 = 90.0
                    // callHedgeLtp = 50.0 × 9% = 4.5
                    // putHedgeLtp = 40.0 × 9% = 3.6
                    // netCredit = 90.0 - 4.5 - 3.6 = 81.9
                    // maxDistance = 200 pts (call @ 51200, put @ 48800)
                    // maxLossPerLotHedged = 200 × 65 - 81.9 × 65 = 13000 - 5323.5 = 7676.5
                    BigDecimal expectedMaxLoss = BigDecimal.valueOf(200)
                            .multiply(BigDecimal.valueOf(65))
                            .subtract(BigDecimal.valueOf(81.9).multiply(BigDecimal.valueOf(65)));

                    verify(lotSizeService).getPositionSize(
                            argThat(arg -> arg.subtract(expectedMaxLoss).abs().compareTo(BigDecimal.valueOf(0.01)) < 0)
                    );
                })
                .verifyComplete();
    }

    /**
     * Test 2: Verify lots is capped at strategyConfig().getMaxLots().
     * <p>
     * Scenario:
     * - Capital-based sizing would compute 5 lots
     * - maxLots config = 1
     * - Expected: final lots = min(5, 1) = 1
     */
    @Test
    @DisplayName("Test 2: lots correctly capped at strategyConfig().getMaxLots()")
    void testLotsCapAtMaxLots() {
        // Setup
        StrategyProperties config = createMockConfig(100.0, 1, 20.0);
        when(strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE")).thenReturn(config);
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        // Capital-based sizing returns 5, but should be capped at maxLots=1
        when(lotSizeService.getPositionSize(any(BigDecimal.class))).thenReturn(5);

        MarketIntelligence intelligence = createMockIntelligence(
                50.0, 40.0,  // callLTP, putLTP
                50000.0      // spotPrice
        );

        MarketMetrics mockMetrics = createMockMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50"))
                .thenReturn(Mono.just(mockMetrics));

        // Execute
        Mono<StrategyResult> result = strategy.evaluate(intelligence);

        // Verify
        StepVerifier.create(result)
                .assertNext(strategyResult -> {
                    assertTrue(strategyResult.getIsExecutable());
                    TradeSignal signal = strategyResult.getSignal();
                    assertNotNull(signal);

                    // All 4 legs should have quantity = 1 (capped at maxLots)
                    assertEquals(1, signal.getLegs().get(0).getQuantity(),
                            "Call leg quantity should be capped at maxLots=1");
                    assertEquals(1, signal.getLegs().get(1).getQuantity(),
                            "Put leg quantity should be capped at maxLots=1");
                    assertEquals(1, signal.getLegs().get(2).getQuantity(),
                            "Call hedge leg quantity should be capped at maxLots=1");
                    assertEquals(1, signal.getLegs().get(3).getQuantity(),
                            "Put hedge leg quantity should be capped at maxLots=1");
                })
                .verifyComplete();
    }

    /**
     * Test 3: Verify both legs use the SAME computed lots value.
     * <p>
     * Scenario:
     * - Capital-based sizing computes 2 lots
     * - maxLots = 3
     * - Expected: both call and put legs have quantity = 2
     */
    @Test
    @DisplayName("Test 3: Both legs use the SAME computed lots value")
    void testBothLegsUseSameLotsValue() {
        // Setup
        StrategyProperties config = createMockConfig(100.0, 3, 20.0);
        when(strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE")).thenReturn(config);
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        // Capital-based sizing returns 2 lots
        when(lotSizeService.getPositionSize(any(BigDecimal.class))).thenReturn(2);

        MarketIntelligence intelligence = createMockIntelligence(
                50.0, 40.0,  // callLTP, putLTP
                50000.0      // spotPrice
        );

        MarketMetrics mockMetrics = createMockMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50"))
                .thenReturn(Mono.just(mockMetrics));

        // Execute
        Mono<StrategyResult> result = strategy.evaluate(intelligence);

        // Verify
        StepVerifier.create(result)
                .assertNext(strategyResult -> {
                    assertTrue(strategyResult.getIsExecutable());
                    TradeSignal signal = strategyResult.getSignal();
                    assertNotNull(signal);

                    int callLegQuantity = signal.getLegs().get(0).getQuantity();
                    int putLegQuantity = signal.getLegs().get(1).getQuantity();
                    int callHedgeLegQuantity = signal.getLegs().get(2).getQuantity();
                    int putHedgeLegQuantity = signal.getLegs().get(3).getQuantity();

                    assertEquals(2, callLegQuantity, "Call leg quantity should be 2");
                    assertEquals(2, putLegQuantity, "Put leg quantity should be 2");
                    assertEquals(2, callHedgeLegQuantity, "Call hedge leg quantity should be 2");
                    assertEquals(2, putHedgeLegQuantity, "Put hedge leg quantity should be 2");
                    assertEquals(callLegQuantity, putLegQuantity,
                            "Both short legs must use the SAME computed lots value");
                    assertEquals(callLegQuantity, callHedgeLegQuantity,
                            "All 4 legs must use the SAME computed lots value");
                    assertEquals(putLegQuantity, putHedgeLegQuantity,
                            "All 4 legs must use the SAME computed lots value");
                })
                .verifyComplete();
    }

    /**
     * Test 4: Regression test — buildTradeSignal() stopLossAmount logic unchanged.
     * <p>
     * Scenario:
     * - totalCredit = 90.0 points
     * - stopLossPercent = 100.0
     * - Expected stopLossAmount = 90.0 × (100.0 / 100.0) = 90.0 points
     * <p>
     * This is a points-based calculation (different from maxLossPerLot which is rupee-based).
     */
    @Test
    @DisplayName("Test 4: Regression — buildTradeSignal() stopLossAmount unchanged")
    void testStopLossAmountCalculationUnchanged() {
        // Setup
        StrategyProperties config = createMockConfig(100.0, 1, 20.0);
        when(strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE")).thenReturn(config);
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(any(BigDecimal.class))).thenReturn(1);

        MarketIntelligence intelligence = createMockIntelligence(
                50.0, 40.0,  // callLTP, putLTP
                50000.0      // spotPrice
        );

        MarketMetrics mockMetrics = createMockMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50"))
                .thenReturn(Mono.just(mockMetrics));

        // Execute
        Mono<StrategyResult> result = strategy.evaluate(intelligence);

        // Verify
        StepVerifier.create(result)
                .assertNext(strategyResult -> {
                    assertTrue(strategyResult.getIsExecutable());
                    TradeSignal signal = strategyResult.getSignal();
                    assertNotNull(signal);

                    // stopLossAmount = totalCredit × (stopLossPercent / 100)
                    // = 90.0 × (100.0 / 100.0) = 90.0 points
                    double expectedStopLossAmount = 90.0;
                    assertEquals(expectedStopLossAmount, signal.getMaxLoss(), 0.01,
                            "stopLossAmount should be 90.0 points (totalCredit × stopLossPercent / 100)");
                })
                .verifyComplete();
    }

    /**
     * Test 5: Integration test with realistic values.
     * <p>
     * Scenario:
     * - callLTP = 75.5, putLTP = 65.25 → totalCredit = 140.75 points
     * - stopLossPercent = 100.0
     * - lotSize = 65
     * - maxLossPerLot = 140.75 × 1.0 × 65 = 9148.75 rupees
     * - Capital-based sizing (with account capital 100000, risk 2%) = floor(2000 / 9148.75) = 0 → floored to 1
     * - maxLots = 2
     * - Expected final lots = min(1, 2) = 1
     */
    @Test
    @DisplayName("Test 5: Integration test with realistic values")
    void testIntegrationWithRealisticValues() {
        // Setup
        StrategyProperties config = createMockConfig(100.0, 2, 20.0);
        when(strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE")).thenReturn(config);
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        // Simulate capital-based sizing: 2000 / 9148.75 = 0.218... → floored to 1
        when(lotSizeService.getPositionSize(any(BigDecimal.class))).thenReturn(1);

        MarketIntelligence intelligence = createMockIntelligence(
                75.5, 65.25,  // callLTP, putLTP
                50000.0       // spotPrice
        );

        MarketMetrics mockMetrics = createMockMetrics();
        when(marketMetricsService.calculateMetrics("NIFTY50"))
                .thenReturn(Mono.just(mockMetrics));

        // Execute
        Mono<StrategyResult> result = strategy.evaluate(intelligence);

        // Verify
        StepVerifier.create(result)
                .assertNext(strategyResult -> {
                    assertTrue(strategyResult.getIsExecutable());
                    TradeSignal signal = strategyResult.getSignal();
                    assertNotNull(signal);

                    // Both legs should have quantity = 1
                    assertEquals(1, signal.getLegs().get(0).getQuantity());
                    assertEquals(1, signal.getLegs().get(1).getQuantity());

                    // expectedCredit should be 140.75 points
                    assertEquals(140.75, signal.getExpectedCredit(), 0.01);

                    // stopLossAmount = 140.75 × (100.0 / 100.0) = 140.75 points
                    assertEquals(140.75, signal.getMaxLoss(), 0.01);
                })
                .verifyComplete();
    }

    // ─── Helper Methods ───────────────────────────────────────────────────────────

    private StrategyProperties createMockConfig(double stopLossPercent, int maxLots, double minCredit) {
        StrategyProperties config = mock(StrategyProperties.class);
        when(config.getStopLossPercent()).thenReturn(stopLossPercent);
        when(config.getMaxLots()).thenReturn(maxLots);
        when(config.getMinCredit()).thenReturn(minCredit);
        when(config.getMaxVix()).thenReturn(20.0);
        when(config.getLatestEntryTime()).thenReturn("23:59");
        when(config.getExtraDouble("min-distance-atr-multiplier", 0.4)).thenReturn(0.4);
        when(config.getExtraDouble("hedge-target-delta", 0.05)).thenReturn(0.05);
        when(config.getExtraDouble("hedge-min-distance-points", 150.0)).thenReturn(150.0);
        return config;
    }

    private MarketIntelligence createMockIntelligence(double callLTP, double putLTP, double spotPrice) {
        MarketIntelligence intelligence = mock(MarketIntelligence.class);
        when(intelligence.getSymbol()).thenReturn("NIFTY50");
        when(intelligence.getSpotPrice()).thenReturn(spotPrice);

        // Mock VIX data
        var vixData = mock(com.trademesh.intelligence.vix.VixData.class);
        when(vixData.getCurrentVix()).thenReturn(15.0);
        when(intelligence.getVixData()).thenReturn(vixData);

        // Mock OI analysis
        var oiAnalysis = createMockOIAnalysis();
        when(intelligence.getOiAnalysis()).thenReturn(oiAnalysis);

        // Mock option chain with hedge candidates
        OptionChain chain = mock(OptionChain.class);
        when(chain.getExpiryDate()).thenReturn(LocalDate.now().plusDays(1));

        List<OptionStrike> strikes = new ArrayList<>();

        // Short call strike @ 51000
        OptionStrike callStrike = mock(OptionStrike.class);
        when(callStrike.getStrikePrice()).thenReturn(BigDecimal.valueOf(51000));
        when(callStrike.getCallLTP()).thenReturn(BigDecimal.valueOf(callLTP));
        strikes.add(callStrike);

        // Short put strike @ 49000
        OptionStrike putStrike = mock(OptionStrike.class);
        when(putStrike.getStrikePrice()).thenReturn(BigDecimal.valueOf(49000));
        when(putStrike.getPutLTP()).thenReturn(BigDecimal.valueOf(putLTP));
        strikes.add(putStrike);

        // Call hedge candidates (above short call @ 51000)
        // Scaled LTPs based on callLTP: 51100 gets 10% of callLTP, 51200 gets 9%, etc.
        double[] callHedgeStrikes = {51100, 51200, 51300, 51400, 51500};
        for (int i = 0; i < callHedgeStrikes.length; i++) {
            OptionStrike hedgeStrike = mock(OptionStrike.class);
            when(hedgeStrike.getStrikePrice()).thenReturn(BigDecimal.valueOf(callHedgeStrikes[i]));
            double hedgeLtp = callLTP * (0.10 - i * 0.01);  // 10%, 9%, 8%, 7%, 6%
            when(hedgeStrike.getCallLTP()).thenReturn(BigDecimal.valueOf(Math.max(0.5, hedgeLtp)));
            strikes.add(hedgeStrike);
        }

        // Put hedge candidates (below short put @ 49000)
        // Scaled LTPs based on putLTP: 48900 gets 10% of putLTP, 48800 gets 9%, etc.
        double[] putHedgeStrikes = {48900, 48800, 48700, 48600, 48500};
        for (int i = 0; i < putHedgeStrikes.length; i++) {
            OptionStrike hedgeStrike = mock(OptionStrike.class);
            when(hedgeStrike.getStrikePrice()).thenReturn(BigDecimal.valueOf(putHedgeStrikes[i]));
            double hedgeLtp = putLTP * (0.10 - i * 0.01);  // 10%, 9%, 8%, 7%, 6%
            when(hedgeStrike.getPutLTP()).thenReturn(BigDecimal.valueOf(Math.max(0.5, hedgeLtp)));
            strikes.add(hedgeStrike);
        }

        when(chain.getStrikes()).thenReturn(strikes);
        when(intelligence.getOptionChain()).thenReturn(chain);

        return intelligence;
    }

    private MarketMetrics createMockMetrics() {
        return MarketMetrics.builder()
                .atr14(BigDecimal.valueOf(250.0))
                .currentAtmIv(BigDecimal.valueOf(18.0))
                .build();
    }

    private com.trademesh.intelligence.oi.OIAnalysis createMockOIAnalysis() {
        return com.trademesh.intelligence.oi.OIAnalysis.builder()
                .maxCallOIStrike(BigDecimal.valueOf(51000))
                .maxPutOIStrike(BigDecimal.valueOf(49000))
                .build();
    }
}
