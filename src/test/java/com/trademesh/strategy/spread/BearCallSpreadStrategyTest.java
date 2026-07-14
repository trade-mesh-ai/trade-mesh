package com.trademesh.strategy.spread;

import com.trademesh.intelligence.MarketMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for BearCallSpreadStrategy.calculateConfidenceScore() with trend factor.
 * Direction-specific: Bear Call rewards NEGATIVE trend (opposite of Bull Put).
 */
class BearCallSpreadStrategyTest {

    private final BearCallSpreadStrategy strategy = new BearCallSpreadStrategy(null, null, null, null, null);

    /**
     * Test: All 5 factors at maximum.
     * IVR: 30, IV/HV: 20, ATR%: 20, PCR: 15, Trend: 15 = 100 points
     * Trend: < -1.0 (e.g., -2.0%)
     */
    @Test
    void testCalculateConfidenceScore_AllFactorsMax() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))      // IVR 55 (in 50-65 range) -> 30 points
                .ivHvRatio(BigDecimal.valueOf(1.5)) // IV/HV 1.5 -> 20 points
                .atrPct(BigDecimal.valueOf(0.40))   // ATR% 0.40 (in 0.36-0.48 range) -> 20 points
                .pcr(BigDecimal.valueOf(1.5))       // PCR 1.5 (> 1.2) -> 15 points
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(-2.0); // Trend -2.0% (< -1.0) -> 15 points

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(100, score, "All factors at max should yield 100 points");
    }

    /**
     * Test: Bear Call rewards negative trend.
     * Trend < -1.0 -> +15 points
     */
    @Test
    void testCalculateConfidenceScore_BearCallNegativeTrendMax() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(0.5))
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(-2.0); // < -1.0

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(15, score, "Negative trend < -1.0 should yield 15 points");
    }

    /**
     * Test: Bear Call rewards moderate negative trend.
     * Trend -1.0 to 0.0 -> +8 points
     */
    @Test
    void testCalculateConfidenceScore_BearCallModerateNegativeTrend() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(0.5))
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(-0.5); // -1.0 to 0.0

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(8, score, "Moderate negative trend (-1.0 to 0.0) should yield 8 points");
    }

    /**
     * Test: Bear Call penalizes positive trend.
     * Trend >= 0.0 -> 0 points
     */
    @Test
    void testCalculateConfidenceScore_BearCallPositiveTrendZero() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(0.5))
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(2.0); // >= 0.0

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(0, score, "Positive trend should yield 0 points for Bear Call");
    }

    /**
     * Test: Direction-specific scoring.
     * Bull Put rewards positive trend, Bear Call rewards negative trend.
     * Same metrics, opposite trend directions should yield opposite scores.
     * 
     * CRITICAL: PCR must be neutral for EACH strategy being tested:
     * - metricsForBullPutTest: pcr=1.5 (neutral for Bull Put, which needs pcr < 0.8 to score)
     * - metricsForBearCallTest: pcr=0.5 (neutral for Bear Call, which needs pcr > 1.2 to score)
     * This isolates trend's contribution without PCR interference.
     */
    @Test
    void testCalculateConfidenceScore_DirectionSpecificScoring() {
        // Arrange: Metrics for Bull Put (pcr=1.5 is neutral for Bull Put)
        MarketMetrics metricsForBullPutTest = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(1.5))  // >= 1.0 -> 0 points for Bull Put (neutral)
                .build();

        // Arrange: Metrics for Bear Call (pcr=0.5 is neutral for Bear Call)
        MarketMetrics metricsForBearCallTest = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(0.5))  // < 0.8 -> 0 points for Bear Call (neutral)
                .build();

        // Act: Positive trend (favorable for Bull Put)
        int scoreBullPositive = new BullPutSpreadStrategy(null, null, null, null, null)
                .calculateConfidenceScore(metricsForBullPutTest, BigDecimal.valueOf(2.0));

        // Act: Negative trend (favorable for Bear Call)
        int scoreBearNegative = strategy.calculateConfidenceScore(metricsForBearCallTest, BigDecimal.valueOf(-2.0));

        // Assert: Both should score 15 (max trend points)
        assertEquals(15, scoreBullPositive, "Bull Put should score 15 for positive trend");
        assertEquals(15, scoreBearNegative, "Bear Call should score 15 for negative trend");

        // Act: Opposite trends
        int scoreBullNegative = new BullPutSpreadStrategy(null, null, null, null, null)
                .calculateConfidenceScore(metricsForBullPutTest, BigDecimal.valueOf(-2.0));

        int scoreBearPositive = strategy.calculateConfidenceScore(metricsForBearCallTest, BigDecimal.valueOf(2.0));

        // Assert: Both should score 0 (unfavorable trend)
        assertEquals(0, scoreBullNegative, "Bull Put should score 0 for negative trend");
        assertEquals(0, scoreBearPositive, "Bear Call should score 0 for positive trend");
    }

    /**
     * Test: Null trend factor contributes 0 (no exception).
     */
    @Test
    void testCalculateConfidenceScore_NullTrendSafe() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(30.0))      // 20 points
                .ivHvRatio(BigDecimal.valueOf(1.5)) // 20 points
                .atrPct(BigDecimal.valueOf(0.35))   // 15 points
                .pcr(BigDecimal.valueOf(1.5))       // 15 points
                .build();
        BigDecimal trendPct = null; // Null trend

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(70, score, "Null trend should contribute 0, total 70 points");
    }

    /**
     * Test: PCR scoring for Bear Call (rewards high PCR).
     * PCR > 1.2 -> +15 points
     */
    @Test
    void testCalculateConfidenceScore_BearCallPCRMax() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(1.5)) // > 1.2
                .build();
        BigDecimal trendPct = null;

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(15, score, "PCR > 1.2 should yield 15 points for Bear Call");
    }
}
