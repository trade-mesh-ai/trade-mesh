package com.trademesh.trading;

import com.trademesh.config.LotSizeService;
import com.trademesh.config.TradeMeshTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Controller-level test for TradingController.getTodayReport().
 * <p>
 * Verifies the HTTP response shape for various daily report scenarios:
 * - No trades executed (all three counts zero)
 * - Trades entered but not closed (the bug scenario)
 * - Trades closed with P&L metrics
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@Import(TradeMeshTestConfiguration.class)
@DisplayName("TradingController.getTodayReport() Tests")
class TradingControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private DailyReportService dailyReportService;

    @MockBean
    private LotSizeService lotSizeService;

    /**
     * Test: getTodayReport returns correct JSON shape for empty-day (all three counts zero).
     * <p>
     * Verifies that when all three counts are zero (enteredToday=0, openCount=0, closedTodayCount=0),
     * the controller renders the HTTP response with EXACTLY:
     * <pre>
     * {
     *   "date": "<today's date>",
     *   "enteredToday": 0,
     *   "currentlyOpen": 0,
     *   "closedToday": 0,
     *   "message": "No trades executed today"
     * }
     * </pre>
     * <p>
     * Confirms that no other keys are present (no closedTradesSummary, etc.),
     * matching the controller's early-return branch for zero-activity days.
     */
    @Test
    @DisplayName("getTodayReport_emptyDay_returnsCorrectJsonShape")
    void getTodayReport_emptyDay_returnsCorrectJsonShape() {
        // Arrange: Create a summary with all three counts at zero
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DailyReportService.DailyReportMetrics zeroedMetrics = new DailyReportService.DailyReportMetrics();
        DailyReportService.DailyStatusSummary emptySummary = new DailyReportService.DailyStatusSummary(
                0, 0, 0, zeroedMetrics
        );

        // Stub DailyReportService to return the empty summary for any date
        lenient().when(dailyReportService.getTodayStatusSummary(any(LocalDate.class)))
                .thenReturn(Mono.just(emptySummary));

        // Act & Assert: Call GET /trading/report/today and verify JSON response
        webTestClient.get()
                .uri("/trading/report/today")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/json")
                .expectBody()
                // Verify exact JSON structure for empty-day response
                .jsonPath("$.date").isEqualTo(today.toString())
                .jsonPath("$.enteredToday").isEqualTo(0)
                .jsonPath("$.currentlyOpen").isEqualTo(0)
                .jsonPath("$.closedToday").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("No trades executed today")
                // Verify that closedTradesSummary is NOT present (no closed trades)
                .jsonPath("$.closedTradesSummary").doesNotExist();
    }

    /**
     * Test: getTodayReport returns correct JSON shape when trades are entered but not closed.
     * <p>
     * This is the bug scenario: a trade entered today (EXPIRY_DAY_STRANGLE) is still OPEN.
     * The endpoint should NOT report "No trades executed today" — it should show the three counts.
     * <p>
     * Expected response:
     * <pre>
     * {
     *   "date": "<today's date>",
     *   "enteredToday": 1,
     *   "currentlyOpen": 1,
     *   "closedToday": 0,
     *   (no closedTradesSummary since no trades closed)
     * }
     * </pre>
     */
    @Test
    @DisplayName("getTodayReport_enteredTodayButNotClosed_showsThreeCountsNotNoTradesMessage")
    void getTodayReport_enteredTodayButNotClosed_showsThreeCountsNotNoTradesMessage() {
        // Arrange: Create a summary with 1 entered, 1 open, 0 closed
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DailyReportService.DailyReportMetrics noClosedMetrics = new DailyReportService.DailyReportMetrics();
        DailyReportService.DailyStatusSummary openTradesSummary = new DailyReportService.DailyStatusSummary(
                1, 1, 0, noClosedMetrics
        );

        // Stub DailyReportService to return the summary with open trades
        lenient().when(dailyReportService.getTodayStatusSummary(any(LocalDate.class)))
                .thenReturn(Mono.just(openTradesSummary));

        // Act & Assert: Call GET /trading/report/today and verify JSON response
        webTestClient.get()
                .uri("/trading/report/today")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/json")
                .expectBody()
                // Verify the three counts are present
                .jsonPath("$.date").isEqualTo(today.toString())
                .jsonPath("$.enteredToday").isEqualTo(1)
                .jsonPath("$.currentlyOpen").isEqualTo(1)
                .jsonPath("$.closedToday").isEqualTo(0)
                // Verify that "No trades executed today" message is NOT present
                .jsonPath("$.message").doesNotExist()
                // Verify that closedTradesSummary is NOT present (no closed trades)
                .jsonPath("$.closedTradesSummary").doesNotExist();
    }

    /**
     * Test: getTodayReport returns correct JSON shape when trades are closed.
     * <p>
     * Verifies that when trades are closed today, the response includes:
     * - The three counts (enteredToday, currentlyOpen, closedToday)
     * - closedTradesSummary with P&L metrics (totalTrades, wins, losses, winRate, P&L, best/worst)
     * <p>
     * Expected response structure:
     * <pre>
     * {
     *   "date": "<today's date>",
     *   "enteredToday": 2,
     *   "currentlyOpen": 1,
     *   "closedToday": 1,
     *   "closedTradesSummary": {
     *     "totalTrades": 1,
     *     "winningTrades": 1,
     *     "losingTrades": 0,
     *     "winRate": "100.0%",
     *     "totalPnl": "75.00 pts",
     *     "totalPnlAmount": "₹ 4875.00",
     *     "bestTrade": { ... },
     *     "worstTrade": { ... }
     *   }
     * }
     * </pre>
     */
    @Test
    @DisplayName("getTodayReport_withClosedTrades_includesClosedTradesSummary")
    void getTodayReport_withClosedTrades_includesClosedTradesSummary() {
        // Arrange: Create a summary with 2 entered, 1 open, 1 closed, with P&L metrics
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        DailyReportService.DailyReportMetrics metricsWithClosedTrade = new DailyReportService.DailyReportMetrics();
        metricsWithClosedTrade.totalTrades = 1;
        metricsWithClosedTrade.winningTrades = 1;
        metricsWithClosedTrade.losingTrades = 0;
        metricsWithClosedTrade.totalPnl = 75.0;
        metricsWithClosedTrade.totalPnlAmount = 4875.0;  // 75 * 65 (NIFTY50 lot size)
        metricsWithClosedTrade.winRate = 100.0;

        DailyReportService.DailyStatusSummary closedTradesSummary = new DailyReportService.DailyStatusSummary(
                2, 1, 1, metricsWithClosedTrade
        );

        // Stub DailyReportService to return the summary with closed trades
        lenient().when(dailyReportService.getTodayStatusSummary(any(LocalDate.class)))
                .thenReturn(Mono.just(closedTradesSummary));

        // Act & Assert: Call GET /trading/report/today and verify JSON response
        webTestClient.get()
                .uri("/trading/report/today")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/json")
                .expectBody()
                // Verify the three counts
                .jsonPath("$.date").isEqualTo(today.toString())
                .jsonPath("$.enteredToday").isEqualTo(2)
                .jsonPath("$.currentlyOpen").isEqualTo(1)
                .jsonPath("$.closedToday").isEqualTo(1)
                // Verify closedTradesSummary is present with correct metrics
                .jsonPath("$.closedTradesSummary.totalTrades").isEqualTo(1)
                .jsonPath("$.closedTradesSummary.winningTrades").isEqualTo(1)
                .jsonPath("$.closedTradesSummary.losingTrades").isEqualTo(0)
                .jsonPath("$.closedTradesSummary.winRate").isEqualTo("100.0%")
                .jsonPath("$.closedTradesSummary.totalPnl").isEqualTo("75.00 pts")
                .jsonPath("$.closedTradesSummary.totalPnlAmount").isEqualTo("₹ 4875.00")
                // Verify that "No trades executed today" message is NOT present
                .jsonPath("$.message").doesNotExist();
    }
}
