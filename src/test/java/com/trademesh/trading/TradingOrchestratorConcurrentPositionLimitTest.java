package com.trademesh.trading;

import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.PaperTradingEngine;
import com.trademesh.execution.TradeStatus;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketIntelligenceService;
import com.trademesh.intelligence.vix.VixData;
import com.trademesh.risk.RiskManager;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.StrategySelector;
import com.trademesh.strategy.TradeSignal;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.enums.Confidence;
import com.trademesh.strategy.enums.SignalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for TradingOrchestrator.runTradingCycle() with concurrent position limit checks.
 * <p>
 * Verifies that:
 * 1. Trade entry is blocked when max concurrent open positions limit is reached
 * 2. Trade entry proceeds normally when under the limit
 */
@DisplayName("TradingOrchestrator Concurrent Position Limit Tests")
class TradingOrchestratorConcurrentPositionLimitTest {

    private TradingOrchestrator tradingOrchestrator;

    @Mock
    private MarketIntelligenceService marketIntelligenceService;

    @Mock
    private StrategySelector strategySelector;

    @Mock
    private PaperTradingEngine paperTradingEngine;

    @Mock
    private TelegramNotificationBot telegramNotificationBot;

    @Mock
    private StrategyExecutionLogRepository executionLogRepository;

    @Mock
    private RiskManager riskManager;

    @Mock
    private PaperTradeRepository paperTradeRepository;

    @Mock
    private StrategiesConfig strategiesConfig;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tradingOrchestrator = new TradingOrchestrator(
                marketIntelligenceService,
                strategySelector,
                paperTradingEngine,
                paperTradeRepository,
                strategiesConfig,
                telegramNotificationBot,
                executionLogRepository,
                riskManager
        );
    }

    /**
     * Test Case 1: Limit Reached (3 open positions at max of 3)
     * <p>
     * Scenario:
     * - PaperTradeRepository.findByStatus(TradeStatus.OPEN) returns Flux with 3 trades
     * - RiskManager.isWithinConcurrentPositionLimit() returns false
     * - Strategy selector returns an executable signal
     * <p>
     * Expected:
     * - Trade entry is skipped
     * - StrategyResult has isExecutable=false
     * - skipReason contains "Max concurrent open positions (3) reached"
     * - paperTradingEngine.executeTrade() is NEVER called
     */
    @Test
    @DisplayName("Should skip trade entry when max concurrent positions limit is reached")
    void testLimitReachedSkipsTradeEntry() {
        // Arrange: Create 3 mock open trades (at the limit)
        PaperTrade trade1 = createMockTrade("NIFTY50", 1);
        PaperTrade trade2 = createMockTrade("NIFTYBANK", 1);
        PaperTrade trade3 = createMockTrade("NIFTY50", 2);

        // Mock market intelligence (safe to trade)
        MarketIntelligence intelligence = createMockIntelligence(true);
        when(marketIntelligenceService.generateFreshIntelligence("NIFTY50"))
                .thenReturn(Mono.just(intelligence));

        // Mock strategy selector to return executable signal
        TradeSignal signal = createMockSignal("BULL_PUT_SPREAD");
        StrategyResult strategyResult = StrategyResult.builder()
                .isExecutable(true)
                .signal(signal)
                .build();
        when(strategySelector.evaluateSelectedStrategy(intelligence))
                .thenReturn(Mono.just(strategyResult));

        // Mock RiskManager: limit is reached (3 open positions)
        when(riskManager.isWithinConcurrentPositionLimit())
                .thenReturn(Mono.just(false));
        when(riskManager.getMaxConcurrentOpenPositions())
                .thenReturn(3);

        // Act & Assert
        StepVerifier.create(tradingOrchestrator.runTradingCycle("NIFTY50"))
                .assertNext(result -> {
                    assert !result.getIsExecutable() : "Expected isExecutable=false when limit reached";
                    assert result.getSkipReason() != null : "Expected skipReason to be set";
                    assert result.getSkipReason().contains("Max concurrent open positions (3) reached")
                            : "Expected skip reason to mention the limit";
                })
                .verifyComplete();

        // Verify that executeTrade was NEVER called
        verify(paperTradingEngine, never()).executeTrade(any());
        verify(executionLogRepository, never()).save(any());
        verify(telegramNotificationBot, never()).sendAlert(any());
    }

    /**
     * Test Case 2: Under Limit (1 open position, max of 3)
     * <p>
     * Scenario:
     * - PaperTradeRepository.findByStatus(TradeStatus.OPEN) returns Flux with 1 trade
     * - RiskManager.isWithinConcurrentPositionLimit() returns true
     * - Strategy selector returns an executable signal
     * - PaperTradingEngine.executeTrade() succeeds
     * <p>
     * Expected:
     * - Trade entry proceeds normally
     * - paperTradingEngine.executeTrade() IS called
     * - logExecution() is called
     * - sendTradingCycleSummary() is called
     * - StrategyResult reflects normal execution (not a skip)
     */
    @Test
    @DisplayName("Should allow trade entry when under concurrent positions limit")
    void testUnderLimitAllowsTradeEntry() {
        // Arrange: Create 1 mock open trade (under the limit of 3)
        PaperTrade trade1 = createMockTrade("NIFTYBANK", 1);

        // Mock market intelligence (safe to trade)
        MarketIntelligence intelligence = createMockIntelligence(true);
        when(marketIntelligenceService.generateFreshIntelligence("NIFTY50"))
                .thenReturn(Mono.just(intelligence));

        // Mock strategy selector to return executable signal
        TradeSignal signal = createMockSignal("BULL_PUT_SPREAD");
        StrategyResult strategyResult = StrategyResult.builder()
                .isExecutable(true)
                .signal(signal)
                .build();
        when(strategySelector.evaluateSelectedStrategy(intelligence))
                .thenReturn(Mono.just(strategyResult));

        // Mock RiskManager: under limit (1 open position < 3)
        when(riskManager.isWithinConcurrentPositionLimit())
                .thenReturn(Mono.just(true));

        // Mock RiskManager: aggregate risk budget also passes
        when(riskManager.isWithinAggregateRiskBudget())
                .thenReturn(Mono.just(true));

        // Mock PaperTradingEngine.executeTrade() to return a successful trade
        PaperTrade executedTrade = createMockTrade("NIFTY50", 1);
        when(paperTradingEngine.executeTrade(strategyResult))
                .thenReturn(Mono.just(executedTrade));

        // Mock execution logging
        StrategyExecutionLog executionLog = StrategyExecutionLog.createNew();
        executionLog.setSymbol("NIFTY50");
        executionLog.setStrategyName("BULL_PUT_SPREAD");
        when(executionLogRepository.save(any()))
                .thenReturn(Mono.just(executionLog));

        // Mock Telegram alert
        when(telegramNotificationBot.sendAlert(any()))
                .thenReturn(Mono.empty());

        // Act & Assert
        StepVerifier.create(tradingOrchestrator.runTradingCycle("NIFTY50"))
                .assertNext(result -> {
                    assert result.getIsExecutable() : "Expected isExecutable=true when under limit";
                    assert result.getSignal() != null : "Expected signal to be present";
                })
                .verifyComplete();

        // Verify that executeTrade WAS called
        verify(paperTradingEngine, times(1)).executeTrade(strategyResult);
        verify(executionLogRepository, times(1)).save(any());
        verify(telegramNotificationBot, times(1)).sendAlert(any());
    }

    // ─── Helper Methods ───────────────────────────────────────────────────────

    /**
     * Creates a mock PaperTrade for testing.
     */
    private PaperTrade createMockTrade(String symbol, int id) {
        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol(symbol)
                .status(TradeStatus.OPEN)
                .entryCredit(BigDecimal.valueOf(100.0))
                .currentValue(BigDecimal.valueOf(101.0))
                .build();
    }

    /**
     * Creates a mock MarketIntelligence for testing.
     */
    private MarketIntelligence createMockIntelligence(boolean isSafeToTrade) {
        return MarketIntelligence.builder()
                .symbol("NIFTY50")
                .isSafeToTrade(isSafeToTrade)
                .marketCondition(com.trademesh.intelligence.MarketCondition.TRENDING_BULLISH)
                .vixData(VixData.builder()
                        .currentVix(15.0)
                        .build())
                .build();
    }

    /**
     * Creates a mock TradeSignal for testing.
     */
    private TradeSignal createMockSignal(String strategyName) {
        return TradeSignal.builder()
                .strategyName(strategyName)
                .signalType(SignalType.ENTER)
                .reason("Test signal")
                .confidence(Confidence.HIGH)
                .build();
    }
}
