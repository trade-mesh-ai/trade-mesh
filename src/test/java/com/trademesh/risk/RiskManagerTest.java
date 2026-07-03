package com.trademesh.risk;

import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RiskManager.
 * <p>
 * Tests cover:
 * - Position sizing calculations
 * - Max position size enforcement
 * - Daily loss limit tracking
 * - Sector exposure validation
 * - Trade recording and P&L tracking
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RiskManager Tests")
class RiskManagerTest {

    @Mock
    private RiskLimitsConfig riskLimitsConfig;

    @Mock
    private com.trademesh.execution.PaperTradeRepository paperTradeRepository;

    @InjectMocks
    private RiskManager riskManager;

    @BeforeEach
    void setUp() {
        // Reset RiskManager state before each test
        riskManager.resetForTesting();

        // Setup default config values
        lenient().when(riskLimitsConfig.getMaxPositionSizePerSymbol()).thenReturn(5);
        lenient().when(riskLimitsConfig.getDailyLossLimitPercent()).thenReturn(2.0);
        lenient().when(riskLimitsConfig.getMaxSectorExposure()).thenReturn(50000.0);
        lenient().when(riskLimitsConfig.getMinMarginBufferPercent()).thenReturn(10.0);
        lenient().when(riskLimitsConfig.getMaxConcurrentOpenPositions()).thenReturn(3);

        RiskLimitsConfig.PositionSizingConfig positionSizing = new RiskLimitsConfig.PositionSizingConfig();
        positionSizing.setRiskPercent(1.0);
        positionSizing.setMaxQuantity(10);
        lenient().when(riskLimitsConfig.getPositionSizing()).thenReturn(positionSizing);
    }

    @Test
    @DisplayName("Should pass risk check for valid trade")
    void testIsWithinRiskLimits_ValidTrade() {
        StepVerifier.create(riskManager.isWithinRiskLimits("NIFTY50", 2))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should fail risk check when position size exceeds limit")
    void testIsWithinRiskLimits_ExceedsMaxPosition() {
        // First, add a position
        riskManager.recordTrade("NIFTY50", 5, 0.0).block();

        // Try to add more (should fail)
        StepVerifier.create(riskManager.isWithinRiskLimits("NIFTY50", 1))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should calculate position size correctly")
    void testCalculatePositionSize() {
        double accountEquity = 100000;
        double riskPercent = 1.0;
        double stopLossPoints = 100;

        StepVerifier.create(riskManager.calculatePositionSize(accountEquity, riskPercent, stopLossPoints))
                .expectNext(10) // (100000 * 1.0 / 100) / 100 = 10
                .verifyComplete();
    }

    @Test
    @DisplayName("Should cap position size at max quantity")
    void testCalculatePositionSize_CappedAtMax() {
        double accountEquity = 1000000;
        double riskPercent = 1.0;
        double stopLossPoints = 1;

        StepVerifier.create(riskManager.calculatePositionSize(accountEquity, riskPercent, stopLossPoints))
                .expectNext(10) // Capped at maxQuantity = 10
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return 0 for invalid stop-loss points")
    void testCalculatePositionSize_InvalidStopLoss() {
        double accountEquity = 100000;
        double riskPercent = 1.0;
        double stopLossPoints = 0;

        StepVerifier.create(riskManager.calculatePositionSize(accountEquity, riskPercent, stopLossPoints))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should record trade and update daily P&L")
    void testRecordTrade() {
        StepVerifier.create(riskManager.recordTrade("NIFTY50", 2, 1000.0))
                .verifyComplete();

        StepVerifier.create(riskManager.getDailyPnl())
                .expectNext(1000.0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should accumulate P&L from multiple trades")
    void testRecordTrade_Multiple() {
        riskManager.recordTrade("NIFTY50", 2, 1000.0).block();
        riskManager.recordTrade("BANKNIFTY", 1, 500.0).block();

        StepVerifier.create(riskManager.getDailyPnl())
                .expectNext(1500.0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should track position size per symbol")
    void testGetPositionSize() {
        riskManager.recordTrade("NIFTY50", 3, 0.0).block();

        StepVerifier.create(riskManager.getPositionSize("NIFTY50"))
                .expectNext(3)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return 0 for non-existent position")
    void testGetPositionSize_NonExistent() {
        StepVerifier.create(riskManager.getPositionSize("NIFTY50"))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should reset daily tracking")
    void testResetDailyTracking() {
        riskManager.recordTrade("NIFTY50", 2, 1000.0).block();

        StepVerifier.create(riskManager.resetDailyTracking())
                .verifyComplete();

        StepVerifier.create(riskManager.getDailyPnl())
                .expectNext(0.0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle concurrent trades")
    void testConcurrentTrades() {
        // Record trades concurrently
        riskManager.recordTrade("NIFTY50", 1, 100.0).block();
        riskManager.recordTrade("NIFTY50", 1, 100.0).block();
        riskManager.recordTrade("BANKNIFTY", 1, 50.0).block();

        StepVerifier.create(riskManager.getPositionSize("NIFTY50"))
                .expectNext(2)
                .verifyComplete();

        StepVerifier.create(riskManager.getDailyPnl())
                .expectNext(250.0)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should validate risk limits with multiple positions")
    void testIsWithinRiskLimits_MultiplePositions() {
        // Add positions up to limit
        riskManager.recordTrade("NIFTY50", 3, 0.0).block();
        riskManager.recordTrade("NIFTY50", 2, 0.0).block();

        // Should fail when trying to exceed limit
        StepVerifier.create(riskManager.isWithinRiskLimits("NIFTY50", 1))
                .expectNext(false)
                .verifyComplete();

        // Should pass for different symbol
        StepVerifier.create(riskManager.isWithinRiskLimits("BANKNIFTY", 3))
                .expectNext(true)
                .verifyComplete();
    }

    // ===== Concurrent Position Limit Tests =====

    @Test
    @DisplayName("Should return true when open count is below limit")
    void testIsWithinConcurrentPositionLimit_BelowLimit() {
        // Setup: 2 open trades (limit is 3)
        PaperTrade trade1 = createMockTrade("NIFTY50", TradeStatus.OPEN);
        PaperTrade trade2 = createMockTrade("BANKNIFTY", TradeStatus.OPEN);

        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2));

        StepVerifier.create(riskManager.isWithinConcurrentPositionLimit())
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return false when open count equals limit")
    void testIsWithinConcurrentPositionLimit_EqualsLimit() {
        // Setup: 3 open trades (limit is 3)
        PaperTrade trade1 = createMockTrade("NIFTY50", TradeStatus.OPEN);
        PaperTrade trade2 = createMockTrade("BANKNIFTY", TradeStatus.OPEN);
        PaperTrade trade3 = createMockTrade("NIFTY50", TradeStatus.OPEN);

        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2, trade3));

        StepVerifier.create(riskManager.isWithinConcurrentPositionLimit())
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return false when open count exceeds limit")
    void testIsWithinConcurrentPositionLimit_AboveLimit() {
        // Setup: 4 open trades (limit is 3)
        PaperTrade trade1 = createMockTrade("NIFTY50", TradeStatus.OPEN);
        PaperTrade trade2 = createMockTrade("BANKNIFTY", TradeStatus.OPEN);
        PaperTrade trade3 = createMockTrade("NIFTY50", TradeStatus.OPEN);
        PaperTrade trade4 = createMockTrade("BANKNIFTY", TradeStatus.OPEN);

        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2, trade3, trade4));

        StepVerifier.create(riskManager.isWithinConcurrentPositionLimit())
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return true when no open positions exist")
    void testIsWithinConcurrentPositionLimit_NoOpenPositions() {
        // Setup: 0 open trades (limit is 3)
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.empty());

        StepVerifier.create(riskManager.isWithinConcurrentPositionLimit())
                .expectNext(true)
                .verifyComplete();
    }

    /**
     * Helper method to create a mock PaperTrade for testing.
     *
     * @param symbol the trading symbol
     * @param status the trade status
     * @return a mock PaperTrade
     */
    private PaperTrade createMockTrade(String symbol, TradeStatus status) {
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol(symbol);
        trade.setStatus(status);
        return trade;
    }
}
