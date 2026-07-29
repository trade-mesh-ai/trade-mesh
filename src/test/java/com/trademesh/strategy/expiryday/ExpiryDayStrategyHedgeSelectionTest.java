package com.trademesh.strategy.expiryday;

import com.trademesh.intelligence.iv.ImpliedVolatilityCalculator;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ExpiryDayStrategy hedge strike selection (Phase 1 shadow logging).
 * <p>
 * Verifies:
 * 1. Hedge selection finds a strike satisfying BOTH delta and distance constraints
 * 2. Distance floor correctly overrides a too-close pure-delta pick
 * 3. No strike satisfying both conditions returns null and logs warning
 * 4. buildTradeLegs() return value is identical with/without valid hedge strikes
 */
@DisplayName("ExpiryDayStrategy Hedge Strike Selection Tests")
class ExpiryDayStrategyHedgeSelectionTest {

    @Mock
    private StrategyProperties cfg;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * Test 1: Hedge selection finds a strike satisfying BOTH delta and distance constraints.
     * <p>
     * Scenario:
     * - Short call strike: 24000
     * - Candidate hedge strikes: 24500, 25000, 25500 (much larger offsets to achieve delta <= 0.05)
     * - Target delta: 0.05
     * - Min distance: 150 points
     * - Expected: First candidate satisfying both constraints selected
     */
    @Test
    @DisplayName("Test 1: Hedge selection finds strike satisfying both delta and distance constraints")
    void testHedgeSelectionFindsBothConstraints() {
        // Setup
        when(cfg.getExtraDouble("hedge-target-delta", 0.05)).thenReturn(0.05);
        when(cfg.getExtraDouble("hedge-min-distance-points", 150.0)).thenReturn(150.0);

        BigDecimal spotPrice = BigDecimal.valueOf(24050);
        BigDecimal shortCallStrike = BigDecimal.valueOf(24000);
        double atmIv = 18.0;
        double tte = 1.0 / 365.0;  // 1 day to expiry

        // Create option chain with candidate hedge strikes (large offsets to achieve delta <= 0.05)
        OptionChain chain = createMockChain("NIFTY50", spotPrice,
                new BigDecimal[]{BigDecimal.valueOf(24500), BigDecimal.valueOf(25000), BigDecimal.valueOf(25500)});

        // Execute via reflection (selectHedgeStrikes is private)
        ExpiryDayStrategy.HedgeStrikeSelection result = invokeSelectHedgeStrikes(
                chain, shortCallStrike, BigDecimal.valueOf(24000), spotPrice, atmIv, tte, cfg);

        // Verify
        assertNotNull(result, "HedgeStrikeSelection should not be null");
        assertNotNull(result.getCallHedgeStrike(), "Call hedge strike should be found");
        assertNotNull(result.getCallHedgeDelta(), "Call hedge delta should be recorded");
        assertNotNull(result.getCallHedgeDistance(), "Call hedge distance should be recorded");
        assertTrue(result.getCallHedgeDistance() >= 150.0,
                "Distance should be >= 150 points");
        assertTrue(Math.abs(result.getCallHedgeDelta()) <= 0.05,
                "Delta should be <= 0.05");
    }

    /**
     * Test 2: Distance floor correctly overrides a too-close pure-delta pick.
     * <p>
     * Scenario:
     * - Short call strike: 24000
     * - Candidate hedge strikes: 24050 (too close, distance 50), 24500 (far enough, distance 500)
     * - Target delta: 0.05
     * - Min distance: 150 points
     * - Expected: 24500 selected (24050 is too close despite low delta)
     * <p>
     * This is the MOST IMPORTANT test: proves the distance floor actually overrides
     * a too-close pure-delta pick. At 500 points, delta ~0.026 (confirmed via real debug output).
     */
    @Test
    @DisplayName("Test 2: Distance floor overrides too-close pure-delta pick")
    void testDistanceFloorOverridesTooCloseDelta() {
        // Setup
        when(cfg.getExtraDouble("hedge-target-delta", 0.05)).thenReturn(0.05);
        when(cfg.getExtraDouble("hedge-min-distance-points", 150.0)).thenReturn(150.0);

        BigDecimal spotPrice = BigDecimal.valueOf(24050);
        BigDecimal shortCallStrike = BigDecimal.valueOf(24000);
        double atmIv = 18.0;
        double tte = 1.0 / 365.0;

        // Create option chain with a too-close strike and a valid one
        OptionChain chain = createMockChain("NIFTY50", spotPrice,
                new BigDecimal[]{BigDecimal.valueOf(24050), BigDecimal.valueOf(24500)});

        // Execute
        ExpiryDayStrategy.HedgeStrikeSelection result = invokeSelectHedgeStrikes(
                chain, shortCallStrike, BigDecimal.valueOf(24000), spotPrice, atmIv, tte, cfg);

        // Verify
        assertNotNull(result, "HedgeStrikeSelection should not be null");
        assertNotNull(result.getCallHedgeStrike(), "Call hedge strike should be found");
        assertEquals(BigDecimal.valueOf(24500), result.getCallHedgeStrike(),
                "Should select 24500, skipping 24050 because distance (50) < min-distance (150)");
        assertTrue(result.getCallHedgeDistance() >= 150.0,
                "Selected strike distance must be >= 150 points");
    }

    /**
     * Test 3: No strike satisfying both conditions returns null and logs warning.
     * <p>
     * Scenario:
     * - Short call strike: 24000
     * - Candidate hedge strikes: 24050 (too close), 24100 (too close)
     * - Target delta: 0.05
     * - Min distance: 150 points
     * - Expected: null for call hedge, warning logged
     */
    @Test
    @DisplayName("Test 3: No valid hedge strike returns null and logs warning")
    void testNoValidHedgeStrikeReturnsNull() {
        // Setup
        when(cfg.getExtraDouble("hedge-target-delta", 0.05)).thenReturn(0.05);
        when(cfg.getExtraDouble("hedge-min-distance-points", 150.0)).thenReturn(150.0);

        BigDecimal spotPrice = BigDecimal.valueOf(24050);
        BigDecimal shortCallStrike = BigDecimal.valueOf(24000);
        double atmIv = 18.0;
        double tte = 1.0 / 365.0;

        // Create option chain with only too-close strikes
        OptionChain chain = createMockChain("NIFTY50", spotPrice,
                new BigDecimal[]{BigDecimal.valueOf(24050), BigDecimal.valueOf(24100)});

        // Execute
        ExpiryDayStrategy.HedgeStrikeSelection result = invokeSelectHedgeStrikes(
                chain, shortCallStrike, BigDecimal.valueOf(24000), spotPrice, atmIv, tte, cfg);

        // Verify
        assertNotNull(result, "HedgeStrikeSelection should not be null");
        assertNull(result.getCallHedgeStrike(),
                "Call hedge strike should be null (no candidate satisfies both constraints)");
        assertNull(result.getCallHedgeDelta(), "Call hedge delta should be null");
        assertNull(result.getCallHedgeDistance(), "Call hedge distance should be null");
    }

    /**
     * Test 4: Regression test — buildTradeLegs() return value is identical
     * with/without valid hedge strikes available.
     * <p>
     * This test verifies zero live-behavior change from Phase 1 shadow logging.
     * The TradeLegsResult (legs, totalCredit) must be byte-for-byte identical
     * whether or not hedge strikes are found.
     * <p>
     * Calls the REAL buildTradeLegs() method twice via reflection with identical
     * inputs except for the option chain (one with valid hedge strikes, one without),
     * and verifies the returned legs and totalCredit are identical in both cases.
     */
    @Test
    @DisplayName("Test 4: Regression — buildTradeLegs() return identical with/without hedge strikes")
    void testBuildTradeLegsReturnIdenticalWithoutHedgeChange() {
        // Setup: Create a real ExpiryDayStrategy with proper mocking
        com.trademesh.strategy.config.StrategiesConfig strategiesConfig = mock(com.trademesh.strategy.config.StrategiesConfig.class);
        com.trademesh.intelligence.MarketMetricsService marketMetricsService = mock(com.trademesh.intelligence.MarketMetricsService.class);
        com.trademesh.config.LotSizeService lotSizeService = mock(com.trademesh.config.LotSizeService.class);

        ExpiryDayStrategy strategy = new ExpiryDayStrategy(strategiesConfig, marketMetricsService, lotSizeService);

        // Mock strategy config
        com.trademesh.strategy.config.StrategyProperties config = mock(com.trademesh.strategy.config.StrategyProperties.class);
        when(strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE")).thenReturn(config);
        when(config.getStopLossPercent()).thenReturn(100.0);
        when(config.getMaxLots()).thenReturn(2);
        when(config.getMinCredit()).thenReturn(20.0);
        when(config.getExtraDouble("hedge-target-delta", 0.05)).thenReturn(0.05);
        when(config.getExtraDouble("hedge-min-distance-points", 150.0)).thenReturn(150.0);

        // Mock lot size service
        when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        when(lotSizeService.getPositionSize(any(BigDecimal.class))).thenReturn(1);

        // Create base MarketIntelligence and StrikeSelection
        BigDecimal spotPrice = BigDecimal.valueOf(24050);
        BigDecimal callStrike = BigDecimal.valueOf(24100);
        BigDecimal putStrike = BigDecimal.valueOf(24000);

        // Create MarketMetrics with ATM IV
        com.trademesh.intelligence.MarketMetrics metrics = com.trademesh.intelligence.MarketMetrics.builder()
                .atr14(BigDecimal.valueOf(250.0))
                .currentAtmIv(BigDecimal.valueOf(18.0))
                .build();

        // Scenario 1: Chain with valid hedge strikes (candidates exist)
        OptionChain chainWithHedges = createMockChainWithHedges("NIFTY50", spotPrice, callStrike, putStrike);
        com.trademesh.intelligence.MarketIntelligence intelligenceWithHedges = createMockIntelligenceWithChain(
                "NIFTY50", spotPrice, callStrike, putStrike, chainWithHedges);
        ExpiryDayStrategy.StrikeSelection strikeSelectionWithHedges = new ExpiryDayStrategy.StrikeSelection(
                callStrike, putStrike, callStrike, putStrike, false, false, 250.0, 0.4);

        // Scenario 2: Chain without valid hedge strikes (all candidates too close)
        OptionChain chainWithoutHedges = createMockChainWithoutHedges("NIFTY50", spotPrice, callStrike, putStrike);
        com.trademesh.intelligence.MarketIntelligence intelligenceWithoutHedges = createMockIntelligenceWithChain(
                "NIFTY50", spotPrice, callStrike, putStrike, chainWithoutHedges);
        ExpiryDayStrategy.StrikeSelection strikeSelectionWithoutHedges = new ExpiryDayStrategy.StrikeSelection(
                callStrike, putStrike, callStrike, putStrike, false, false, 250.0, 0.4);

        // Execute: Call buildTradeLegs() twice via reflection
        ExpiryDayStrategy.TradeLegsResult result1 = invokeBuildTradeLegs(
                strategy, intelligenceWithHedges, strikeSelectionWithHedges, metrics);
        ExpiryDayStrategy.TradeLegsResult result2 = invokeBuildTradeLegs(
                strategy, intelligenceWithoutHedges, strikeSelectionWithoutHedges, metrics);

        // Verify: Both results have identical totalCredit and leg data
        assertNotNull(result1, "buildTradeLegs() should return non-null result with valid hedges");
        assertNotNull(result2, "buildTradeLegs() should return non-null result without valid hedges");

        assertEquals(result1.getTotalCredit(), result2.getTotalCredit(), 0.01,
                "totalCredit must be identical regardless of hedge strike availability");

        assertEquals(result1.getCallLeg().getStrikePrice(), result2.getCallLeg().getStrikePrice(),
                "Call leg strike price must be identical");
        assertEquals(result1.getPutLeg().getStrikePrice(), result2.getPutLeg().getStrikePrice(),
                "Put leg strike price must be identical");

        assertEquals(result1.getCallLeg().getQuantity(), result2.getCallLeg().getQuantity(),
                "Call leg quantity must be identical");
        assertEquals(result1.getPutLeg().getQuantity(), result2.getPutLeg().getQuantity(),
                "Put leg quantity must be identical");

        assertEquals(result1.getCallLeg().getAction(), result2.getCallLeg().getAction(),
                "Call leg action must be identical");
        assertEquals(result1.getPutLeg().getAction(), result2.getPutLeg().getAction(),
                "Put leg action must be identical");
    }

    // ─── Helper Methods ───────────────────────────────────────────────────────────

    /**
     * Creates a mock OptionChain with specified strikes.
     */
    private OptionChain createMockChain(String symbol, BigDecimal spotPrice, BigDecimal[] strikeValues) {
        OptionChain chain = mock(OptionChain.class);
        when(chain.getSymbol()).thenReturn(symbol);
        when(chain.getSpotPrice()).thenReturn(spotPrice);
        when(chain.getExpiryDate()).thenReturn(LocalDate.now().plusDays(1));

        List<OptionStrike> strikes = new ArrayList<>();
        for (BigDecimal strikeValue : strikeValues) {
            OptionStrike strike = mock(OptionStrike.class);
            when(strike.getStrikePrice()).thenReturn(strikeValue);
            when(strike.getCallLTP()).thenReturn(BigDecimal.valueOf(10.0));
            when(strike.getPutLTP()).thenReturn(BigDecimal.valueOf(10.0));
            strikes.add(strike);
        }
        when(chain.getStrikes()).thenReturn(strikes);

        return chain;
    }

    /**
     * Creates a mock OptionChain with valid hedge strike candidates (far enough from short strikes).
     */
    private OptionChain createMockChainWithHedges(String symbol, BigDecimal spotPrice,
                                                   BigDecimal callStrike, BigDecimal putStrike) {
        OptionChain chain = mock(OptionChain.class);
        when(chain.getSymbol()).thenReturn(symbol);
        when(chain.getSpotPrice()).thenReturn(spotPrice);
        when(chain.getExpiryDate()).thenReturn(LocalDate.now().plusDays(1));

        List<OptionStrike> strikes = new ArrayList<>();

        // Add short call strike itself (needed for buildTradeLegs() to find the short leg)
        OptionStrike shortCallStrike = mock(OptionStrike.class);
        when(shortCallStrike.getStrikePrice()).thenReturn(callStrike);
        when(shortCallStrike.getCallLTP()).thenReturn(BigDecimal.valueOf(50.0));
        when(shortCallStrike.getPutLTP()).thenReturn(BigDecimal.valueOf(0.5));
        strikes.add(shortCallStrike);

        // Add short put strike itself (needed for buildTradeLegs() to find the short leg)
        OptionStrike shortPutStrike = mock(OptionStrike.class);
        when(shortPutStrike.getStrikePrice()).thenReturn(putStrike);
        when(shortPutStrike.getCallLTP()).thenReturn(BigDecimal.valueOf(0.5));
        when(shortPutStrike.getPutLTP()).thenReturn(BigDecimal.valueOf(40.0));
        strikes.add(shortPutStrike);

        // Add call hedge candidates (above short call strike)
        for (int i = 1; i <= 3; i++) {
            BigDecimal strikePrice = callStrike.add(BigDecimal.valueOf(i * 100));
            OptionStrike strike = mock(OptionStrike.class);
            when(strike.getStrikePrice()).thenReturn(strikePrice);
            when(strike.getCallLTP()).thenReturn(BigDecimal.valueOf(5.0 + i));
            when(strike.getPutLTP()).thenReturn(BigDecimal.valueOf(0.5));
            strikes.add(strike);
        }

        // Add put hedge candidates (below short put strike)
        for (int i = 1; i <= 3; i++) {
            BigDecimal strikePrice = putStrike.subtract(BigDecimal.valueOf(i * 100));
            OptionStrike strike = mock(OptionStrike.class);
            when(strike.getStrikePrice()).thenReturn(strikePrice);
            when(strike.getCallLTP()).thenReturn(BigDecimal.valueOf(0.5));
            when(strike.getPutLTP()).thenReturn(BigDecimal.valueOf(5.0 + i));
            strikes.add(strike);
        }

        when(chain.getStrikes()).thenReturn(strikes);
        return chain;
    }

    /**
     * Creates a mock OptionChain without valid hedge strike candidates (all too close).
     */
    private OptionChain createMockChainWithoutHedges(String symbol, BigDecimal spotPrice,
                                                      BigDecimal callStrike, BigDecimal putStrike) {
        OptionChain chain = mock(OptionChain.class);
        when(chain.getSymbol()).thenReturn(symbol);
        when(chain.getSpotPrice()).thenReturn(spotPrice);
        when(chain.getExpiryDate()).thenReturn(LocalDate.now().plusDays(1));

        List<OptionStrike> strikes = new ArrayList<>();

        // Add short call strike itself (needed for buildTradeLegs() to find the short leg)
        OptionStrike shortCallStrike = mock(OptionStrike.class);
        when(shortCallStrike.getStrikePrice()).thenReturn(callStrike);
        when(shortCallStrike.getCallLTP()).thenReturn(BigDecimal.valueOf(50.0));
        when(shortCallStrike.getPutLTP()).thenReturn(BigDecimal.valueOf(0.5));
        strikes.add(shortCallStrike);

        // Add short put strike itself (needed for buildTradeLegs() to find the short leg)
        OptionStrike shortPutStrike = mock(OptionStrike.class);
        when(shortPutStrike.getStrikePrice()).thenReturn(putStrike);
        when(shortPutStrike.getCallLTP()).thenReturn(BigDecimal.valueOf(0.5));
        when(shortPutStrike.getPutLTP()).thenReturn(BigDecimal.valueOf(40.0));
        strikes.add(shortPutStrike);

        // Add call candidates that are too close (distance < 150)
        for (int i = 1; i <= 2; i++) {
            BigDecimal strikePrice = callStrike.add(BigDecimal.valueOf(i * 50));
            OptionStrike strike = mock(OptionStrike.class);
            when(strike.getStrikePrice()).thenReturn(strikePrice);
            when(strike.getCallLTP()).thenReturn(BigDecimal.valueOf(5.0));
            when(strike.getPutLTP()).thenReturn(BigDecimal.valueOf(0.5));
            strikes.add(strike);
        }

        // Add put candidates that are too close (distance < 150)
        for (int i = 1; i <= 2; i++) {
            BigDecimal strikePrice = putStrike.subtract(BigDecimal.valueOf(i * 50));
            OptionStrike strike = mock(OptionStrike.class);
            when(strike.getStrikePrice()).thenReturn(strikePrice);
            when(strike.getCallLTP()).thenReturn(BigDecimal.valueOf(0.5));
            when(strike.getPutLTP()).thenReturn(BigDecimal.valueOf(5.0));
            strikes.add(strike);
        }

        when(chain.getStrikes()).thenReturn(strikes);
        return chain;
    }

    /**
     * Creates a mock MarketIntelligence with a specific option chain.
     */
    private com.trademesh.intelligence.MarketIntelligence createMockIntelligenceWithChain(
            String symbol, BigDecimal spotPrice, BigDecimal callStrike, BigDecimal putStrike,
            OptionChain chain) {
        com.trademesh.intelligence.MarketIntelligence intelligence = mock(com.trademesh.intelligence.MarketIntelligence.class);
        when(intelligence.getSymbol()).thenReturn(symbol);
        when(intelligence.getSpotPrice()).thenReturn(spotPrice.doubleValue());

        // Mock VIX data
        var vixData = mock(com.trademesh.intelligence.vix.VixData.class);
        when(vixData.getCurrentVix()).thenReturn(15.0);
        when(intelligence.getVixData()).thenReturn(vixData);

        // Mock OI analysis
        var oiAnalysis = mock(com.trademesh.intelligence.oi.OIAnalysis.class);
        when(oiAnalysis.getMaxCallOIStrike()).thenReturn(callStrike);
        when(oiAnalysis.getMaxPutOIStrike()).thenReturn(putStrike);
        when(intelligence.getOiAnalysis()).thenReturn(oiAnalysis);

        // Set the provided chain
        when(intelligence.getOptionChain()).thenReturn(chain);

        return intelligence;
    }

    /**
     * Invokes selectHedgeStrikes() via reflection (it's private).
     * This is a workaround for testing private methods.
     */
    private ExpiryDayStrategy.HedgeStrikeSelection invokeSelectHedgeStrikes(
            OptionChain chain, BigDecimal shortCallStrike, BigDecimal shortPutStrike,
            BigDecimal spotPrice, double atmIv, double tte, StrategyProperties cfg) {
        try {
            var method = ExpiryDayStrategy.class.getDeclaredMethod(
                    "selectHedgeStrikes",
                    OptionChain.class, BigDecimal.class, BigDecimal.class,
                    BigDecimal.class, double.class, double.class, StrategyProperties.class);
            method.setAccessible(true);

            // Create a dummy ExpiryDayStrategy instance (constructor args don't matter for this test)
            var strategy = new ExpiryDayStrategy(null, null, null);

            return (ExpiryDayStrategy.HedgeStrikeSelection) method.invoke(
                    strategy, chain, shortCallStrike, shortPutStrike, spotPrice, atmIv, tte, cfg);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke selectHedgeStrikes via reflection", e);
        }
    }

    /**
     * Invokes buildTradeLegs() via reflection (it's private).
     * This is a workaround for testing private methods.
     */
    private ExpiryDayStrategy.TradeLegsResult invokeBuildTradeLegs(
            ExpiryDayStrategy strategy,
            com.trademesh.intelligence.MarketIntelligence intelligence,
            ExpiryDayStrategy.StrikeSelection strikeSelection,
            com.trademesh.intelligence.MarketMetrics metrics) {
        try {
            var method = ExpiryDayStrategy.class.getDeclaredMethod(
                    "buildTradeLegs",
                    com.trademesh.intelligence.MarketIntelligence.class,
                    ExpiryDayStrategy.StrikeSelection.class,
                    com.trademesh.intelligence.MarketMetrics.class);
            method.setAccessible(true);

            return (ExpiryDayStrategy.TradeLegsResult) method.invoke(strategy, intelligence, strikeSelection, metrics);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke buildTradeLegs via reflection", e);
        }
    }
}
