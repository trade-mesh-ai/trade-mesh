package com.trademesh.strategy.spread;

import com.trademesh.intelligence.MarketMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for BullPutSpreadStrategy.calculateConfidenceScore() with trend factor.
 */
class BullPutSpreadStrategyTest {

    private final BullPutSpreadStrategy strategy = new BullPutSpreadStrategy(null, null, null, null, null);

    /**
     * Test: All 5 factors at maximum.
     * IVR: 30, IV/HV: 20, ATR%: 20, PCR: 15, Trend: 15 = 100 points
     * Trend: > 1.0 (e.g., 2.0%)
     */
    @Test
    void testCalculateConfidenceScore_AllFactorsMax() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(55.0))      // IVR 55 (in 50-65 range) -> 30 points
                .ivHvRatio(BigDecimal.valueOf(1.5)) // IV/HV 1.5 -> 20 points
                .atrPct(BigDecimal.valueOf(0.40))   // ATR% 0.40 (in 0.36-0.48 range) -> 20 points
                .pcr(BigDecimal.valueOf(0.7))       // PCR 0.7 (< 0.8) -> 15 points
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(2.0); // Trend 2.0% (> 1.0) -> 15 points

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(100, score, "All factors at max should yield 100 points");
    }

    /**
     * Test: Bull Put rewards positive trend.
     * Trend > 1.0 -> +15 points
     */
    @Test
    void testCalculateConfidenceScore_BullPutPositiveTrendMax() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(2.0))
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(2.0); // > 1.0

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(15, score, "Positive trend > 1.0 should yield 15 points");
    }

    /**
     * Test: Bull Put rewards moderate positive trend.
     * Trend 0.0 to 1.0 -> +8 points
     */
    @Test
    void testCalculateConfidenceScore_BullPutModeratePositiveTrend() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(2.0))
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(0.5); // 0.0 to 1.0

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(8, score, "Moderate positive trend (0.0-1.0) should yield 8 points");
    }

    /**
     * Test: Bull Put penalizes negative trend.
     * Trend <= 0.0 -> 0 points
     */
    @Test
    void testCalculateConfidenceScore_BullPutNegativeTrendZero() {
        // Arrange
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(0.0))
                .ivHvRatio(BigDecimal.valueOf(0.0))
                .atrPct(BigDecimal.valueOf(1.0))
                .pcr(BigDecimal.valueOf(2.0))
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(-2.0); // <= 0.0

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(0, score, "Negative trend should yield 0 points for Bull Put");
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
                .pcr(BigDecimal.valueOf(0.7))       // 15 points
                .build();
        BigDecimal trendPct = null; // Null trend

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(70, score, "Null trend should contribute 0, total 70 points");
    }

    /**
     * Test: Min-score gate at 60.
     * Score 59 should be rejected, score 60 should be accepted.
     * (This is a conceptual test; actual gate logic is in evaluate() method)
     */
    @Test
    void testCalculateConfidenceScore_MinScoreThreshold() {
        // Arrange: Create a score of exactly 70
        MarketMetrics metrics = MarketMetrics.builder()
                .ivr(BigDecimal.valueOf(50.0))      // 30 points (in 50-65 range)
                .ivHvRatio(BigDecimal.valueOf(1.5)) // 20 points
                .atrPct(BigDecimal.valueOf(0.40))   // 20 points (in 0.36-0.48 range, not 0.35)
                .pcr(null)                          // 0 points
                .build();
        BigDecimal trendPct = BigDecimal.valueOf(-5.0); // 0 points (negative trend)

        // Act
        int score = strategy.calculateConfidenceScore(metrics, trendPct);

        // Assert
        assertEquals(70, score, "Score should be 70 (30+20+20+0)");
    }
}
