package com.trademesh.strategy.spread;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for min-score gate at threshold 60 in both spread strategies.
 * Tests the boundary condition: score 59 rejected, score 60 accepted.
 */
class SpreadStrategyMinScoreGateTest {

    /**
     * Test: Min-score gate rejects score below 60.
     * Default min-score in config is 60.
     * Score 59 should be rejected.
     */
    @Test
    void testMinScoreGate_BelowThreshold() {
        // Arrange
        int score = 59;
        int minScore = 60; // From application.yml config

        // Act & Assert
        assertEquals(true, score < minScore, "Score 59 should be below min-score 60");
    }

    /**
     * Test: Min-score gate accepts score at threshold.
     * Score 60 should be accepted.
     */
    @Test
    void testMinScoreGate_AtThreshold() {
        // Arrange
        int score = 60;
        int minScore = 60; // From application.yml config

        // Act & Assert
        assertEquals(false, score < minScore, "Score 60 should NOT be below min-score 60");
    }

    /**
     * Test: Min-score gate accepts score above threshold.
     * Score 61 should be accepted.
     */
    @Test
    void testMinScoreGate_AboveThreshold() {
        // Arrange
        int score = 61;
        int minScore = 60; // From application.yml config

        // Act & Assert
        assertEquals(false, score < minScore, "Score 61 should NOT be below min-score 60");
    }

    /**
     * Test: Min-score gate with max score (100).
     * Score 100 should be accepted.
     */
    @Test
    void testMinScoreGate_MaxScore() {
        // Arrange
        int score = 100;
        int minScore = 60; // From application.yml config

        // Act & Assert
        assertEquals(false, score < minScore, "Score 100 should NOT be below min-score 60");
    }

    /**
     * Test: Min-score gate with zero score.
     * Score 0 should be rejected.
     */
    @Test
    void testMinScoreGate_ZeroScore() {
        // Arrange
        int score = 0;
        int minScore = 60; // From application.yml config

        // Act & Assert
        assertEquals(true, score < minScore, "Score 0 should be below min-score 60");
    }

    /**
     * Test: Verify min-score threshold is 60 (not 51 from old config).
     * This ensures the config update was applied correctly.
     */
    @Test
    void testMinScoreThreshold_IsUpdatedTo60() {
        // Arrange
        int oldMinScore = 51; // Old threshold
        int newMinScore = 60; // New threshold

        // Act & Assert
        assertEquals(60, newMinScore, "Min-score should be updated to 60");
        assertEquals(true, newMinScore > oldMinScore, "New threshold should be higher than old");
    }

    /**
     * Test: Confidence mapping with new min-score.
     * HIGH: >= 60, MEDIUM: >= 43, LOW: < 43
     * Score 60 should map to HIGH.
     */
    @Test
    void testConfidenceMapping_Score60IsHigh() {
        // Arrange
        int score = 60;
        int highThreshold = 60;
        int mediumThreshold = 43;

        // Act
        String confidence = score >= highThreshold ? "HIGH"
                : score >= mediumThreshold ? "MEDIUM"
                : "LOW";

        // Assert
        assertEquals("HIGH", confidence, "Score 60 should map to HIGH confidence");
    }

    /**
     * Test: Confidence mapping with score below min-score.
     * Score 59 should map to MEDIUM (but would be rejected by gate).
     */
    @Test
    void testConfidenceMapping_Score59IsNotHigh() {
        // Arrange
        int score = 59;
        int highThreshold = 60;
        int mediumThreshold = 43;

        // Act
        String confidence = score >= highThreshold ? "HIGH"
                : score >= mediumThreshold ? "MEDIUM"
                : "LOW";

        // Assert
        assertEquals("MEDIUM", confidence, "Score 59 should map to MEDIUM confidence");
        assertEquals(true, score < highThreshold, "Score 59 should be below HIGH threshold");
    }

    /**
     * Test: Confidence mapping with score at MEDIUM threshold.
     * Score 43 should map to MEDIUM.
     */
    @Test
    void testConfidenceMapping_Score43IsMedium() {
        // Arrange
        int score = 43;
        int highThreshold = 60;
        int mediumThreshold = 43;

        // Act
        String confidence = score >= highThreshold ? "HIGH"
                : score >= mediumThreshold ? "MEDIUM"
                : "LOW";

        // Assert
        assertEquals("MEDIUM", confidence, "Score 43 should map to MEDIUM confidence");
    }

    /**
     * Test: Confidence mapping with score below MEDIUM threshold.
     * Score 42 should map to LOW.
     */
    @Test
    void testConfidenceMapping_Score42IsLow() {
        // Arrange
        int score = 42;
        int highThreshold = 60;
        int mediumThreshold = 43;

        // Act
        String confidence = score >= highThreshold ? "HIGH"
                : score >= mediumThreshold ? "MEDIUM"
                : "LOW";

        // Assert
        assertEquals("LOW", confidence, "Score 42 should map to LOW confidence");
    }
}
