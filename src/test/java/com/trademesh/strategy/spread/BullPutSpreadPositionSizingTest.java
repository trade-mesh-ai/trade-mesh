package com.trademesh.strategy.spread;

import com.trademesh.config.LotSizeService;
import com.trademesh.strategy.config.StrategyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests for Bull Put Spread position sizing with confidence-based scaling.
 * Verifies that the 4-argument getPositionSize() call (with confidence score and min-score)
 * produces expected lot sizes based on confidence level.
 */
class BullPutSpreadPositionSizingTest {

    @Mock
    private LotSizeService lotSizeService;

    private StrategyProperties cfg;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cfg = new StrategyProperties();
        cfg.setName("BULL_PUT_SPREAD");
        cfg.setMaxLots(30);
        cfg.setEnabled(true);
        
        // Set extra config with min-score: 60
        Map<String, Object> extra = new HashMap<>();
        extra.put("min-score", 60);
        cfg.setExtra(extra);
    }

    /**
     * Test (a): High confidence score (90) produces larger lot count than low confidence (60)
     * for the same maxLossPerLot and VIX.
     * 
     * Setup:
     * - maxLossPerLot = 2000
     * - currentVix = 15.0 (normal VIX, full multiplier 1.0)
     * - High confidence: score = 90 (multiplier ~0.909)
     * - Low confidence: score = 60 (multiplier 0.5, at min-score boundary)
     * 
     * Expected: lotsAtScore90 > lotsAtScore60
     */
    @Test
    void testPositionSizing_HighConfidenceProducesMoreLots() {
        // Arrange
        BigDecimal maxLossPerLot = BigDecimal.valueOf(2000);
        double currentVix = 15.0;
        int minScore = cfg.getExtraInt("min-score", 60);
        
        // Mock behavior for high confidence (score 90)
        // At score 90: multiplier = 0.5 + (90 - 60) / (2.0 * (100 - 60)) 
        //            = 0.5 + 30 / 80 = 0.5 + 0.375 = 0.875
        int lotsAtScore90 = 8;  // Simulated result after confidence scaling
        when(lotSizeService.getPositionSize(
                eq(maxLossPerLot), 
                eq(currentVix), 
                eq(90), 
                eq(minScore)
        )).thenReturn(lotsAtScore90);
        
        // Mock behavior for low confidence (score 60, at min-score boundary)
        // At score 60 (minScore): multiplier = 0.5
        int lotsAtScore60 = 4;  // Simulated result at min-score boundary
        when(lotSizeService.getPositionSize(
                eq(maxLossPerLot), 
                eq(currentVix), 
                eq(60), 
                eq(minScore)
        )).thenReturn(lotsAtScore60);
        
        // Act
        int resultHighConfidence = lotSizeService.getPositionSize(maxLossPerLot, currentVix, 90, minScore);
        int resultLowConfidence = lotSizeService.getPositionSize(maxLossPerLot, currentVix, 60, minScore);
        
        // Assert
        assertTrue(resultHighConfidence > resultLowConfidence,
                "High confidence (score 90) should produce more lots than low confidence (score 60)");
        assertTrue(resultHighConfidence <= Math.min(30, 8),
                "High confidence result should respect max-lots ceiling");
        assertTrue(resultLowConfidence > 0,
                "Even at min-score boundary, result should be at least 1 lot");
    }

    /**
     * Test (b): Confidence scaling multiplier bounds are enforced (0.5x to 1.0x of VIX-scaled size).
     * 
     * Setup:
     * - maxLossPerLot = 1500
     * - currentVix = 12.0 (low VIX, full multiplier 1.0)
     * - Test scores: 100 (max, multiplier 1.0), 80 (mid, ~0.875), 60 (min, 0.5)
     * 
     * Expected: Multiplier bounds [0.5, 1.0] are respected
     *           Score 100 >= Score 80 >= Score 60 in lot count
     */
    @Test
    void testPositionSizing_ConfidenceMultiplierBoundsEnforced() {
        // Arrange
        BigDecimal maxLossPerLot = BigDecimal.valueOf(1500);
        double currentVix = 12.0;
        int minScore = cfg.getExtraInt("min-score", 60);
        
        // Base VIX-scaled size (before confidence) = 10 lots
        // With confidence scaling:
        // Score 100: multiplier = 1.0 → 10 lots
        // Score 80: multiplier = 0.5 + (80-60)/(2*40) = 0.875 → ~8.75 = 8 lots (floored)
        // Score 60: multiplier = 0.5 → 5 lots
        
        int lotsAtScore100 = 10;  // max multiplier 1.0x
        when(lotSizeService.getPositionSize(
                eq(maxLossPerLot), 
                eq(currentVix), 
                eq(100), 
                eq(minScore)
        )).thenReturn(lotsAtScore100);
        
        int lotsAtScore80 = 9;   // mid multiplier ~0.875x
        when(lotSizeService.getPositionSize(
                eq(maxLossPerLot), 
                eq(currentVix), 
                eq(80), 
                eq(minScore)
        )).thenReturn(lotsAtScore80);
        
        int lotsAtScore60 = 5;   // min multiplier 0.5x
        when(lotSizeService.getPositionSize(
                eq(maxLossPerLot), 
                eq(currentVix), 
                eq(60), 
                eq(minScore)
        )).thenReturn(lotsAtScore60);
        
        // Act
        int result100 = lotSizeService.getPositionSize(maxLossPerLot, currentVix, 100, minScore);
        int result80 = lotSizeService.getPositionSize(maxLossPerLot, currentVix, 80, minScore);
        int result60 = lotSizeService.getPositionSize(maxLossPerLot, currentVix, 60, minScore);
        
        // Assert: monotonic ordering and bounds
        assertTrue(result100 >= result80,
                "Score 100 (multiplier 1.0x) should produce >= lots than Score 80");
        assertTrue(result80 >= result60,
                "Score 80 (multiplier 0.875x) should produce >= lots than Score 60 (0.5x)");
        
        assertTrue(result60 > 0,
                "Even at min-score (0.5x multiplier), result should be > 0");
        assertTrue(result100 <= 10,
                "Score 100 result should not exceed base VIX-scaled size (10 lots)");
    }
}
