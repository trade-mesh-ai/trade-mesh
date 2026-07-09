package com.trademesh.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LotSizeService capital-based position sizing.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "trading.account-capital=100000",
        "trading.risk-percent-per-trade=2.0"
})
class LotSizeServiceTest {

    @Autowired
    private LotSizeService lotSizeService;

    @Test
    void testGetPositionSize_BasicCalculation() {
        // Capital: 100,000, Risk: 2% = 2,000 risk budget
        // maxLossPerLot: 1,000 → lots = 2,000 / 1,000 = 2
        BigDecimal maxLossPerLot = BigDecimal.valueOf(1000);
        int result = lotSizeService.getPositionSize(maxLossPerLot);
        assertEquals(2, result);
    }

    @Test
    void testGetPositionSize_RoundsDown() {
        // Capital: 100,000, Risk: 2% = 2,000 risk budget
        // maxLossPerLot: 1,500 → lots = 2,000 / 1,500 = 1.333... → 1 (rounded DOWN)
        BigDecimal maxLossPerLot = BigDecimal.valueOf(1500);
        int result = lotSizeService.getPositionSize(maxLossPerLot);
        assertEquals(1, result);
    }

    @Test
    void testGetPositionSize_NeverZero() {
        // Capital: 100,000, Risk: 2% = 2,000 risk budget
        // maxLossPerLot: 10,000 → lots = 2,000 / 10,000 = 0.2 → 0 (rounded DOWN)
        // But we guard against zero, so result = max(1, 0) = 1
        BigDecimal maxLossPerLot = BigDecimal.valueOf(10000);
        int result = lotSizeService.getPositionSize(maxLossPerLot);
        assertEquals(1, result);
    }

    @Test
    void testGetPositionSize_ZeroMaxLoss() {
        // maxLossPerLot is zero → guard returns 1
        BigDecimal maxLossPerLot = BigDecimal.ZERO;
        int result = lotSizeService.getPositionSize(maxLossPerLot);
        assertEquals(1, result);
    }

    @Test
    void testGetPositionSize_NegativeMaxLoss() {
        // maxLossPerLot is negative → guard returns 1
        BigDecimal maxLossPerLot = BigDecimal.valueOf(-500);
        int result = lotSizeService.getPositionSize(maxLossPerLot);
        assertEquals(1, result);
    }

    @Test
    void testGetPositionSize_NullMaxLoss() {
        // maxLossPerLot is null → guard returns 1
        int result = lotSizeService.getPositionSize(null);
        assertEquals(1, result);
    }

    @Test
    void testGetPositionSize_SmallMaxLoss() {
        // Capital: 100,000, Risk: 2% = 2,000 risk budget
        // maxLossPerLot: 100 → lots = 2,000 / 100 = 20
        BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
        int result = lotSizeService.getPositionSize(maxLossPerLot);
        assertEquals(20, result);
    }

    @Test
    void testGetLotSize_NIFTY50() {
        // NIFTY50 should be 65 from config
        int result = lotSizeService.getLotSize("NIFTY50");
        assertEquals(65, result);
    }

    @Test
    void testGetLotSize_NIFTYBANK() {
        // NIFTYBANK should be 30 from config
        int result = lotSizeService.getLotSize("NIFTYBANK");
        assertEquals(30, result);
    }

    @Test
    void testGetLotSize_UnknownSymbol() {
        // Unknown symbol should default to 1
        int result = lotSizeService.getLotSize("UNKNOWN");
        assertEquals(1, result);
    }

    @Test
    void testGetLotSize_NullSymbol() {
        // Null symbol should default to 1
        int result = lotSizeService.getLotSize(null);
        assertEquals(1, result);
    }

    // ===== VIX-based position sizing tests =====

    @Test
    void testGetVixSizeMultiplier_HighVix() {
        // VIX > 18.0 should return 0.5 (half size)
        int baseSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000));
        int scaledSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000), 20.0);
        // baseSize = 2, multiplier = 0.5, scaledSize = floor(2 * 0.5) = 1
        assertEquals(1, scaledSize);
    }

    @Test
    void testGetVixSizeMultiplier_BoundaryVix18() {
        // VIX = 18.0 (boundary) should return 1.0 (full size)
        int baseSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000));
        int scaledSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000), 18.0);
        // baseSize = 2, multiplier = 1.0, scaledSize = floor(2 * 1.0) = 2
        assertEquals(2, scaledSize);
    }

    @Test
    void testGetVixSizeMultiplier_MidRangeVix() {
        // VIX in [12, 18) should return 1.0 (full size)
        int baseSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000));
        int scaledSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000), 15.0);
        // baseSize = 2, multiplier = 1.0, scaledSize = floor(2 * 1.0) = 2
        assertEquals(2, scaledSize);
    }

    @Test
    void testGetVixSizeMultiplier_BoundaryVix12() {
        // VIX = 12.0 (boundary) should return 1.0 (full size)
        int baseSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000));
        int scaledSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000), 12.0);
        // baseSize = 2, multiplier = 1.0, scaledSize = floor(2 * 1.0) = 2
        assertEquals(2, scaledSize);
    }

    @Test
    void testGetVixSizeMultiplier_LowVix() {
        // VIX < 12.0 should return 1.0 (full size)
        int baseSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000));
        int scaledSize = lotSizeService.getPositionSize(BigDecimal.valueOf(1000), 10.0);
        // baseSize = 2, multiplier = 1.0, scaledSize = floor(2 * 1.0) = 2
        assertEquals(2, scaledSize);
    }

    @Test
    void testGetPositionSize_VixScaling_FloorBehavior() {
        // Test that floor is applied correctly: baseSize=1, VIX=20 → 0.5 floored → 1
        // Capital: 100,000, Risk: 2% = 2,000 risk budget
        // maxLossPerLot: 10,000 → baseSize = 2,000 / 10,000 = 0.2 → 0 (rounded DOWN) → max(1, 0) = 1
        // With VIX=20: multiplier = 0.5, scaledSize = floor(1 * 0.5) = 0 → max(1, 0) = 1
        BigDecimal maxLossPerLot = BigDecimal.valueOf(10000);
        int scaledSize = lotSizeService.getPositionSize(maxLossPerLot, 20.0);
        assertEquals(1, scaledSize, "Even with VIX scaling, result should never be zero");
    }

    @Test
    void testGetPositionSize_VixScaling_LargeBaseSize() {
        // Test with larger base size: baseSize=20, VIX=20 → 10
        // Capital: 100,000, Risk: 2% = 2,000 risk budget
        // maxLossPerLot: 100 → baseSize = 2,000 / 100 = 20
        // With VIX=20: multiplier = 0.5, scaledSize = floor(20 * 0.5) = 10
        BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
        int scaledSize = lotSizeService.getPositionSize(maxLossPerLot, 20.0);
        assertEquals(10, scaledSize);
    }

    @Test
    void testGetPositionSize_VixScaling_FullSize() {
        // Test that VIX <= 18 returns full size
        // Capital: 100,000, Risk: 2% = 2,000 risk budget
        // maxLossPerLot: 100 → baseSize = 2,000 / 100 = 20
        // With VIX=15: multiplier = 1.0, scaledSize = floor(20 * 1.0) = 20
        BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
        int scaledSize = lotSizeService.getPositionSize(maxLossPerLot, 15.0);
        assertEquals(20, scaledSize);
    }

     @Test
     void testGetPositionSize_VixScaling_JustAbove18() {
         // Test boundary: VIX = 18.01 should trigger 0.5 multiplier
         // Capital: 100,000, Risk: 2% = 2,000 risk budget
         // maxLossPerLot: 100 → baseSize = 2,000 / 100 = 20
         // With VIX=18.01: multiplier = 0.5, scaledSize = floor(20 * 0.5) = 10
         BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
         int scaledSize = lotSizeService.getPositionSize(maxLossPerLot, 18.01);
         assertEquals(10, scaledSize);
     }

     // ===== Confidence-based position sizing tests =====

     @Test
     void testGetConfidenceSizeMultiplier_AtMinScore() {
         // score = minScore → multiplier = 0.5
         // Capital: 100,000, Risk: 2% = 2,000 risk budget
         // maxLossPerLot: 100 → baseSize = 20
         // VIX: 15 → multiplier = 1.0, vixScaledSize = 20
         // Confidence: score=60, minScore=60 → multiplier = 0.5
         // Final: floor(20 * 0.5) = 10
         BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
         int result = lotSizeService.getPositionSize(maxLossPerLot, 15.0, 60, 60);
         assertEquals(10, result);
     }

     @Test
     void testGetConfidenceSizeMultiplier_AtMaxScore() {
         // score = 100 → multiplier = 1.0
         // Capital: 100,000, Risk: 2% = 2,000 risk budget
         // maxLossPerLot: 100 → baseSize = 20
         // VIX: 15 → multiplier = 1.0, vixScaledSize = 20
         // Confidence: score=100, minScore=60 → multiplier = 1.0
         // Final: floor(20 * 1.0) = 20
         BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
         int result = lotSizeService.getPositionSize(maxLossPerLot, 15.0, 100, 60);
         assertEquals(20, result);
     }

     @Test
     void testGetConfidenceSizeMultiplier_Midpoint() {
         // score = 80, minScore = 60 → midpoint between min and 100
         // Formula: 0.5 + (80 - 60) / (2.0 * (100 - 60)) = 0.5 + 20/80 = 0.5 + 0.25 = 0.75
         // Capital: 100,000, Risk: 2% = 2,000 risk budget
         // maxLossPerLot: 100 → baseSize = 20
         // VIX: 15 → multiplier = 1.0, vixScaledSize = 20
         // Confidence: score=80, minScore=60 → multiplier = 0.75
         // Final: floor(20 * 0.75) = 15
         BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
         int result = lotSizeService.getPositionSize(maxLossPerLot, 15.0, 80, 60);
         assertEquals(15, result);
     }

     @Test
     void testGetConfidenceSizeMultiplier_DivisionByZeroGuard() {
         // minScore = 100 → guard returns 1.0
         // Capital: 100,000, Risk: 2% = 2,000 risk budget
         // maxLossPerLot: 100 → baseSize = 20
         // VIX: 15 → multiplier = 1.0, vixScaledSize = 20
         // Confidence: minScore=100 → guard returns 1.0
         // Final: floor(20 * 1.0) = 20
         BigDecimal maxLossPerLot = BigDecimal.valueOf(100);
         int result = lotSizeService.getPositionSize(maxLossPerLot, 15.0, 50, 100);
         assertEquals(20, result);
     }

     @Test
     void testGetConfidenceSizeMultiplier_CompoundedVixAndConfidence() {
         // Test compounding: baseSize=10, VIX=20 (0.5x) → 5, confidence=70/minScore=60 (0.625x) → floor(5×0.625)=3
         // Capital: 100,000, Risk: 2% = 2,000 risk budget
         // maxLossPerLot: 200 → baseSize = 2,000 / 200 = 10
         // VIX: 20 → multiplier = 0.5, vixScaledSize = floor(10 * 0.5) = 5
         // Confidence: score=70, minScore=60 → multiplier = 0.5 + (70-60)/(2.0*(100-60)) = 0.5 + 10/80 = 0.625
         // Final: floor(5 * 0.625) = 3
         BigDecimal maxLossPerLot = BigDecimal.valueOf(200);
         int result = lotSizeService.getPositionSize(maxLossPerLot, 20.0, 70, 60);
         assertEquals(3, result);
     }

     @Test
     void testGetConfidenceSizeMultiplier_FloorsAtOne() {
         // Even when compounded multiplier would produce 0, result should be 1
         // Capital: 100,000, Risk: 2% = 2,000 risk budget
         // maxLossPerLot: 10,000 → baseSize = 2,000 / 10,000 = 0.2 → 0 (rounded DOWN) → max(1, 0) = 1
         // VIX: 20 → multiplier = 0.5, vixScaledSize = floor(1 * 0.5) = 0 → max(1, 0) = 1
         // Confidence: score=60, minScore=60 → multiplier = 0.5
         // Final: floor(1 * 0.5) = 0 → max(1, 0) = 1
         BigDecimal maxLossPerLot = BigDecimal.valueOf(10000);
         int result = lotSizeService.getPositionSize(maxLossPerLot, 20.0, 60, 60);
         assertEquals(1, result);
     }

     @Test
     void testGetConfidenceSizeMultiplier_RegressionOneArgOverload() {
         // Verify 1-arg overload still works unchanged
         BigDecimal maxLossPerLot = BigDecimal.valueOf(1000);
         int result = lotSizeService.getPositionSize(maxLossPerLot);
         assertEquals(2, result);
     }

     @Test
     void testGetConfidenceSizeMultiplier_RegressionTwoArgOverload() {
         // Verify 2-arg overload still works unchanged
         BigDecimal maxLossPerLot = BigDecimal.valueOf(1000);
         int result = lotSizeService.getPositionSize(maxLossPerLot, 15.0);
         assertEquals(2, result);
     }
}
