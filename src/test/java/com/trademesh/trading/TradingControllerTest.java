package com.trademesh.trading;

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
import static org.mockito.Mockito.when;

/**
 * Controller-level test for TradingController.getTodayReport().
 * <p>
 * Verifies the HTTP response shape when no trades are executed on a given day.
 * This test cannot be verified live because the endpoint has no date parameter
 * (hardcoded to LocalDate.now()) and no zero-trade day has occurred since the refactor.
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

    /**
     * Test: getTodayReport returns correct JSON shape for empty-day (zero trades).
     * <p>
     * Verifies that when DailyReportService.getMetricsForDate() returns a zero-valued
     * DailyReportMetrics (totalTrades=0, all other fields at defaults), the controller
     * renders the HTTP response with EXACTLY:
     * <pre>
     * {
     *   "date": "<today's date>",
     *   "totalTrades": 0,
     *   "message": "No trades executed today"
     * }
     * </pre>
     * <p>
     * Confirms that no other keys are present (no winningTrades, totalPnl, etc.),
     * matching the controller's early-return branch for zero-trade days.
     */
    @Test
    @DisplayName("getTodayReport_emptyDay_returnsCorrectJsonShape")
    void getTodayReport_emptyDay_returnsCorrectJsonShape() {
        // Arrange: Create a zero-valued DailyReportMetrics matching what computeMetrics(emptyList) produces
        DailyReportService.DailyReportMetrics zeroedMetrics = new DailyReportService.DailyReportMetrics();
        // DailyReportMetrics() constructor initializes all fields to zero/null:
        // totalTrades=0, winningTrades=0, losingTrades=0, totalPnl=0.0,
        // bestTrade=null, worstTrade=null, winRate=0.0, totalPnlAmount=0.0,
        // bestPnlAmount=0.0, worstPnlAmount=0.0

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        // Stub DailyReportService to return the zeroed metrics for any date
        lenient().when(dailyReportService.getMetricsForDate(any(LocalDate.class)))
                .thenReturn(Mono.just(zeroedMetrics));

        // Act & Assert: Call GET /trading/report/today and verify JSON response
        webTestClient.get()
                .uri("/trading/report/today")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/json")
                .expectBody()
                // Verify exact JSON structure for empty-day response
                .jsonPath("$.date").isEqualTo(today.toString())
                .jsonPath("$.totalTrades").isEqualTo(0)
                .jsonPath("$.message").isEqualTo("No trades executed today")
                // Verify that extra keys are NOT present (early-return branch omits them)
                .jsonPath("$.winningTrades").doesNotExist()
                .jsonPath("$.losingTrades").doesNotExist()
                .jsonPath("$.winRate").doesNotExist()
                .jsonPath("$.totalPnl").doesNotExist()
                .jsonPath("$.totalPnlAmount").doesNotExist()
                .jsonPath("$.bestTrade").doesNotExist()
                .jsonPath("$.worstTrade").doesNotExist();
    }
}
