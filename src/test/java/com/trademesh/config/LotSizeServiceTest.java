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
}
