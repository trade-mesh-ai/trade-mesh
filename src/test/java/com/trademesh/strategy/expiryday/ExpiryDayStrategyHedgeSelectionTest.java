package com.trademesh.strategy.expiryday;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.intelligence.iv.ImpliedVolatilityCalculator;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
      * Test 4: buildTradeLegs() returns correct TradeLegsResult with all 4 legs.
      * <p>
      * Case A - Valid hedge scenario:
      * - Spot: 24050, short call: 24000, short put: 24000
      * - Candidate hedge strikes: 24500, 25000, 25500 (call side) and 23500, 23000, 22500 (put side)
      * - Config: hedge-target-delta=0.05, hedge-min-distance-points=150.0
      * - Expected: buildTradeLegs() returns non-null TradeLegsResult with all 4 legs present
      * <p>
      * Case B - No valid hedge scenario:
      * - Spot: 24050, short call: 24000, short put: 24000
      * - Candidate hedge strikes: 24050, 24100 (call side) and 24000, 23950 (put side) — all too close
      * - Config: hedge-target-delta=0.05, hedge-min-distance-points=150.0
      * - Expected: buildTradeLegs() returns null (no valid hedges found)
      */
     @Test
     @DisplayName("Test 4: buildTradeLegs() returns correct TradeLegsResult with all 4 legs")
     void testBuildTradeLegsWithAndWithoutValidHedges() {
         // Setup: Create a real ExpiryDayStrategy with proper mocking
         com.trademesh.strategy.config.StrategiesConfig strategiesConfig = mock(com.trademesh.strategy.config.StrategiesConfig.class);
         com.trademesh.intelligence.MarketMetricsService marketMetricsService = mock(com.trademesh.intelligence.MarketMetricsService.class);
         com.trademesh.config.LotSizeService lotSizeService = mock(com.trademesh.config.LotSizeService.class);

         // Fixed clock at 11:00 AM IST (before 15:30 market close)
         // Construction: LocalDateTime.of(2026, 8, 12, 11, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant()
         // This ensures LocalDateTime.now(clock) inside buildTradeLegs() reads as 11:00 IST
         Clock fixedClock = Clock.fixed(
                 LocalDateTime.of(2026, 8, 12, 11, 0)
                         .atZone(ZoneId.of("Asia/Kolkata"))
                         .toInstant(),
                 ZoneId.of("Asia/Kolkata")
         );
         ExpiryDayStrategy strategy = new ExpiryDayStrategy(strategiesConfig, marketMetricsService, lotSizeService, fixedClock);

         // Mock strategy config (matching ExpiryDayStrategyPositionSizingTest pattern)
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

         // Create MarketMetrics with ATM IV
         com.trademesh.intelligence.MarketMetrics metrics = com.trademesh.intelligence.MarketMetrics.builder()
                 .atr14(BigDecimal.valueOf(250.0))
                 .currentAtmIv(BigDecimal.valueOf(18.0))
                 .build();

         // ─── Case A: Valid hedge scenario ───────────────────────────────────────
         BigDecimal spotPrice = BigDecimal.valueOf(24050);
         BigDecimal shortCallStrike = BigDecimal.valueOf(24000);
         BigDecimal shortPutStrike = BigDecimal.valueOf(24000);

         // Create option chain with valid hedge candidates
         // Call side: 24500, 25000, 25500 (distances: 500, 1000, 1500 — all >= 150)
         // Put side: 23500, 23000, 22500 (distances: 500, 1000, 1500 — all >= 150)
         OptionChain chainWithValidHedges = createMockChainWithValidHedges(
                 "NIFTY50", spotPrice, shortCallStrike, shortPutStrike);
         com.trademesh.intelligence.MarketIntelligence intelligenceWithValidHedges = createMockIntelligenceWithChain(
                 "NIFTY50", spotPrice, shortCallStrike, shortPutStrike, chainWithValidHedges);
         ExpiryDayStrategy.StrikeSelection strikeSelectionWithValidHedges = new ExpiryDayStrategy.StrikeSelection(
                 shortCallStrike, shortPutStrike, shortCallStrike, shortPutStrike, false, false, 250.0, 0.4);

         // Execute Case A
         ExpiryDayStrategy.TradeLegsResult resultA = invokeBuildTradeLegs(
                 strategy, intelligenceWithValidHedges, strikeSelectionWithValidHedges, metrics);

         // Verify Case A: Non-null result with all 4 legs present
         assertNotNull(resultA, "Case A: buildTradeLegs() should return non-null result with valid hedges");
         assertNotNull(resultA.getCallLeg(), "Case A: Call leg should be present");
         assertNotNull(resultA.getPutLeg(), "Case A: Put leg should be present");
         assertNotNull(resultA.getCallHedgeLeg(), "Case A: Call hedge leg should be present");
         assertNotNull(resultA.getPutHedgeLeg(), "Case A: Put hedge leg should be present");
         assertEquals(4, countNonNullLegs(resultA), "Case A: All 4 legs must be present");

         // ─── Case B: No valid hedge scenario ───────────────────────────────────────
         // Create option chain with only too-close candidates
         // Call side: 24050, 24100 (distances: 50, 100 — both < 150)
         // Put side: 24000, 23950 (distances: 0, 50 — both < 150)
         OptionChain chainWithoutValidHedges = createMockChainWithoutValidHedges(
                 "NIFTY50", spotPrice, shortCallStrike, shortPutStrike);
         com.trademesh.intelligence.MarketIntelligence intelligenceWithoutValidHedges = createMockIntelligenceWithChain(
                 "NIFTY50", spotPrice, shortCallStrike, shortPutStrike, chainWithoutValidHedges);
         ExpiryDayStrategy.StrikeSelection strikeSelectionWithoutValidHedges = new ExpiryDayStrategy.StrikeSelection(
                 shortCallStrike, shortPutStrike, shortCallStrike, shortPutStrike, false, false, 250.0, 0.4);

         // Execute Case B
         ExpiryDayStrategy.TradeLegsResult resultB = invokeBuildTradeLegs(
                 strategy, intelligenceWithoutValidHedges, strikeSelectionWithoutValidHedges, metrics);

         // Verify Case B: Null result (no valid hedges found)
         assertNull(resultB, "Case B: buildTradeLegs() should return null when no valid hedges found");
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
     * Creates a mock OptionChain with valid hedge strike candidates (far enough from short strikes).
     * Used for Test 4 Case A: spot=24050, short call/put=24000
     * Call side: 24500, 25000, 25500 (distances: 500, 1000, 1500 — all >= 150)
     * Put side: 23500, 23000, 22500 (distances: 500, 1000, 1500 — all >= 150)
     */
    private OptionChain createMockChainWithValidHedges(String symbol, BigDecimal spotPrice,
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
        // 24500, 25000, 25500 (distances: 500, 1000, 1500 — all >= 150)
        for (int i = 1; i <= 3; i++) {
            BigDecimal strikePrice = callStrike.add(BigDecimal.valueOf(i * 500));
            OptionStrike strike = mock(OptionStrike.class);
            when(strike.getStrikePrice()).thenReturn(strikePrice);
            when(strike.getCallLTP()).thenReturn(BigDecimal.valueOf(5.0 + i));
            when(strike.getPutLTP()).thenReturn(BigDecimal.valueOf(0.5));
            strikes.add(strike);
        }

        // Add put hedge candidates (below short put strike)
        // 23500, 23000, 22500 (distances: 500, 1000, 1500 — all >= 150)
        for (int i = 1; i <= 3; i++) {
            BigDecimal strikePrice = putStrike.subtract(BigDecimal.valueOf(i * 500));
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
     * Used for Test 4 Case B: spot=24050, short call/put=24000
     * Call side: 24050, 24100 (distances: 50, 100 — both < 150)
     * Put side: 24000, 23950 (distances: 0, 50 — both < 150)
     */
    private OptionChain createMockChainWithoutValidHedges(String symbol, BigDecimal spotPrice,
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
        // 24050, 24100 (distances: 50, 100 — both < 150)
        strikes.add(createMockStrike(BigDecimal.valueOf(24050), BigDecimal.valueOf(5.0), BigDecimal.valueOf(0.5)));
        strikes.add(createMockStrike(BigDecimal.valueOf(24100), BigDecimal.valueOf(5.0), BigDecimal.valueOf(0.5)));

        // Add put candidates that are too close (distance < 150)
        // 24000, 23950 (distances: 0, 50 — both < 150)
        strikes.add(createMockStrike(BigDecimal.valueOf(24000), BigDecimal.valueOf(0.5), BigDecimal.valueOf(5.0)));
        strikes.add(createMockStrike(BigDecimal.valueOf(23950), BigDecimal.valueOf(0.5), BigDecimal.valueOf(5.0)));

        when(chain.getStrikes()).thenReturn(strikes);
        return chain;
    }

    /**
     * Helper to create a mock OptionStrike with given strike price and LTPs.
     */
    private OptionStrike createMockStrike(BigDecimal strikePrice, BigDecimal callLtp, BigDecimal putLtp) {
        OptionStrike strike = mock(OptionStrike.class);
        when(strike.getStrikePrice()).thenReturn(strikePrice);
        when(strike.getCallLTP()).thenReturn(callLtp);
        when(strike.getPutLTP()).thenReturn(putLtp);
        return strike;
    }

    /**
     * Counts the number of non-null legs in a TradeLegsResult.
     */
    private int countNonNullLegs(ExpiryDayStrategy.TradeLegsResult result) {
        int count = 0;
        if (result.getCallLeg() != null) count++;
        if (result.getPutLeg() != null) count++;
        if (result.getCallHedgeLeg() != null) count++;
        if (result.getPutHedgeLeg() != null) count++;
        return count;
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

            // Create a dummy ExpiryDayStrategy instance with a fixed clock
            // Fixed clock at 11:00 AM IST (before 15:30 market close)
            // Construction: LocalDateTime.of(2026, 8, 12, 11, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant()
            // This ensures LocalDateTime.now(clock) inside buildTradeLegs() reads as 11:00 IST
            Clock fixedClock = Clock.fixed(
                    LocalDateTime.of(2026, 8, 12, 11, 0)
                            .atZone(ZoneId.of("Asia/Kolkata"))
                            .toInstant(),
                    ZoneId.of("Asia/Kolkata")
            );
            var strategy = new ExpiryDayStrategy(null, null, null, fixedClock);

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
