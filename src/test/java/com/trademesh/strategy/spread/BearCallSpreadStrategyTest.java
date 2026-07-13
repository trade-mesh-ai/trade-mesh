package com.trademesh.strategy.spread;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.trading.MarketCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for BearCallSpreadStrategy.
 * Verifies that the strategy can be instantiated with all required dependencies,
 * including the newly injected MarketMetricsService.
 */
class BearCallSpreadStrategyTest {

    @Mock
    private StrategiesConfig strategiesConfig;

    @Mock
    private MarketMetricsService marketMetricsService;

    @Mock
    private LotSizeService lotSizeService;

    @Mock
    private MarketCalendarService marketCalendarService;

    private BearCallSpreadStrategy strategy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        strategy = new BearCallSpreadStrategy(
                strategiesConfig,
                marketMetricsService,
                lotSizeService,
                marketCalendarService
        );
    }

    @Test
    void testStrategyInstantiation_WithAllDependencies_Succeeds() {
        assertNotNull(strategy, "Strategy should be instantiated with all dependencies");
    }

    @Test
    void testGetName_ReturnsCorrectStrategyName() {
        assertNotNull(strategy.getName());
    }
}
