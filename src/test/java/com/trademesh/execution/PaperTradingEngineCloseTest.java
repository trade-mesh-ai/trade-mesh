package com.trademesh.execution;

import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.config.LotSizeService;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for closeIronCondorTrade() and closeSpreadTrade() concurrency-safety guards.
 * <p>
 * Tests verify that both methods correctly:
 * - Re-fetch the trade from DB to ensure current status (guard against concurrent closes)
 * - Skip closing if the trade is already CLOSED (duplicate close prevention)
 * - Proceed with close logic if the trade is still OPEN
 * - Mutate the ORIGINAL parameter (not the freshly-fetched object)
 * - Call save() and sendAlert() only when proceeding with close
 * <p>
 * These are isolated unit tests using Mockito to mock repository and service dependencies.
 * No Spring context or database dependencies are required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaperTradingEngine Close Trade Concurrency-Safety Tests")
class PaperTradingEngineCloseTest {

    private PaperTradingEngine engine;

    @Mock
    private PaperTradeRepository paperTradeRepository;

    @Mock
    private TelegramNotificationBot telegramNotificationBot;

    @Mock
    private LotSizeService lotSizeService;

    @BeforeEach
    void setUp() {
        engine = new PaperTradingEngine(
            paperTradeRepository, null, telegramNotificationBot,
            null, null, lotSizeService, null, null
        );

        // Stub lotSizeService for alert formatting
        lenient().when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);

        // Stub telegramNotificationBot.sendAlert() to return Mono.empty()
        lenient().when(telegramNotificationBot.sendAlert(any())).thenReturn(Mono.empty());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IRON CONDOR CLOSE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: closeIronCondorTrade() - OPEN status → proceeds with save and alert.
     * <p>
     * Verifies that when the re-fetched trade is still OPEN, the method:
     * - Mutates the ORIGINAL parameter (not the freshly-fetched object)
     * - Sets status to CLOSED, realizedPnl = unrealizedPnl, unrealizedPnl = 0.0
     * - Calls save() exactly once
     * - Calls sendAlert() exactly once
     * <p>
     * Uses two distinct trade objects to verify the method mutates the original parameter,
     * not the freshly-fetched object from the DB.
     */
    @Test
    @DisplayName("closeIronCondorTrade: OPEN status → proceeds with save and alert, mutates original param")
    void closeIronCondorTrade_OpenStatus_ProceedsWithSaveAndAlert() {
        // Arrange: Create two distinct trade objects
        PaperTrade originalParam = createOpenIronCondorTrade();
        originalParam.setExitReason("DTE_EXIT");
        originalParam.setUnrealizedPnl(-10.5);

        PaperTrade freshlyFetchedFromDb = createOpenIronCondorTrade();
        freshlyFetchedFromDb.setId(originalParam.getId());
        freshlyFetchedFromDb.setExitReason(null);  // deliberately different
        freshlyFetchedFromDb.setUnrealizedPnl(0.0);  // deliberately different

        // Stub findById to return the fresh (different) trade object
        when(paperTradeRepository.findById(originalParam.getId()))
            .thenReturn(Mono.just(freshlyFetchedFromDb));

        // Stub save() to echo back the actual mutated trade object
        when(paperTradeRepository.save(any(PaperTrade.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act & Assert
        StepVerifier.create(engine.closeIronCondorTrade(originalParam))
            .expectNextMatches(result ->
                result.getStatus() == TradeStatus.CLOSED &&
                result.getRealizedPnl() == -10.5 &&  // ORIGINAL param's unrealizedPnl
                result.getUnrealizedPnl() == 0.0 &&
                result.getExitReason().equals("DTE_EXIT")  // ORIGINAL param's exitReason
            )
            .verifyComplete();

        // Verify save() was called exactly once
        verify(paperTradeRepository, times(1)).save(any(PaperTrade.class));

        // Verify sendAlert() was called exactly once
        verify(telegramNotificationBot, times(1)).sendAlert(any());
    }

    /**
     * Test 2: closeIronCondorTrade() - CLOSED status → skips save and alert.
     * <p>
     * Verifies that when the re-fetched trade is already CLOSED, the method:
     * - Returns the freshly-fetched trade unchanged
     * - Does NOT call save()
     * - Does NOT call sendAlert()
     * <p>
     * This is the concurrency guard in action: if another thread already closed the trade,
     * this method detects it and skips the duplicate close attempt.
     */
    @Test
    @DisplayName("closeIronCondorTrade: CLOSED status → skips save and alert")
    void closeIronCondorTrade_ClosedStatus_SkipsSaveAndAlert() {
        // Arrange
        PaperTrade trade = createOpenIronCondorTrade();
        PaperTrade freshTrade = createClosedIronCondorTrade();
        freshTrade.setId(trade.getId());

        // Stub findById to return the fresh CLOSED trade
        when(paperTradeRepository.findById(trade.getId()))
            .thenReturn(Mono.just(freshTrade));

        // NOTE: save() is NOT stubbed here — it should never be called

        // Act & Assert
        StepVerifier.create(engine.closeIronCondorTrade(trade))
            .expectNextMatches(result -> result.getStatus() == TradeStatus.CLOSED)
            .verifyComplete();

        // Verify save() was NOT called
        verify(paperTradeRepository, never()).save(any(PaperTrade.class));

        // Verify sendAlert() was NOT called
        verify(telegramNotificationBot, never()).sendAlert(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SPREAD CLOSE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Test 3: closeSpreadTrade() - OPEN status → proceeds with save and alert.
     * <p>
     * Verifies that when the re-fetched trade is still OPEN, the method:
     * - Mutates the ORIGINAL parameter (not the freshly-fetched object)
     * - Sets status to CLOSED, realizedPnl = unrealizedPnl, unrealizedPnl = 0.0
     * - Calls save() exactly once
     * - Calls sendAlert() exactly once
     * <p>
     * Uses two distinct trade objects to verify the method mutates the original parameter,
     * not the freshly-fetched object from the DB.
     */
    @Test
    @DisplayName("closeSpreadTrade: OPEN status → proceeds with save and alert, mutates original param")
    void closeSpreadTrade_OpenStatus_ProceedsWithSaveAndAlert() {
        // Arrange: Create two distinct trade objects
        PaperTrade originalParam = createOpenSpreadTrade();
        originalParam.setExitReason("LEG_BREACH");
        originalParam.setUnrealizedPnl(-5.25);

        PaperTrade freshlyFetchedFromDb = createOpenSpreadTrade();
        freshlyFetchedFromDb.setId(originalParam.getId());
        freshlyFetchedFromDb.setExitReason(null);  // deliberately different
        freshlyFetchedFromDb.setUnrealizedPnl(0.0);  // deliberately different

        // Stub findById to return the fresh (different) trade object
        when(paperTradeRepository.findById(originalParam.getId()))
            .thenReturn(Mono.just(freshlyFetchedFromDb));

        // Stub save() to echo back the actual mutated trade object
        when(paperTradeRepository.save(any(PaperTrade.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        // Act & Assert
        StepVerifier.create(engine.closeSpreadTrade(originalParam))
            .expectNextMatches(result ->
                result.getStatus() == TradeStatus.CLOSED &&
                result.getRealizedPnl() == -5.25 &&  // ORIGINAL param's unrealizedPnl
                result.getUnrealizedPnl() == 0.0 &&
                result.getExitReason().equals("LEG_BREACH")  // ORIGINAL param's exitReason
            )
            .verifyComplete();

        // Verify save() was called exactly once
        verify(paperTradeRepository, times(1)).save(any(PaperTrade.class));

        // Verify sendAlert() was called exactly once
        verify(telegramNotificationBot, times(1)).sendAlert(any());
    }

    /**
     * Test 4: closeSpreadTrade() - CLOSED status → skips save and alert.
     * <p>
     * Verifies that when the re-fetched trade is already CLOSED, the method:
     * - Returns the freshly-fetched trade unchanged
     * - Does NOT call save()
     * - Does NOT call sendAlert()
     * <p>
     * This is the concurrency guard in action: if another thread already closed the trade,
     * this method detects it and skips the duplicate close attempt.
     */
    @Test
    @DisplayName("closeSpreadTrade: CLOSED status → skips save and alert")
    void closeSpreadTrade_ClosedStatus_SkipsSaveAndAlert() {
        // Arrange
        PaperTrade trade = createOpenSpreadTrade();
        PaperTrade freshTrade = createClosedSpreadTrade();
        freshTrade.setId(trade.getId());

        // Stub findById to return the fresh CLOSED trade
        when(paperTradeRepository.findById(trade.getId()))
            .thenReturn(Mono.just(freshTrade));

        // NOTE: save() is NOT stubbed here — it should never be called

        // Act & Assert
        StepVerifier.create(engine.closeSpreadTrade(trade))
            .expectNextMatches(result -> result.getStatus() == TradeStatus.CLOSED)
            .verifyComplete();

        // Verify save() was NOT called
        verify(paperTradeRepository, never()).save(any(PaperTrade.class));

        // Verify sendAlert() was NOT called
        verify(telegramNotificationBot, never()).sendAlert(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private PaperTrade createOpenIronCondorTrade() {
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("IRON_CONDOR");
        trade.setStatus(TradeStatus.OPEN);
        trade.setEntryCredit(100.0);
        trade.setCurrentValue(100.0);
        trade.setUnrealizedPnl(0.0);
        trade.setEntryTime(LocalDateTime.now());
        trade.setTradeDate(LocalDate.now());

        // 4 legs: SELL 23900 PE, BUY 23800 PE, SELL 24100 CE, BUY 24200 CE
        List<TradeLeg> legs = List.of(
            TradeLeg.builder().action(Action.SELL).optionType(OptionType.PE).strikePrice(23900.0)
                .limitPrice(50.0).quantity(1).expiryDate(LocalDate.now().plusDays(7)).build(),
            TradeLeg.builder().action(Action.BUY).optionType(OptionType.PE).strikePrice(23800.0)
                .limitPrice(40.0).quantity(1).expiryDate(LocalDate.now().plusDays(7)).build(),
            TradeLeg.builder().action(Action.SELL).optionType(OptionType.CE).strikePrice(24100.0)
                .limitPrice(50.0).quantity(1).expiryDate(LocalDate.now().plusDays(7)).build(),
            TradeLeg.builder().action(Action.BUY).optionType(OptionType.CE).strikePrice(24200.0)
                .limitPrice(40.0).quantity(1).expiryDate(LocalDate.now().plusDays(7)).build()
        );
        trade.setLegs(legs);

        return trade;
    }

    private PaperTrade createClosedIronCondorTrade() {
        PaperTrade trade = createOpenIronCondorTrade();
        trade.setStatus(TradeStatus.CLOSED);
        trade.setExitTime(LocalDateTime.now());
        trade.setRealizedPnl(10.0);
        trade.setUnrealizedPnl(0.0);
        return trade;
    }

    private PaperTrade createOpenSpreadTrade() {
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.randomUUID());
        trade.setSymbol("NIFTY50");
        trade.setStrategyName("BULL_PUT_SPREAD");
        trade.setStatus(TradeStatus.OPEN);
        trade.setEntryCredit(50.0);
        trade.setCurrentValue(50.0);
        trade.setUnrealizedPnl(0.0);
        trade.setEntryTime(LocalDateTime.now());
        trade.setTradeDate(LocalDate.now());

        // 2 legs: SELL 23900 PE, BUY 23800 PE
        List<TradeLeg> legs = List.of(
            TradeLeg.builder().action(Action.SELL).optionType(OptionType.PE).strikePrice(23900.0)
                .limitPrice(50.0).quantity(1).expiryDate(LocalDate.now().plusDays(7)).build(),
            TradeLeg.builder().action(Action.BUY).optionType(OptionType.PE).strikePrice(23800.0)
                .limitPrice(40.0).quantity(1).expiryDate(LocalDate.now().plusDays(7)).build()
        );
        trade.setLegs(legs);

        return trade;
    }

    private PaperTrade createClosedSpreadTrade() {
        PaperTrade trade = createOpenSpreadTrade();
        trade.setStatus(TradeStatus.CLOSED);
        trade.setExitTime(LocalDateTime.now());
        trade.setRealizedPnl(5.0);
        trade.setUnrealizedPnl(0.0);
        return trade;
    }
}
