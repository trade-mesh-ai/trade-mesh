package com.trademesh.strategy.ironcondor;

import com.trademesh.intelligence.MarketMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for IronCondorStrategy.calculateConfidenceScore().
 * Verifies the 4-factor confidence scoring model (IVR, IV/HV, ATR%, PCR).
 * Max achievable score: 85 points (after VIX removal).
 */
class IronCondorConfidenceScoreTest {

    private final IronCondorStrategy strategy = new IronCondorStrategy(null, null, null, null, null, null);

    /**
     * Test 1: All four factors at their best band.
     * Expected: 30 (IVR) + 20 (IV/HV) + 20 (ATR%) + 15 (PCR) = 85 points (new max).
     *
     * Best bands:
     * - IVR: 50-65 → +30
     * - IV/HV: >= 1.3 → +20
     * - ATR%: 0.6-0.8 → +20 (highest band)
     * - PCR: 0.8-1.2 → +15
     */
    @Test
    void testConfidenceScore_AllFactorsAtBestBand_Returns85() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))      // in 50-65 range → +30
                .ivHvRatio(BigDecimal.valueOf(1.5)) // >= 1.3 → +20
                .atrPct(BigDecimal.valueOf(0.7))    // in 0.6-0.8 range → +20
                .pcr(BigDecimal.valueOf(1.0))       // in 0.8-1.2 range → +15
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(85, score, "All factors at best band should yield max score of 85");
    }

    /**
     * Test 2: All four factors at their worst/zero-scoring band.
     * Expected: -20 points (ATR% penalty).
     *
     * Worst bands:
     * - IVR: < 20 or > 80 → 0
     * - IV/HV: < 1.1 → 0
     * - ATR%: >= 1.0 → -20 (penalty)
     * - PCR: < 0.8 or > 1.2 → 0
     */
    @Test
    void testConfidenceScore_AllFactorsAtWorstBand_ReturnsNegative20() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(10.0))      // < 20 → 0
                .ivHvRatio(BigDecimal.valueOf(1.0)) // < 1.1 → 0
                .atrPct(BigDecimal.valueOf(1.2))    // >= 1.0 → -20 (penalty)
                .pcr(BigDecimal.valueOf(0.5))       // < 0.8 → 0
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(-20, score, "All factors at worst band should yield -20 (ATR% penalty)");
    }

    /**
     * Test 3: Mid-range case with hand-verifiable expected score.
     * Expected: 20 (IVR) + 10 (IV/HV) + 15 (ATR%) + 0 (PCR) = 45 points.
     *
     * Mid-range bands:
     * - IVR: 20-50 → +20
     * - IV/HV: 1.1-1.3 → +10
     * - ATR%: < 0.6 → +15
     * - PCR: 0.5 (outside 0.8-1.2) → 0
     */
    @Test
    void testConfidenceScore_MidRangeCase_Returns45() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(35.0))      // in 20-50 range → +20
                .ivHvRatio(BigDecimal.valueOf(1.2)) // in 1.1-1.3 range → +10
                .atrPct(BigDecimal.valueOf(0.5))    // < 0.6 → +15
                .pcr(BigDecimal.valueOf(0.5))       // < 0.8 (outside balanced) → 0
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(45, score, "Mid-range case should yield 45 points");
    }

    /**
     * Test 4: Verify IVR best band (50-65) yields +30, not +20.
     * This confirms the highest IVR band is 50-65 (not 20-50).
     */
    @Test
    void testConfidenceScore_IVRBestBand50to65_Returns30() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(60.0))      // in 50-65 range → +30
                .ivHvRatio(null)                    // no contribution
                .atrPct(null)                       // no contribution
                .pcr(null)                          // no contribution
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(30, score, "IVR in 50-65 range should yield +30");
    }

    /**
     * Test 5: Verify ATR% best band (0.6-0.8) yields +20, not +15.
     * This confirms the highest ATR% band is 0.6-0.8 (not <0.6).
     */
    @Test
    void testConfidenceScore_ATRBestBand0p6to0p8_Returns20() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)                          // no contribution
                .ivHvRatio(null)                    // no contribution
                .atrPct(BigDecimal.valueOf(0.7))    // in 0.6-0.8 range → +20
                .pcr(null)                          // no contribution
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(20, score, "ATR% in 0.6-0.8 range should yield +20");
    }

     /**
      * Test 6: Null metrics should not crash; missing factors contribute 0.
      * Expected: 0 (all null).
      */
     @Test
     void testConfidenceScore_AllNullMetrics_Returns0() {
         MarketMetrics metrics = MarketMetrics.builder()
                 .ivr(null)
                 .ivHvRatio(null)
                 .atrPct(null)
                 .pcr(null)
                 .build();

         int score = strategy.calculateConfidenceScore(metrics);

         assertEquals(0, score, "All null metrics should yield 0");
     }

      @Test
      void testConfidenceScore_KnownCombination_ProducesCorrectScore() {
          MarketMetrics metrics = MarketMetrics.builder()
                  .ivr(BigDecimal.valueOf(55.0))
                  .ivHvRatio(BigDecimal.valueOf(1.5))
                  .atrPct(BigDecimal.valueOf(0.5))
                  .pcr(null)
                  .build();
          int score = strategy.calculateConfidenceScore(metrics);
          assertEquals(65, score, "Known combination (IVR=55, IVHV=1.5, ATR%=0.5, PCR=null) produces score 65");
      }
}
