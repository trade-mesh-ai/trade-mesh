package com.trademesh.strategy.spread;

import com.trademesh.intelligence.MarketMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for spread strategy confidence scoring.
 * Tests both BullPutSpreadStrategy and BearCallSpreadStrategy.
 * Verifies the 4-factor confidence scoring model (IVR, IV/HV, ATR%, PCR).
 * Max achievable score: 85 points (same as Iron Condor).
 */
class SpreadConfidenceScoreTest {

    private final BullPutSpreadStrategy bullPutStrategy = new BullPutSpreadStrategy(null, null, null, null, null);
    private final BearCallSpreadStrategy bearCallStrategy = new BearCallSpreadStrategy(null, null, null, null, null);

    // ═══════════════════════════════════════════════════════════════════════════════
    // BULL PUT SPREAD TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: Bull Put - All factors at best band.
     * Expected: 30 (IVR) + 20 (IV/HV) + 15 (PCR) = 65 points.
     * <p>
     * Best bands:
     * - IVR: 50-65 → +30
     * - IV/HV: >= 1.3 → +20
     * - PCR: < 0.8 → +15 (Bull Put rewards low PCR)
     */
    @Test
    void testBullPutConfidenceScore_AllFactorsAtBestBand_Returns65() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))      // in 50-65 range → +30
                .ivHvRatio(BigDecimal.valueOf(1.5)) // >= 1.3 → +20
                .pcr(BigDecimal.valueOf(0.7))       // < 0.8 → +15
                .build();

        int score = bullPutStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(65, score, "Bull Put: All factors at best band should yield max score of 65");
    }

    /**
     * Test 2: Bull Put - All factors at worst band.
     * Expected: 0 (no penalties for spreads, unlike IC's ATR% penalty).
     */
    @Test
    void testBullPutConfidenceScore_AllFactorsAtWorstBand_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(10.0))      // < 20 → 0
                .ivHvRatio(BigDecimal.valueOf(1.0)) // < 1.1 → 0
                .atrPct(BigDecimal.valueOf(0.8))    // >= 0.60 → 0 (no penalty for spreads)
                .pcr(BigDecimal.valueOf(1.5))       // >= 1.0 → 0 (Bull Put dislikes high PCR)
                .build();

        int score = bullPutStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(0, score, "Bull Put: All factors at worst band should yield 0");
    }

    /**
     * Test 3: Bull Put - PCR < 0.8 (favorable for Bull Put).
     * Expected: +15 for PCR.
     */
    @Test
    void testBullPutConfidenceScore_PCRLow_Returns15() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(0.7))       // < 0.8 → +15
                .build();

        int score = bullPutStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(15, score, "Bull Put: PCR < 0.8 should yield +15");
    }

    /**
     * Test 4: Bull Put - PCR 0.8-1.0 (neutral for Bull Put).
     * Expected: +8 for PCR.
     */
    @Test
    void testBullPutConfidenceScore_PCRNeutral_Returns8() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(0.9))       // in 0.8-1.0 range → +8
                .build();

        int score = bullPutStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(8, score, "Bull Put: PCR in 0.8-1.0 should yield +8");
    }

    /**
     * Test 5: Bull Put - PCR >= 1.0 (unfavorable for Bull Put).
     * Expected: 0 for PCR.
     */
    @Test
    void testBullPutConfidenceScore_PCRHigh_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(1.3))       // >= 1.0 → 0 (unfavorable)
                .build();

        int score = bullPutStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(0, score, "Bull Put: PCR >= 1.0 should yield 0");
    }

    /**
     * Test 6: Bull Put - PCR = 1.3 (Bear Call's favorable range).
     * Verify Bull Put does NOT reward this (no cross-contamination).
     * Expected: 0 for PCR.
     */
    @Test
    void testBullPutConfidenceScore_PCRBearCallFavorable_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(1.3))       // > 1.2 (Bear Call favorable) → 0 for Bull Put
                .build();

        int score = bullPutStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(0, score, "Bull Put: PCR = 1.3 (Bear Call favorable) should yield 0 for Bull Put");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // BEAR CALL SPREAD TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 8: Bear Call - All factors at best band.
     * Expected: 30 (IVR) + 20 (IV/HV) + 15 (PCR) = 65 points.
     * <p>
     * Best bands:
     * - IVR: 50-65 → +30
     * - IV/HV: >= 1.3 → +20
     * - PCR: > 1.2 → +15 (Bear Call rewards high PCR)
     */
    @Test
    void testBearCallConfidenceScore_AllFactorsAtBestBand_Returns65() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))      // in 50-65 range → +30
                .ivHvRatio(BigDecimal.valueOf(1.5)) // >= 1.3 → +20
                .pcr(BigDecimal.valueOf(1.3))       // > 1.2 → +15
                .build();

        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(65, score, "Bear Call: All factors at best band should yield max score of 65");
    }

    /**
     * Test 8b: Bear Call - Score 50 boundary test.
     * Expected: 30 (IVR) + 20 (IV/HV) + 0 (PCR) = 50 points.
     */
    @Test
    void testBearCallConfidenceScore_Score50_CorrectBoundary() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))      // in 50-65 range → +30
                .ivHvRatio(BigDecimal.valueOf(1.5)) // >= 1.3 → +20
                .pcr(null)                          // 0
                .build();

        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);
        assertEquals(50, score, "Score 50 should be achievable (30+20)");
    }

    /**
     * Test 9: Bear Call - All factors at worst band.
     * Expected: 0 (no penalties for spreads).
     */
    @Test
    void testBearCallConfidenceScore_AllFactorsAtWorstBand_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(10.0))      // < 20 → 0
                .ivHvRatio(BigDecimal.valueOf(1.0)) // < 1.1 → 0
                .atrPct(BigDecimal.valueOf(0.8))    // >= 0.60 → 0
                .pcr(BigDecimal.valueOf(0.5))       // <= 1.0 → 0 (Bear Call dislikes low PCR)
                .build();

        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(0, score, "Bear Call: All factors at worst band should yield 0");
    }

    /**
     * Test 10: Bear Call - PCR > 1.2 (favorable for Bear Call).
     * Expected: +15 for PCR.
     */
    @Test
    void testBearCallConfidenceScore_PCRHigh_Returns15() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(1.3))       // > 1.2 → +15
                .build();

        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(15, score, "Bear Call: PCR > 1.2 should yield +15");
    }

    /**
     * Test 11: Bear Call - PCR 1.0-1.2 (neutral for Bear Call).
     * Expected: +8 for PCR.
     */
    @Test
    void testBearCallConfidenceScore_PCRNeutral_Returns8() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(1.1))       // in 1.0-1.2 range → +8
                .build();

        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(8, score, "Bear Call: PCR in 1.0-1.2 should yield +8");
    }

    /**
     * Test 12: Bear Call - PCR <= 1.0 (unfavorable for Bear Call).
     * Expected: 0 for PCR.
     */
    @Test
    void testBearCallConfidenceScore_PCRLow_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(0.8))       // <= 1.0 → 0 (unfavorable)
                .build();

        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(0, score, "Bear Call: PCR <= 1.0 should yield 0");
    }

    /**
     * Test 13: Bear Call - PCR = 0.7 (Bull Put's favorable range).
     * Verify Bear Call does NOT reward this (no cross-contamination).
     * Expected: 0 for PCR.
     */
    @Test
    void testBearCallConfidenceScore_PCRBullPutFavorable_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(BigDecimal.valueOf(0.7))       // < 0.8 (Bull Put favorable) → 0 for Bear Call
                .build();

        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(0, score, "Bear Call: PCR = 0.7 (Bull Put favorable) should yield 0 for Bear Call");
    }

    /**
     * Test 14: Both strategies - All null metrics.
     * Expected: 0 (no contributions).
     */
    @Test
    void testConfidenceScore_AllNullMetrics_Returns0() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(null)
                .ivHvRatio(null)
                .atrPct(null)
                .pcr(null)
                .build();

        int bullPutScore = bullPutStrategy.calculateConfidenceScore(metrics, null);
        int bearCallScore = bearCallStrategy.calculateConfidenceScore(metrics, null);

        assertEquals(0, bullPutScore, "Bull Put: All null metrics should yield 0");
        assertEquals(0, bearCallScore, "Bear Call: All null metrics should yield 0");
    }

    @Test
    void testBullPutConfidenceScore_KnownCombination_ProducesCorrectScore() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(0.7))
                .build();
        int score = bullPutStrategy.calculateConfidenceScore(metrics, null);
        System.out.println("DEBUG: SpreadConfidenceScoreTest.testBullPutConfidenceScore_KnownCombination_ProducesCorrectScore() computed score = " + score);
        assertEquals(65, score, "Known combination (IVR=55, IVHV=1.5, PCR=0.7) produces score 65 for Bull Put Spread");
    }

    @Test
    void testBearCallConfidenceScore_KnownCombination_ProducesCorrectScore() {
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))
                .ivHvRatio(BigDecimal.valueOf(1.5))
                .pcr(BigDecimal.valueOf(1.3))
                .build();
        int score = bearCallStrategy.calculateConfidenceScore(metrics, null);
        System.out.println("DEBUG: SpreadConfidenceScoreTest.testBearCallConfidenceScore_KnownCombination_ProducesCorrectScore() computed score = " + score);
        assertEquals(65, score, "Known combination (IVR=55, IVHV=1.5, PCR=1.3) produces score 65 for Bear Call Spread");
    }
}
