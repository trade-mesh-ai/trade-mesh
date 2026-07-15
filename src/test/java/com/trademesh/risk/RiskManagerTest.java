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
