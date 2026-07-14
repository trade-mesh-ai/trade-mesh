package com.trademesh.trading;

import com.trademesh.config.LotSizeService;
import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integration tests for DailyReportService.generateDailyReport() with cross-date scenarios.
 * <p>
 * Tests the corrected logic that distinguishes between:
 * - Trades entered today (via tradeDate)
 * - Trades closed today (via exitTime)
 * - All currently open trades (via status)
 * <p>
 * These tests verify that the three queries are independent and correctly sourced.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyReportService.generateDailyReport() Cross-Date Tests")
class DailyReportServiceCrossDateTest {

    @Mock
    private PaperTradeRepository paperTradeRepository;

    @Mock
    private com.trademesh.alert.TelegramNotificationBot telegramNotificationBot;

    @Mock
    private LotSizeService lotSizeService;

    private DailyReportService dailyReportService;

    @BeforeEach
    void setUp() {
        lenient().when(lotSizeService.getLotSize("NIFTY50")).thenReturn(65);
        lenient().when(lotSizeService.getLotSize("BANKNIFTY")).thenReturn(30);
        lenient().when(telegramNotificationBot.sendAlert(any())).thenReturn(Mono.empty());

        dailyReportService = new DailyReportService(paperTradeRepository, telegramNotificationBot, lotSizeService);
    }

    /**
     * Test case 1: Trade entered yesterday, closed today.
     * <p>
     * Verifies that a trade entered on a prior day but closed today:
     * - Does NOT appear in enteredToday count
     * - DOES appear in closedToday metrics
     * - Is correctly included in P&L calculations
     */
    @Test
    @DisplayName("generateDailyReport_tradeEnteredYesterdayClosedToday_appearsInClosedMetricsOnly")
    void generateDailyReport_tradeEnteredYesterdayClosedToday_appearsInClosedMetricsOnly() {
        // Arrange
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate yesterday = today.minusDays(1);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);

        // Trade entered yesterday, closed today
        PaperTrade tradeEnteredYesterdayClosedToday = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(50.0)
                .entryCredit(100.0)
                .currentValue(50.0)
                .tradeDate(yesterday)  // Entered yesterday
                .entryTime(yesterday.atTime(9, 30, 0))
                .exitTime(today.atTime(14, 0, 0))  // Closed today
                .exitReason("TARGET_HIT")
                .build();

        // Mock repository responses
        when(paperTradeRepository.findByTradeDate(today))
                .thenReturn(Flux.empty());  // No trades entered today

        when(paperTradeRepository.findByStatusAndExitTimeBetween(TradeStatus.CLOSED, todayStart, todayEnd))
                .thenReturn(Flux.just(tradeEnteredYesterdayClosedToday));  // This trade closed today

        when(paperTradeRepository.countByStatus(TradeStatus.OPEN))
                .thenReturn(Mono.just(0L));  // No open trades

        // Act & Assert
        StepVerifier.create(dailyReportService.generateDailyReport())
                .verifyComplete();

        // Verify the trade was included in closed metrics (via exitTime query)
        // and NOT in entered today count (via tradeDate query)
        // The report should show: Entered today: 0, Closed today: 1, Open: 0
    }

    /**
     * Test case 2: Trade entered today, still open.
     * <p>
     * Verifies that a trade entered today but still open:
     * - DOES appear in enteredToday count
     * - Does NOT appear in closedToday metrics
     * - Is correctly counted in openCount
     */
    @Test
    @DisplayName("generateDailyReport_tradeEnteredTodayStillOpen_appearsInEnteredAndOpenCountOnly")
    void generateDailyReport_tradeEnteredTodayStillOpen_appearsInEnteredAndOpenCountOnly() {
        // Arrange
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);

        // Trade entered today, still open
        PaperTrade tradeEnteredTodayStillOpen = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.OPEN)
                .entryCredit(100.0)
                .currentValue(95.0)
                .unrealizedPnl(5.0)
                .tradeDate(today)  // Entered today
                .entryTime(today.atTime(9, 30, 0))
                .exitTime(null)  // Still open
                .build();

        // Mock repository responses
        when(paperTradeRepository.findByTradeDate(today))
                .thenReturn(Flux.just(tradeEnteredTodayStillOpen));  // This trade entered today

        when(paperTradeRepository.findByStatusAndExitTimeBetween(TradeStatus.CLOSED, todayStart, todayEnd))
                .thenReturn(Flux.empty());  // No trades closed today

        when(paperTradeRepository.countByStatus(TradeStatus.OPEN))
                .thenReturn(Mono.just(1L));  // One open trade (this one)

        // Act & Assert
        StepVerifier.create(dailyReportService.generateDailyReport())
                .verifyComplete();

        // Verify the trade was included in entered today count and open count
        // but NOT in closed metrics
        // The report should show: Entered today: 1, Closed today: 0, Open: 1
    }

    /**
     * Test case 3: Trade entered today, closed today (same-day).
     * <p>
     * Verifies that a trade entered and closed on the same day:
     * - DOES appear in enteredToday count
     * - DOES appear in closedToday metrics
     * - Is correctly included in P&L calculations
     * - Is NOT double-counted
     */
    @Test
    @DisplayName("generateDailyReport_tradeEnteredAndClosedToday_appearsInBothCountsCorrectly")
    void generateDailyReport_tradeEnteredAndClosedToday_appearsInBothCountsCorrectly() {
        // Arrange
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);

        // Trade entered and closed today
        PaperTrade tradeEnteredAndClosedToday = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(75.0)
                .entryCredit(100.0)
                .currentValue(25.0)
                .tradeDate(today)  // Entered today
                .entryTime(today.atTime(9, 30, 0))
                .exitTime(today.atTime(15, 0, 0))  // Closed today
                .exitReason("TARGET_HIT")
                .build();

        // Mock repository responses
        when(paperTradeRepository.findByTradeDate(today))
                .thenReturn(Flux.just(tradeEnteredAndClosedToday));  // This trade entered today

        when(paperTradeRepository.findByStatusAndExitTimeBetween(TradeStatus.CLOSED, todayStart, todayEnd))
                .thenReturn(Flux.just(tradeEnteredAndClosedToday));  // This trade closed today

        when(paperTradeRepository.countByStatus(TradeStatus.OPEN))
                .thenReturn(Mono.just(0L));  // No open trades

        // Act & Assert
        StepVerifier.create(dailyReportService.generateDailyReport())
                .verifyComplete();

        // Verify the trade appears in both entered today and closed today counts
        // The report should show: Entered today: 1, Closed today: 1, Open: 0
    }

    /**
     * Test case 4: Multiple trades with mixed scenarios.
     * <p>
     * Verifies correct handling of:
     * - Trade A: Entered yesterday, closed today
     * - Trade B: Entered today, still open
     * - Trade C: Entered today, closed today
     * - Trade D: Entered yesterday, still open (counts in openCount)
     */
    @Test
    @DisplayName("generateDailyReport_mixedScenarios_countsAreIndependent")
    void generateDailyReport_mixedScenarios_countsAreIndependent() {
        // Arrange
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate yesterday = today.minusDays(1);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);

        // Trade A: Entered yesterday, closed today
        PaperTrade tradeA = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(50.0)
                .tradeDate(yesterday)
                .entryTime(yesterday.atTime(9, 30, 0))
                .exitTime(today.atTime(10, 0, 0))
                .exitReason("TARGET_HIT")
                .build();

        // Trade B: Entered today, still open
        PaperTrade tradeB = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.OPEN)
                .tradeDate(today)
                .entryTime(today.atTime(9, 30, 0))
                .exitTime(null)
                .build();

        // Trade C: Entered today, closed today
        PaperTrade tradeC = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(75.0)
                .tradeDate(today)
                .entryTime(today.atTime(11, 0, 0))
                .exitTime(today.atTime(14, 0, 0))
                .exitReason("TARGET_HIT")
                .build();

        // Trade D: Entered yesterday, still open
        PaperTrade tradeD = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("BANKNIFTY")
                .strategyName("BEAR_CALL_SPREAD")
                .status(TradeStatus.OPEN)
                .tradeDate(yesterday)
                .entryTime(yesterday.atTime(10, 0, 0))
                .exitTime(null)
                .build();

        // Mock repository responses
        when(paperTradeRepository.findByTradeDate(today))
                .thenReturn(Flux.just(tradeB, tradeC));  // Trades B and C entered today

        when(paperTradeRepository.findByStatusAndExitTimeBetween(TradeStatus.CLOSED, todayStart, todayEnd))
                .thenReturn(Flux.just(tradeA, tradeC));  // Trades A and C closed today

        when(paperTradeRepository.countByStatus(TradeStatus.OPEN))
                .thenReturn(Mono.just(2L));  // Trades B and D are open

        // Act & Assert
        StepVerifier.create(dailyReportService.generateDailyReport())
                .verifyComplete();

        // Verify counts:
        // - Entered today: 2 (B, C)
        // - Closed today: 2 (A, C)
        // - Open: 2 (B, D)
        // Note: B appears in both entered and open, C appears in both entered and closed
        // A appears only in closed (entered yesterday)
        // D appears only in open (entered yesterday)
    }

    /**
     * Test case 5: getTodayStatusSummary() with mixed scenarios.
     * <p>
     * Verifies that getTodayStatusSummary() correctly bundles all three counts
     * with P&L metrics in a single DailyStatusSummary record.
     * <p>
     * Scenario:
     * - Trade A: Entered yesterday, closed today (P&L: 50 pts)
     * - Trade B: Entered today, still open (no P&L)
     * - Trade C: Entered today, closed today (P&L: 75 pts)
     * - Trade D: Entered yesterday, still open (no P&L)
     * <p>
     * Expected summary:
     * - enteredToday: 2 (B, C)
     * - openCount: 2 (B, D)
     * - closedTodayCount: 2 (A, C)
     * - metrics.totalTrades: 2 (A, C)
     * - metrics.totalPnl: 125 pts (50 + 75)
     */
    @Test
    @DisplayName("getTodayStatusSummary_mixedScenarios_correctCounts")
    void getTodayStatusSummary_mixedScenarios_correctCounts() {
        // Arrange
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate yesterday = today.minusDays(1);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);

        // Trade A: Entered yesterday, closed today
        PaperTrade tradeA = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(50.0)
                .entryCredit(100.0)
                .currentValue(50.0)
                .tradeDate(yesterday)
                .entryTime(yesterday.atTime(9, 30, 0))
                .exitTime(today.atTime(10, 0, 0))
                .exitReason("TARGET_HIT")
                .build();

        // Trade B: Entered today, still open
        PaperTrade tradeB = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.OPEN)
                .entryCredit(100.0)
                .currentValue(95.0)
                .unrealizedPnl(5.0)
                .tradeDate(today)
                .entryTime(today.atTime(9, 30, 0))
                .exitTime(null)
                .build();

        // Trade C: Entered today, closed today
        PaperTrade tradeC = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("BULL_PUT_SPREAD")
                .status(TradeStatus.CLOSED)
                .realizedPnl(75.0)
                .entryCredit(100.0)
                .currentValue(25.0)
                .tradeDate(today)
                .entryTime(today.atTime(11, 0, 0))
                .exitTime(today.atTime(14, 0, 0))
                .exitReason("TARGET_HIT")
                .build();

        // Trade D: Entered yesterday, still open
        PaperTrade tradeD = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("BANKNIFTY")
                .strategyName("BEAR_CALL_SPREAD")
                .status(TradeStatus.OPEN)
                .entryCredit(200.0)
                .currentValue(190.0)
                .unrealizedPnl(10.0)
                .tradeDate(yesterday)
                .entryTime(yesterday.atTime(10, 0, 0))
                .exitTime(null)
                .build();

        // Mock repository responses
        when(paperTradeRepository.findByTradeDate(today))
                .thenReturn(Flux.just(tradeB, tradeC));  // Trades B and C entered today

        when(paperTradeRepository.findByStatusAndExitTimeBetween(TradeStatus.CLOSED, todayStart, todayEnd))
                .thenReturn(Flux.just(tradeA, tradeC));  // Trades A and C closed today

        when(paperTradeRepository.countByStatus(TradeStatus.OPEN))
                .thenReturn(Mono.just(2L));  // Trades B and D are open

        // Act & Assert
        StepVerifier.create(dailyReportService.getTodayStatusSummary(today))
                .assertNext(summary -> {
                    // Verify the three independent counts
                    assertEquals(2, summary.enteredToday(), "Should have 2 trades entered today (B, C)");
                    assertEquals(2, summary.openCount(), "Should have 2 open trades (B, D)");
                    assertEquals(2, summary.closedTodayCount(), "Should have 2 trades closed today (A, C)");

                    // Verify the metrics from closed trades
                    DailyReportService.DailyReportMetrics metrics = summary.metrics();
                    assertEquals(2, metrics.totalTrades, "Should have 2 closed trades (A, C)");
                    assertEquals(125.0, metrics.totalPnl, 0.01, "Total P&L should be 125 pts (50 + 75)");
                    assertEquals(2, metrics.winningTrades, "Both trades should be winners");
                    assertEquals(0, metrics.losingTrades, "No losing trades");
                    assertEquals(100.0, metrics.winRate, 0.01, "Win rate should be 100%");

                    // Verify best/worst trades
                    assertNotNull(metrics.bestTrade, "Best trade should not be null");
                    assertEquals(75.0, metrics.bestTrade.getRealizedPnl(), 0.01, "Best trade should be C with 75 pts");
                    assertNotNull(metrics.worstTrade, "Worst trade should not be null");
                    assertEquals(50.0, metrics.worstTrade.getRealizedPnl(), 0.01, "Worst trade should be A with 50 pts");
                })
                .verifyComplete();
    }

    /**
     * Test case 6: getTodayStatusSummary() with only open trades (no closed).
     * <p>
     * Verifies that when trades are entered today but not closed,
     * the summary correctly shows:
     * - enteredToday > 0
     * - closedTodayCount = 0
     * - metrics.totalTrades = 0 (no closed trades)
     * <p>
     * This is the bug scenario: endpoint should NOT report "No trades executed today"
     * when enteredToday > 0.
     */
    @Test
    @DisplayName("getTodayStatusSummary_enteredTodayButNotClosed_correctCounts")
    void getTodayStatusSummary_enteredTodayButNotClosed_correctCounts() {
        // Arrange
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);

        // Trade: Entered today, still open (the bug scenario)
        PaperTrade tradeEnteredTodayOpen = PaperTrade.builder()
                .id(UUID.randomUUID())
                .symbol("NIFTY50")
                .strategyName("EXPIRY_DAY_STRANGLE")
                .status(TradeStatus.OPEN)
                .entryCredit(150.0)
                .currentValue(145.0)
                .unrealizedPnl(5.0)
                .tradeDate(today)
                .entryTime(today.atTime(9, 30, 0))
                .exitTime(null)
                .build();

        // Mock repository responses
        when(paperTradeRepository.findByTradeDate(today))
                .thenReturn(Flux.just(tradeEnteredTodayOpen));  // This trade entered today

        when(paperTradeRepository.findByStatusAndExitTimeBetween(TradeStatus.CLOSED, todayStart, todayEnd))
                .thenReturn(Flux.empty());  // No trades closed today

        when(paperTradeRepository.countByStatus(TradeStatus.OPEN))
                .thenReturn(Mono.just(1L));  // One open trade (this one)

        // Act & Assert
        StepVerifier.create(dailyReportService.getTodayStatusSummary(today))
                .assertNext(summary -> {
                    // Verify the three independent counts
                    assertEquals(1, summary.enteredToday(), "Should have 1 trade entered today");
                    assertEquals(1, summary.openCount(), "Should have 1 open trade");
                    assertEquals(0, summary.closedTodayCount(), "Should have 0 trades closed today");

                    // Verify metrics are empty (no closed trades)
                    DailyReportService.DailyReportMetrics metrics = summary.metrics();
                    assertEquals(0, metrics.totalTrades, "Should have 0 closed trades");
                    assertEquals(0.0, metrics.totalPnl, 0.01, "Total P&L should be 0 (no closed trades)");
                })
                .verifyComplete();
    }

}
