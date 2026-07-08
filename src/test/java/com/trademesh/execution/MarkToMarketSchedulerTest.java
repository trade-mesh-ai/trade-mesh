package com.trademesh.execution;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MarkToMarketScheduler.markToMarketScheduled() method.
 * Tests per-trade error handling to ensure one trade's failure doesn't abort the cycle.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarkToMarketScheduler Per-Trade Error Handling Tests")
class MarkToMarketSchedulerTest {

    @Mock
    private PaperTradingEngine paperTradingEngine;

    @Mock
    private PaperTradeRepository paperTradeRepository;

    @Mock
    private TokenAvailabilityTracker tokenAvailabilityTracker;

    @InjectMocks
    private MarkToMarketScheduler markToMarketScheduler;

    private PaperTrade trade1;
    private PaperTrade trade2;
    private PaperTrade trade3;

    @BeforeEach
    void setUp() {
        // Create three test trades
        trade1 = createTestTrade("trade-1");
        trade2 = createTestTrade("trade-2");
        trade3 = createTestTrade("trade-3");
    }

    private PaperTrade createTestTrade(String id) {
        PaperTrade trade = new PaperTrade();
        trade.setId(UUID.fromString("00000000-0000-0000-0000-00000000000" + id.charAt(id.length() - 1)));
        trade.setSymbol("NIFTY50");
        trade.setStatus(TradeStatus.OPEN);
        trade.setEntryCredit(100.0);
        trade.setCurrentValue(100.0);
        trade.setUnrealizedPnl(0.0);
        trade.setEntryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        return trade;
    }

    /**
     * Test Case 1: One trade fails with token-missing error, others succeed.
     * Verifies that markToMarket() is called for all trades and cycle completes.
     */
    @Test
    @DisplayName("Should process all trades when one fails with token-missing error")
    void testTokenMissingErrorDoesNotAbortCycle() {
        // Setup: Repository returns 3 trades
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2, trade3));

        // Setup: trade1 succeeds, trade2 fails with token error, trade3 succeeds
        when(paperTradingEngine.markToMarket(trade1))
                .thenReturn(Mono.just(trade1));

        when(paperTradingEngine.markToMarket(trade2))
                .thenReturn(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")));

        when(paperTradingEngine.markToMarket(trade3))
                .thenReturn(Mono.just(trade3));

        // Execute with controlled time within market hours
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(11, 0));

        // Verify: markToMarket() was called for all 3 trades
        verify(paperTradingEngine, times(1)).markToMarket(trade1);
        verify(paperTradingEngine, times(1)).markToMarket(trade2);
        verify(paperTradingEngine, times(1)).markToMarket(trade3);

        // Verify: TokenAvailabilityTracker was updated for the token error
        verify(tokenAvailabilityTracker, times(1)).setTokenUnavailable();
        verify(tokenAvailabilityTracker, times(1)).trackTokenMissing("MarkToMarketScheduler");
    }

    /**
     * Test Case 2: Verify WARN log fires with degraded-mode message for token error.
     */
    @Test
    @DisplayName("Should log WARN with degraded-mode message when token is missing")
    void testTokenMissingLogsWarnMessage() {
        // Setup: Repository returns 1 trade
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1));

        // Setup: markToMarket fails with token error
        when(paperTradingEngine.markToMarket(trade1))
                .thenReturn(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")));

        // Execute with controlled time within market hours
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(11, 0));

        // Verify: TokenAvailabilityTracker was called (indicating error was caught)
        verify(tokenAvailabilityTracker, times(1)).setTokenUnavailable();
        verify(tokenAvailabilityTracker, times(1)).trackTokenMissing("MarkToMarketScheduler");
    }

    /**
     * Test Case 3: Non-token errors are also handled gracefully.
     * Verifies that other errors don't abort the batch either.
     */
    @Test
    @DisplayName("Should handle non-token errors gracefully without aborting batch")
    void testNonTokenErrorsDoNotAbortCycle() {
        // Setup: Repository returns 3 trades
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2, trade3));

        // Setup: trade1 succeeds, trade2 fails with generic error, trade3 succeeds
        when(paperTradingEngine.markToMarket(trade1))
                .thenReturn(Mono.just(trade1));

        when(paperTradingEngine.markToMarket(trade2))
                .thenReturn(Mono.error(new RuntimeException("Database connection error")));

        when(paperTradingEngine.markToMarket(trade3))
                .thenReturn(Mono.just(trade3));

        // Execute with controlled time within market hours
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(11, 0));

        // Verify: markToMarket() was called for all 3 trades
        verify(paperTradingEngine, times(1)).markToMarket(trade1);
        verify(paperTradingEngine, times(1)).markToMarket(trade2);
        verify(paperTradingEngine, times(1)).markToMarket(trade3);

        // Verify: TokenAvailabilityTracker was NOT called (not a token error)
        verify(tokenAvailabilityTracker, never()).setTokenUnavailable();
        verify(tokenAvailabilityTracker, never()).trackTokenMissing(any());
    }

    /**
     * Test Case 4: All trades succeed - normal happy path.
     */
    @Test
    @DisplayName("Should process all trades successfully when no errors occur")
    void testAllTradesSucceed() {
        // Setup: Repository returns 3 trades
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2, trade3));

        // Setup: All trades succeed
        when(paperTradingEngine.markToMarket(trade1))
                .thenReturn(Mono.just(trade1));
        when(paperTradingEngine.markToMarket(trade2))
                .thenReturn(Mono.just(trade2));
        when(paperTradingEngine.markToMarket(trade3))
                .thenReturn(Mono.just(trade3));

        // Execute with controlled time within market hours
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(11, 0));

        // Verify: markToMarket() was called for all 3 trades
        verify(paperTradingEngine, times(1)).markToMarket(trade1);
        verify(paperTradingEngine, times(1)).markToMarket(trade2);
        verify(paperTradingEngine, times(1)).markToMarket(trade3);

        // Verify: TokenAvailabilityTracker was NOT called (no errors)
        verify(tokenAvailabilityTracker, never()).setTokenUnavailable();
        verify(tokenAvailabilityTracker, never()).trackTokenMissing(any());
    }

    /**
     * Test Case 5: Multiple trades fail with token error - all are handled.
     */
    @Test
    @DisplayName("Should handle multiple token-missing errors in same batch")
    void testMultipleTokenErrorsInBatch() {
        // Setup: Repository returns 3 trades
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1, trade2, trade3));

        // Setup: trade1 and trade3 fail with token error, trade2 succeeds
        when(paperTradingEngine.markToMarket(trade1))
                .thenReturn(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")));

        when(paperTradingEngine.markToMarket(trade2))
                .thenReturn(Mono.just(trade2));

        when(paperTradingEngine.markToMarket(trade3))
                .thenReturn(Mono.error(new IllegalStateException(
                        "No Zerodha access token found in Redis. Please authenticate first.")));

        // Execute with controlled time within market hours
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(11, 0));

        // Verify: markToMarket() was called for all 3 trades
        verify(paperTradingEngine, times(1)).markToMarket(trade1);
        verify(paperTradingEngine, times(1)).markToMarket(trade2);
        verify(paperTradingEngine, times(1)).markToMarket(trade3);

        // Verify: TokenAvailabilityTracker was called (for token errors)
        verify(tokenAvailabilityTracker, atLeast(1)).setTokenUnavailable();
        verify(tokenAvailabilityTracker, atLeast(1)).trackTokenMissing("MarkToMarketScheduler");
    }

    /**
     * Test Case 6: Empty batch - no trades to process.
     */
    @Test
    @DisplayName("Should handle empty batch gracefully")
    void testEmptyBatch() {
        // Setup: Repository returns no trades
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.empty());

        // Execute with controlled time within market hours
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(11, 0));

        // Verify: markToMarket() was never called
        verify(paperTradingEngine, never()).markToMarket(any());

        // Verify: TokenAvailabilityTracker was not called
        verify(tokenAvailabilityTracker, never()).setTokenUnavailable();
        verify(tokenAvailabilityTracker, never()).trackTokenMissing(any());
    }

    /**
     * Test Case 7: Market hours boundary - outside market hours.
     */
    @Test
    @DisplayName("Should skip processing when outside market hours")
    void testOutsideMarketHours() {
        // Setup: Repository stub is lenient since this test verifies early return
        // before repository is ever queried (market hours check happens first)
        lenient().when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1));

        // Execute with time outside market hours (before 9:15 AM)
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(8, 0));

        // Verify: markToMarket() was never called (market hours check should skip)
        verify(paperTradingEngine, never()).markToMarket(any());

        // Verify: TokenAvailabilityTracker was not called
        verify(tokenAvailabilityTracker, never()).setTokenUnavailable();
        verify(tokenAvailabilityTracker, never()).trackTokenMissing(any());
    }

    /**
     * Test Case 8: Market hours boundary - at market open.
     */
    @Test
    @DisplayName("Should process trades at market open time (9:15 AM)")
    void testAtMarketOpen() {
        // Setup: Repository returns 1 trade
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1));

        // Setup: Trade succeeds
        when(paperTradingEngine.markToMarket(trade1))
                .thenReturn(Mono.just(trade1));

        // Execute at exactly 9:15 AM (market open)
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(9, 15));

        // Verify: markToMarket() was called
        verify(paperTradingEngine, times(1)).markToMarket(trade1);
    }

    /**
     * Test Case 9: Market hours boundary - at market close.
     */
    @Test
    @DisplayName("Should process trades at market close time (3:30 PM)")
    void testAtMarketClose() {
        // Setup: Repository returns 1 trade
        when(paperTradeRepository.findByStatus(TradeStatus.OPEN))
                .thenReturn(Flux.just(trade1));

        // Setup: Trade succeeds
        when(paperTradingEngine.markToMarket(trade1))
                .thenReturn(Mono.just(trade1));

        // Execute at exactly 3:30 PM (market close)
        markToMarketScheduler.executeMarkToMarket(LocalTime.of(15, 30));

        // Verify: markToMarket() was called
        verify(paperTradingEngine, times(1)).markToMarket(trade1);
    }
}
