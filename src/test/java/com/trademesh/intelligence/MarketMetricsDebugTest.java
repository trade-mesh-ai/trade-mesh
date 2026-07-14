package com.trademesh.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;

import static org.mockito.Mockito.*;

/**
 * Minimal isolated debug test for MarketMetrics mock behavior.
 * 
 * This test is completely separate from ExpiryDayStrategyPositionSizingTest
 * and tests ONLY the basic Mockito mock behavior on MarketMetrics.
 * 
 * If this test prints 250.0, the problem is specific to the reactive chain.
 * If this test prints null, the problem is fundamental to mocking MarketMetrics.
 */
@DisplayName("MarketMetrics Mock Debug Test")
class MarketMetricsDebugTest {

    @Test
    void debugMockMetricsAtr14() {
        MarketMetrics metrics = mock(MarketMetrics.class);
        when(metrics.getAtr14()).thenReturn(BigDecimal.valueOf(250.0));
        System.out.println("DEBUG: isolated mock atr14=" + metrics.getAtr14());
    }
}
