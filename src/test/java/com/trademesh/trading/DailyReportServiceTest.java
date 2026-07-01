package com.trademesh.trading;

import com.trademesh.config.LotSizeService;
import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for DailyReportService.computeMetrics().
 * <p>
 * Tests cover:
 * - Empty trade list handling (zero metrics, no exceptions)
 * - Single trade edge case (no discrimination errors, bestTrade == worstTrade)
 * - Tied realizedPnl with deterministic tie-breaking by exitTime
 * - Known production fixture regression guard (verified 2026-07-01 data)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyReportService.computeMetrics() Tests")
class DailyReportServiceTest {

    @Mock
    private LotSizeService lotSizeService;

    private DailyReportService dailyReportService;

    @BeforeEach
    void setUp() {
        // Shared stub for LotSizeService: NIFTY50 → 65 lots
        // Marked lenient() to avoid STRICT_STUBS failure in test 1 (empty list)
        // where getLotSize() is never called
        lenient().when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);

        dailyReportService = new DailyReportService(null, null, lotSizeService);
    }

    /**
     * Test case 1: Empty list returns zeroed metrics.
     * <p>
     * Verifies that computeMetrics() handles empty input gracefully:
     * - No exception is thrown
     * - All metrics are zeroed (totalTrades=0, winRate=0.0, etc.)
     * - bestTrade and worstTrade are null
     * <p>
     * This is the only way to test this branch via unit test, since the
     * REST endpoint has no date parameter and cannot be called with empty results.
     */
    @Test
    @DisplayName("computeMetrics_emptyList_returnsZeroedMetrics")
    void computeMetrics_emptyList_returnsZeroedMetrics() {
        // Arrange
        List<PaperTrade> trades = Collections.emptyList();

        // Act
        DailyReportService.DailyReportMetrics metrics = dailyReportService.computeMetrics(trades);

        // Assert
        assertNotNull(metrics, "Metrics should never be null");
        assertEquals(0, metrics.totalTrades, "totalTrades should be 0");
        assertEquals(0, metrics.winningTrades, "winningTrades should be 0");
        assertEquals(0, metrics.losingTrades, "losingTrades should be 0");
        assertEquals(0.0, metrics.totalPnl, "totalPnl should be 0.0");
        assertEquals(0.0, metrics.winRate, "winRate should be 0.0");
        assertNull(metrics.bestTrade, "bestTrade should be null");
        assertNull(metrics.worstTrade, "worstTrade should be null");
        assertEquals(0.0, metrics.totalPnlAmount, "totalPnlAmount should be 0.0");
        assertEquals(0.0, metrics.bestPnlAmount, "bestPnlAmount should be 0.0");
        assertEquals(0.0, metrics.worstPnlAmount, "worstPnlAmount should be 0.0");
    }

    /**
     * Test case 2: Single trade with no discrimination errors.
     * <p>
     * Verifies that computeMetrics() correctly handles a single trade:
     * - totalTrades = 1
     * - bestTrade and worstTrade both reference the same trade
     * - winRate is 100.0 (winning trade) or 0.0 (losing trade) based on sign
     * - No divide-by-zero or NaN errors
     */
    @Test
    @DisplayName("computeMetrics_singleTrade_noDiscriminationErrors")
    void computeMetrics_singleTrade_noDiscriminationErrors() {
        // Arrange: Single winning trade
        PaperTrade trade = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(50.0)
                .entryCredit(100.0)
                .currentValue(50.0)
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .entryTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .exitTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .exitReason("TARGET_HIT")
                .build();

        List<PaperTrade> trades = List.of(trade);

        // Act
        DailyReportService.DailyReportMetrics metrics = dailyReportService.computeMetrics(trades);

        // Assert
        assertEquals(1, metrics.totalTrades, "totalTrades should be 1");
        assertEquals(1, metrics.winningTrades, "winningTrades should be 1 (positive PnL)");
        assertEquals(0, metrics.losingTrades, "losingTrades should be 0");
        assertEquals(50.0, metrics.totalPnl, "totalPnl should equal the single trade's PnL");
        assertEquals(100.0, metrics.winRate, "winRate should be 100.0 for a single winning trade");
        assertSame(trade, metrics.bestTrade, "bestTrade should be the same trade object");
        assertSame(trade, metrics.worstTrade, "worstTrade should be the same trade object");
        assertEquals(50.0 * 65, metrics.totalPnlAmount, "totalPnlAmount should be realizedPnl * lotSize");
        assertEquals(50.0 * 65, metrics.bestPnlAmount, "bestPnlAmount should be realizedPnl * lotSize");
        assertEquals(50.0 * 65, metrics.worstPnlAmount, "worstPnlAmount should be realizedPnl * lotSize");
    }

    /**
     * Test case 3: Tied realizedPnl breaks by exitTime ascending.
     * <p>
     * Verifies that when two trades have identical realizedPnl, the Comparator
     * chain correctly uses exitTime as a tie-breaker, selecting the trade with
     * the EARLIER exitTime for both bestTrade and worstTrade.
     * <p>
     * This is the critical fix for non-deterministic tie-breaking and has never
     * been exercised in production or prior tests.
     */
    @Test
    @DisplayName("computeMetrics_tiedRealizedPnl_breaksTieByExitTimeAscending")
    void computeMetrics_tiedRealizedPnl_breaksTieByExitTimeAscending() {
        // Arrange: Two trades with identical realizedPnl but different exitTime
        LocalDateTime earlierTime = LocalDateTime.of(2026, 7, 1, 9, 30, 0);
        LocalDateTime laterTime = LocalDateTime.of(2026, 7, 1, 10, 30, 0);

        PaperTrade tradeWithEarlierExit = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(75.0)  // Tied PnL
                .entryCredit(100.0)
                .currentValue(25.0)
                .tradeDate(LocalDate.of(2026, 7, 1))
                .entryTime(LocalDateTime.of(2026, 7, 1, 9, 0, 0))
                .exitTime(earlierTime)  // Earlier exit
                .exitReason("TARGET_HIT")
                .build();

        PaperTrade tradeWithLaterExit = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(75.0)  // Tied PnL
                .entryCredit(100.0)
                .currentValue(25.0)
                .tradeDate(LocalDate.of(2026, 7, 1))
                .entryTime(LocalDateTime.of(2026, 7, 1, 9, 15, 0))
                .exitTime(laterTime)  // Later exit
                .exitReason("TARGET_HIT")
                .build();

        List<PaperTrade> trades = List.of(tradeWithLaterExit, tradeWithEarlierExit);

        // Act
        DailyReportService.DailyReportMetrics metrics = dailyReportService.computeMetrics(trades);

        // Assert: Both bestTrade and worstTrade should resolve to the trade with earlier exitTime
        // (since they have identical PnL, the tie-breaker applies to both)
        assertSame(tradeWithEarlierExit, metrics.bestTrade,
                "bestTrade should be the trade with earlier exitTime when PnL is tied");
        assertSame(tradeWithEarlierExit, metrics.worstTrade,
                "worstTrade should be the trade with earlier exitTime when PnL is tied");
    }

    /**
     * Test case 4: Known fixture matches verified production figures.
     * <p>
     * Regression guard: reconstructs the exact 3 trades verified in production
     * on 2026-07-01 and asserts all metrics match the already-verified REST/Telegram output.
     * <p>
     * If this test ever fails after a change to computeMetrics(), it indicates
     * the change broke the logic that was already verified against live data.
     */
    @Test
    @DisplayName("computeMetrics_knownFixture_matchesVerifiedProductionFigures")
    void computeMetrics_knownFixture_matchesVerifiedProductionFigures() {
        // Arrange: Reconstruct the 3 verified trades from 2026-07-01
        LocalDate tradeDate = LocalDate.of(2026, 7, 1);

        PaperTrade tradeA = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(52.50)
                .entryCredit(100.0)
                .currentValue(47.50)
                .tradeDate(tradeDate)
                .entryTime(LocalDateTime.of(2026, 7, 1, 9, 15, 0))
                .exitTime(LocalDateTime.of(2026, 7, 1, 10, 0, 0))
                .exitReason("TARGET_HIT")
                .build();

        PaperTrade tradeB = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(116.25)  // Best trade
                .entryCredit(150.0)
                .currentValue(33.75)
                .tradeDate(tradeDate)
                .entryTime(LocalDateTime.of(2026, 7, 1, 9, 30, 0))
                .exitTime(LocalDateTime.of(2026, 7, 1, 11, 0, 0))
                .exitReason("TARGET_HIT")
                .build();

        PaperTrade tradeC = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(-1.05)  // Worst trade
                .entryCredit(100.0)
                .currentValue(101.05)
                .tradeDate(tradeDate)
                .entryTime(LocalDateTime.of(2026, 7, 1, 10, 0, 0))
                .exitTime(LocalDateTime.of(2026, 7, 1, 14, 30, 0))
                .exitReason("STOP_LOSS")
                .build();

        List<PaperTrade> trades = List.of(tradeA, tradeB, tradeC);

        // Act
        DailyReportService.DailyReportMetrics metrics = dailyReportService.computeMetrics(trades);

        // Assert: Exact values matching verified production output
        assertEquals(3, metrics.totalTrades, "totalTrades should be 3");
        assertEquals(2, metrics.winningTrades, "winningTrades should be 2 (A and B are positive)");
        assertEquals(1, metrics.losingTrades, "losingTrades should be 1 (C is negative)");

        // Total PnL: 52.50 + 116.25 + (-1.05) = 167.70
        assertEquals(167.70, metrics.totalPnl, 0.001,
                "totalPnl should be 167.70 (within 0.001 delta for floating point)");

        // Win rate: 2 wins / 3 trades * 100 = 66.666...
        // Assert the RAW double, not the rounded display string
        assertEquals(66.6667, metrics.winRate, 0.001,
                "winRate should be 66.6667 (raw double, not rounded for display)");

        // Best and worst trades
        assertSame(tradeB, metrics.bestTrade, "bestTrade should be tradeB (116.25)");
        assertEquals(116.25, metrics.bestTrade.getRealizedPnl(), "bestTrade.realizedPnl should be 116.25");

        assertSame(tradeC, metrics.worstTrade, "worstTrade should be tradeC (-1.05)");
        assertEquals(-1.05, metrics.worstTrade.getRealizedPnl(), "worstTrade.realizedPnl should be -1.05");

        // Rupee amounts (points * lotSize)
        // Total: (52.50 + 116.25 + (-1.05)) * 65 = 167.70 * 65 = 10,900.50
        assertEquals(167.70 * 65, metrics.totalPnlAmount, 0.01,
                "totalPnlAmount should be totalPnl * lotSize");

        // Best: 116.25 * 65 = 7,556.25
        assertEquals(116.25 * 65, metrics.bestPnlAmount, 0.01,
                "bestPnlAmount should be bestTrade.realizedPnl * lotSize");

        // Worst: -1.05 * 65 = -68.25
        assertEquals(-1.05 * 65, metrics.worstPnlAmount, 0.01,
                "worstPnlAmount should be worstTrade.realizedPnl * lotSize");
    }
}
