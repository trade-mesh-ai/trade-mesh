package com.trademesh.strategy.ironcondor;

import com.trademesh.intelligence.MarketMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for IronCondorStrategy.calculateConfidenceScore().
 * Verifies the 3-factor confidence scoring model (IVR, IV/HV, PCR).
 * Max achievable score: 65 points (ATR% removed).
 */
class IronCondorConfidenceScoreTest {

    private final IronCondorStrategy strategy = new IronCondorStrategy(null, null, null, null, null, null);

    /**
     * Test 1: All three factors at their best band.
     * Expected: 30 (IVR) + 20 (IV/HV) + 15 (PCR) = 65 points (new max).
     * <p>
     * Best bands:
     * - IVR: 50-65 → +30
     * - IV/HV: >= 1.3 → +20
     * - PCR: 0.8-1.2 → +15
     */
    @Test
    void testConfidenceScore_AllFactorsAtBestBand_Returns65() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))      // in 50-65 range → +30
                .ivHvRatio(BigDecimal.valueOf(1.5)) // >= 1.3 → +20
                .pcr(BigDecimal.valueOf(1.0))       // in 0.8-1.2 range → +15
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(65, score, "All factors at best band should yield max score of 65");
    }

    /**
     * Test 2: All three factors at their worst/zero-scoring band.
     * Expected: 0 points.
     * <p>
     * Worst bands:
     * - IVR: < 20 or > 80 → 0
     * - IV/HV: < 1.1 → 0
     * - PCR: < 0.8 or > 1.2 → 0
     */
    @Test
    void testConfidenceScore_AllFactorsAtWorstBand_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(10.0))      // < 20 → 0
                .ivHvRatio(BigDecimal.valueOf(1.0)) // < 1.1 → 0
                .pcr(BigDecimal.valueOf(0.5))       // < 0.8 → 0
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(0, score, "All factors at worst band should yield 0");
    }

    /**
     * Test 3: Mid-range case with hand-verifiable expected score.
     * Expected: 20 (IVR) + 10 (IV/HV) + 0 (PCR) = 30 points.
     * <p>
     * Mid-range bands:
     * - IVR: 20-50 → +20
     * - IV/HV: 1.1-1.3 → +10
     * - PCR: 0.5 (outside 0.8-1.2) → 0
     */
    @Test
    void testConfidenceScore_MidRangeCase_Returns30() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(35.0))      // in 20-50 range → +20
                .ivHvRatio(BigDecimal.valueOf(1.2)) // in 1.1-1.3 range → +10
                .pcr(BigDecimal.valueOf(0.5))       // < 0.8 (outside balanced) → 0
                .build();

        int score = strategy.calculateConfidenceScore(metrics);

        assertEquals(30, score, "Mid-range case should yield 30 points");
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
                .pcr(null)
                .build();
        int score = strategy.calculateConfidenceScore(metrics);
        assertEquals(50, score, "Known combination (IVR=55, IVHV=1.5, PCR=null) produces score 50");
    }
}
