package com.trademesh.trading;

import com.trademesh.intelligence.MarketIntelligenceService;
import com.trademesh.strategy.StrategySelector;
import com.trademesh.strategy.config.StrategiesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TradingScheduler scheduled methods.
 * <p>
 * Tests verify that:
 * 1. scheduledDailyExit() calls exitPositionsForStrategy() with correct parameters
 * 2. scheduledDailyExit() does NOT call exitAllPositions()
 * 3. Market open check is performed before executing exit
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingScheduler Tests")
class TradingSchedulerTest {

    private TradingScheduler tradingScheduler;

    @Mock
    private TradingOrchestrator tradingOrchestrator;

    @Mock
    private DailyReportService dailyReportService;

    @Mock
    private MarketCalendarService marketCalendarService;

    @Mock
    private StrategiesConfig strategiesConfig;

    @Mock
    private MarketIntelligenceService marketIntelligenceService;

    @Mock
    private StrategySelector strategySelector;

    @BeforeEach
    void setUp() {
        tradingScheduler = new TradingScheduler(
                tradingOrchestrator,
                dailyReportService,
                marketCalendarService,
                strategiesConfig,
                marketIntelligenceService,
                strategySelector
        );
    }

    /**
     * Test: scheduledDailyExit() calls exitPositionsForStrategy() with EXPIRY_DAY_STRANGLE.
     * <p>
     * Verifies that:
     * - Market open check is performed
     * - exitPositionsForStrategy("NIFTY50", "EXPIRY_DAY_STRANGLE") is called
     * - exitAllPositions() is NOT called
     */
    @Test
    @DisplayName("scheduledDailyExit: Calls exitPositionsForStrategy with EXPIRY_DAY_STRANGLE")
    void testScheduledDailyExitCallsExitPositionsForStrategy() {
        // Arrange
        when(marketCalendarService.isMarketOpen()).thenReturn(Mono.just(true));
        when(tradingOrchestrator.exitPositionsForStrategy("NIFTY50", "EXPIRY_DAY_STRANGLE"))
                .thenReturn(Mono.just(0));

        // Act
        tradingScheduler.scheduledDailyExit();

        // Give async operations time to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Assert
        verify(marketCalendarService, times(1)).isMarketOpen();
        verify(tradingOrchestrator, times(1)).exitPositionsForStrategy("NIFTY50", "EXPIRY_DAY_STRANGLE");
        verify(tradingOrchestrator, never()).exitAllPositions(anyString());
    }

    /**
     * Test: scheduledDailyExit() skips execution when market is closed.
     * <p>
     * Verifies that:
     * - Market open check is performed
     * - exitPositionsForStrategy() is NOT called when market is closed
     */
    @Test
    @DisplayName("scheduledDailyExit: Skips execution when market is closed")
    void testScheduledDailyExitSkipsWhenMarketClosed() {
        // Arrange
        when(marketCalendarService.isMarketOpen()).thenReturn(Mono.just(false));

        // Act
        tradingScheduler.scheduledDailyExit();

        // Give async operations time to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Assert
        verify(marketCalendarService, times(1)).isMarketOpen();
        verify(tradingOrchestrator, never()).exitPositionsForStrategy(anyString(), anyString());
        verify(tradingOrchestrator, never()).exitAllPositions(anyString());
    }

    /**
     * Test: scheduledDailyExit() handles successful position closure.
     * <p>
     * Verifies that:
     * - exitPositionsForStrategy() is called and returns a count
     * - No exceptions are thrown
     */
    @Test
    @DisplayName("scheduledDailyExit: Handles successful position closure")
    void testScheduledDailyExitHandlesSuccessfulClosure() {
        // Arrange
        when(marketCalendarService.isMarketOpen()).thenReturn(Mono.just(true));
        when(tradingOrchestrator.exitPositionsForStrategy("NIFTY50", "EXPIRY_DAY_STRANGLE"))
                .thenReturn(Mono.just(3)); // 3 positions closed

        // Act
        tradingScheduler.scheduledDailyExit();

        // Give async operations time to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Assert
        verify(tradingOrchestrator, times(1)).exitPositionsForStrategy("NIFTY50", "EXPIRY_DAY_STRANGLE");
        verify(tradingOrchestrator, never()).exitAllPositions(anyString());
    }
}
