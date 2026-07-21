package com.trademesh.trading;

import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.PaperTradingEngine;
import com.trademesh.execution.TradeStatus;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TradingOrchestrator DTE exit safety net methods.
 * <p>
 * Tests verify that:
 * 1. runDteExitSafetyNetCheck() iterates ONLY the three DTE-governed strategies
 * 2. EXPIRY_DAY_STRANGLE is never queried
 * 3. Correct default thresholds are passed per strategy (2 for IRON_CONDOR, 1 for spreads)
 * 4. Positions with isDteExitDue() == true are closed
 * 5. Positions with isDteExitDue() == false are left alone
 * 6. Correct count of closed positions is returned
 * 7. No positions due for exit → returns 0, closeTrade() never called
 * <p>
 * These are isolated unit tests using Mockito (no Spring context).
 * Pattern matches PaperTradingEngineDteExitTest.java and PaperTradingEngineVixPanicTest.java.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingOrchestrator DTE Exit Safety Net Tests")
class TradingOrchestratorDteExitSafetyNetTest {

    private TradingOrchestrator tradingOrchestrator;

    @Mock
    private PaperTradeRepository paperTradeRepository;

    @Mock
    private PaperTradingEngine paperTradingEngine;

    @Mock
    private StrategiesConfig strategiesConfig;

    @Mock
    private StrategyProperties ironCondorConfig;

    @Mock
    private StrategyProperties spreadConfig;

    @BeforeEach
    void setUp() {
        // Create a minimal TradingOrchestrator with mocked dependencies
        tradingOrchestrator = new TradingOrchestrator(
                null, // marketIntelligenceService
                null, // strategySelector
                paperTradingEngine,
                paperTradeRepository,
                strategiesConfig,
                null, // telegramNotificationBot
                null, // executionLogRepository
                null  // riskManager
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEST: runDteExitSafetyNetCheck() - Core Behavior
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: runDteExitSafetyNetCheck() closes positions where isDteExitDue() returns true.
     * <p>
     * Verifies that:
     * - IRON_CONDOR positions with isDteExitDue() == true are closed
     * - closeTrade() is called with reason "DTE_EXIT_SAFETY_NET"
     * - Count of closed positions is returned correctly
     */
    @Test
    @DisplayName("runDteExitSafetyNetCheck: Closes positions where isDteExitDue() returns true")
    void testClosesPositionsDueForExit() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        PaperTrade trade1 = createIronCondorTrade(expiryDate);
        PaperTrade trade2 = createIronCondorTrade(expiryDate);

        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(ironCondorConfig);
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(spreadConfig);
        when(strategiesConfig.getStrategy("BEAR_CALL_SPREAD")).thenReturn(spreadConfig);

        // IRON_CONDOR: 2 trades, both due for exit
        when(paperTradeRepository.findByStrategyNameAndStatus("IRON_CONDOR", TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2));
        when(paperTradingEngine.isDteExitDue(eq(trade1), eq(ironCondorConfig), eq(2), any(LocalTime.class))).thenReturn(true);
        when(paperTradingEngine.isDteExitDue(eq(trade2), eq(ironCondorConfig), eq(2), any(LocalTime.class))).thenReturn(true);

        // BULL_PUT_SPREAD and BEAR_CALL_SPREAD: no trades
        when(paperTradeRepository.findByStrategyNameAndStatus("BULL_PUT_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.empty());
        when(paperTradeRepository.findByStrategyNameAndStatus("BEAR_CALL_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.empty());

        // Mock closeTrade to return the closed trade
        when(paperTradingEngine.closeTrade(trade1, "DTE_EXIT_SAFETY_NET"))
                .thenReturn(Mono.just(trade1));
        when(paperTradingEngine.closeTrade(trade2, "DTE_EXIT_SAFETY_NET"))
                .thenReturn(Mono.just(trade2));

        // Act & Assert
        StepVerifier.create(tradingOrchestrator.runDteExitSafetyNetCheck())
                .assertNext(count -> assertEquals(2, count, "Expected 2 positions to be closed"))
                .verifyComplete();

        // Verify closeTrade was called exactly twice with correct reason
        verify(paperTradingEngine, times(2)).closeTrade(any(PaperTrade.class), eq("DTE_EXIT_SAFETY_NET"));
    }

    /**
     * Test 2: runDteExitSafetyNetCheck() leaves alone positions where isDteExitDue() returns false.
     * <p>
     * Verifies that:
     * - Positions with isDteExitDue() == false are NOT closed
     * - closeTrade() is not called for those positions
     * - Count reflects only closed positions
     */
    @Test
    @DisplayName("runDteExitSafetyNetCheck: Leaves alone positions where isDteExitDue() returns false")
    void testLeavesPositionsNotDueForExit() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(5); // Far away
        PaperTrade trade1 = createIronCondorTrade(expiryDate);

        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(ironCondorConfig);
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(spreadConfig);
        when(strategiesConfig.getStrategy("BEAR_CALL_SPREAD")).thenReturn(spreadConfig);

        // IRON_CONDOR: 1 trade, NOT due for exit
        when(paperTradeRepository.findByStrategyNameAndStatus("IRON_CONDOR", TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1));
        when(paperTradingEngine.isDteExitDue(eq(trade1), eq(ironCondorConfig), eq(2), any(LocalTime.class))).thenReturn(false);

        // BULL_PUT_SPREAD and BEAR_CALL_SPREAD: no trades
        when(paperTradeRepository.findByStrategyNameAndStatus("BULL_PUT_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.empty());
        when(paperTradeRepository.findByStrategyNameAndStatus("BEAR_CALL_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.empty());

        // Act & Assert
        StepVerifier.create(tradingOrchestrator.runDteExitSafetyNetCheck())
                .assertNext(count -> assertEquals(0, count, "Expected 0 positions to be closed"))
                .verifyComplete();

        // Verify closeTrade was NEVER called
        verify(paperTradingEngine, never()).closeTrade(any(PaperTrade.class), anyString());
    }

    /**
     * Test 3: runDteExitSafetyNetCheck() iterates ONLY the three DTE-governed strategies.
     * <p>
     * Verifies that:
     * - IRON_CONDOR is queried
     * - BULL_PUT_SPREAD is queried
     * - BEAR_CALL_SPREAD is queried
     * - EXPIRY_DAY_STRANGLE is NEVER queried
     * - Correct default thresholds are passed (2 for IRON_CONDOR, 1 for spreads)
     */
    @Test
    @DisplayName("runDteExitSafetyNetCheck: Iterates ONLY DTE-governed strategies, never EXPIRY_DAY_STRANGLE")
    void testIteratesOnlyDteGovernedStrategies() {
        // Arrange
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(ironCondorConfig);
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(spreadConfig);
        when(strategiesConfig.getStrategy("BEAR_CALL_SPREAD")).thenReturn(spreadConfig);

        // All strategies return empty (no trades)
        when(paperTradeRepository.findByStrategyNameAndStatus(anyString(), eq(TradeStatus.OPEN)))
                .thenReturn(Flux.empty());

        // Act
        StepVerifier.create(tradingOrchestrator.runDteExitSafetyNetCheck())
                .assertNext(count -> assertEquals(0, count))
                .verifyComplete();

        // Verify that ONLY the three DTE-governed strategies were queried
        verify(paperTradeRepository, times(1)).findByStrategyNameAndStatus("IRON_CONDOR", TradeStatus.OPEN);
        verify(paperTradeRepository, times(1)).findByStrategyNameAndStatus("BULL_PUT_SPREAD", TradeStatus.OPEN);
        verify(paperTradeRepository, times(1)).findByStrategyNameAndStatus("BEAR_CALL_SPREAD", TradeStatus.OPEN);

        // Verify EXPIRY_DAY_STRANGLE was NEVER queried
        verify(paperTradeRepository, never()).findByStrategyNameAndStatus("EXPIRY_DAY_STRANGLE", TradeStatus.OPEN);
    }

    /**
     * Test 4: runDteExitSafetyNetCheck() passes correct default thresholds per strategy.
     * <p>
     * Verifies that:
     * - IRON_CONDOR positions are checked with defaultThreshold = 2
     * - BULL_PUT_SPREAD positions are checked with defaultThreshold = 1
     * - BEAR_CALL_SPREAD positions are checked with defaultThreshold = 1
     */
    @Test
    @DisplayName("runDteExitSafetyNetCheck: Passes correct default thresholds per strategy")
    void testPassesCorrectDefaultThresholds() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        PaperTrade ironCondorTrade = createIronCondorTrade(expiryDate);
        PaperTrade bullPutTrade = createSpreadTrade("BULL_PUT_SPREAD", expiryDate);
        PaperTrade bearCallTrade = createSpreadTrade("BEAR_CALL_SPREAD", expiryDate);

        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(ironCondorConfig);
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(spreadConfig);
        when(strategiesConfig.getStrategy("BEAR_CALL_SPREAD")).thenReturn(spreadConfig);

        when(paperTradeRepository.findByStrategyNameAndStatus("IRON_CONDOR", TradeStatus.OPEN))
                .thenReturn(Flux.just(ironCondorTrade));
        when(paperTradeRepository.findByStrategyNameAndStatus("BULL_PUT_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.just(bullPutTrade));
        when(paperTradeRepository.findByStrategyNameAndStatus("BEAR_CALL_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.just(bearCallTrade));

        when(paperTradingEngine.isDteExitDue(eq(ironCondorTrade), eq(ironCondorConfig), eq(2), any(LocalTime.class))).thenReturn(false);
        when(paperTradingEngine.isDteExitDue(eq(bullPutTrade), eq(spreadConfig), eq(1), any(LocalTime.class))).thenReturn(false);
        when(paperTradingEngine.isDteExitDue(eq(bearCallTrade), eq(spreadConfig), eq(1), any(LocalTime.class))).thenReturn(false);

        // Act
        StepVerifier.create(tradingOrchestrator.runDteExitSafetyNetCheck())
                .assertNext(count -> assertEquals(0, count))
                .verifyComplete();

        // Verify correct thresholds were passed
        ArgumentCaptor<Integer> thresholdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(paperTradingEngine).isDteExitDue(eq(ironCondorTrade), eq(ironCondorConfig), thresholdCaptor.capture(), any(LocalTime.class));
        assertEquals(2, thresholdCaptor.getValue(), "IRON_CONDOR should use threshold 2");

        verify(paperTradingEngine).isDteExitDue(eq(bullPutTrade), eq(spreadConfig), thresholdCaptor.capture(), any(LocalTime.class));
        assertEquals(1, thresholdCaptor.getValue(), "BULL_PUT_SPREAD should use threshold 1");

        verify(paperTradingEngine).isDteExitDue(eq(bearCallTrade), eq(spreadConfig), thresholdCaptor.capture(), any(LocalTime.class));
        assertEquals(1, thresholdCaptor.getValue(), "BEAR_CALL_SPREAD should use threshold 1");
    }

    /**
     * Test 5: runDteExitSafetyNetCheck() returns 0 when no positions are due for exit.
     * <p>
     * Verifies that:
     * - When no positions are due for exit, count is 0
     * - closeTrade() is never called
     */
    @Test
    @DisplayName("runDteExitSafetyNetCheck: Returns 0 when no positions due for exit, closeTrade() never called")
    void testReturnsZeroWhenNoPositionsDueForExit() {
        // Arrange
        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(ironCondorConfig);
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(spreadConfig);
        when(strategiesConfig.getStrategy("BEAR_CALL_SPREAD")).thenReturn(spreadConfig);

        // All strategies return empty
        when(paperTradeRepository.findByStrategyNameAndStatus(anyString(), eq(TradeStatus.OPEN)))
                .thenReturn(Flux.empty());

        // Act & Assert
        StepVerifier.create(tradingOrchestrator.runDteExitSafetyNetCheck())
                .assertNext(count -> {
                    assertEquals(0, count, "Expected count to be 0");
                })
                .verifyComplete();

        // Verify closeTrade was never called
        verify(paperTradingEngine, never()).closeTrade(any(PaperTrade.class), anyString());
    }

    /**
     * Test 6: runDteExitSafetyNetCheck() handles mixed scenarios correctly.
     * <p>
     * Verifies that:
     * - Some positions are closed (isDteExitDue() == true)
     * - Some positions are left alone (isDteExitDue() == false)
     * - Count reflects only closed positions
     */
    @Test
    @DisplayName("runDteExitSafetyNetCheck: Handles mixed scenarios (some close, some don't)")
    void testHandlesMixedScenarios() {
        // Arrange
        LocalDate expiryDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        PaperTrade ironCondorTrade1 = createIronCondorTrade(expiryDate);
        PaperTrade ironCondorTrade2 = createIronCondorTrade(expiryDate);
        PaperTrade bullPutTrade = createSpreadTrade("BULL_PUT_SPREAD", expiryDate);

        when(strategiesConfig.getStrategy("IRON_CONDOR")).thenReturn(ironCondorConfig);
        when(strategiesConfig.getStrategy("BULL_PUT_SPREAD")).thenReturn(spreadConfig);
        when(strategiesConfig.getStrategy("BEAR_CALL_SPREAD")).thenReturn(spreadConfig);

        // IRON_CONDOR: 2 trades, 1 due for exit, 1 not
        when(paperTradeRepository.findByStrategyNameAndStatus("IRON_CONDOR", TradeStatus.OPEN))
                .thenReturn(Flux.just(ironCondorTrade1, ironCondorTrade2));
        when(paperTradingEngine.isDteExitDue(eq(ironCondorTrade1), eq(ironCondorConfig), eq(2), any(LocalTime.class))).thenReturn(true);
        when(paperTradingEngine.isDteExitDue(eq(ironCondorTrade2), eq(ironCondorConfig), eq(2), any(LocalTime.class))).thenReturn(false);

        // BULL_PUT_SPREAD: 1 trade, due for exit
        when(paperTradeRepository.findByStrategyNameAndStatus("BULL_PUT_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.just(bullPutTrade));
        when(paperTradingEngine.isDteExitDue(eq(bullPutTrade), eq(spreadConfig), eq(1), any(LocalTime.class))).thenReturn(true);

        // BEAR_CALL_SPREAD: no trades
        when(paperTradeRepository.findByStrategyNameAndStatus("BEAR_CALL_SPREAD", TradeStatus.OPEN))
                .thenReturn(Flux.empty());

        // Mock closeTrade
        when(paperTradingEngine.closeTrade(ironCondorTrade1, "DTE_EXIT_SAFETY_NET"))
                .thenReturn(Mono.just(ironCondorTrade1));
        when(paperTradingEngine.closeTrade(bullPutTrade, "DTE_EXIT_SAFETY_NET"))
                .thenReturn(Mono.just(bullPutTrade));

        // Act & Assert
        StepVerifier.create(tradingOrchestrator.runDteExitSafetyNetCheck())
                .assertNext(count -> assertEquals(2, count, "Expected 2 positions to be closed (1 IC + 1 BPS)"))
                .verifyComplete();

        // Verify closeTrade was called exactly twice
        verify(paperTradingEngine, times(2)).closeTrade(any(PaperTrade.class), eq("DTE_EXIT_SAFETY_NET"));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a test Iron Condor trade with 4 legs.
     */
    private PaperTrade createIronCondorTrade(LocalDate expiryDate) {
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(19500.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.CE)
                        .strikePrice(BigDecimal.valueOf(19700.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19300.0))
                        .limitPrice(BigDecimal.valueOf(50.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19100.0))
                        .limitPrice(BigDecimal.valueOf(30.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build()
        );

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("IRON_CONDOR")
                .status(TradeStatus.OPEN)
                .legs(legs)
                .entryCredit(BigDecimal.valueOf(100.0))
                .currentValue(BigDecimal.valueOf(100.0))
                .unrealizedPnl(BigDecimal.valueOf(0.0))
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
    }

    /**
     * Creates a test spread trade (Bull Put or Bear Call) with 2 legs.
     */
    private PaperTrade createSpreadTrade(String strategyName, LocalDate expiryDate) {
        List<TradeLeg> legs = List.of(
                TradeLeg.builder()
                        .action(Action.SELL)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19300.0))
                        .limitPrice(BigDecimal.valueOf(60.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build(),
                TradeLeg.builder()
                        .action(Action.BUY)
                        .optionType(OptionType.PE)
                        .strikePrice(BigDecimal.valueOf(19200.0))
                        .limitPrice(BigDecimal.valueOf(40.0))
                        .quantity(1)
                        .expiryDate(expiryDate)
                        .build()
        );

        return PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName(strategyName)
                .status(TradeStatus.OPEN)
                .legs(legs)
                .entryCredit(BigDecimal.valueOf(100.0))
                .currentValue(BigDecimal.valueOf(100.0))
                .unrealizedPnl(BigDecimal.valueOf(0.0))
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
    }
}
