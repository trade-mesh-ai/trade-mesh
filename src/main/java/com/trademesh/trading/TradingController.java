package com.trademesh.trading;

import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.TradeStatus;
import com.trademesh.strategy.StrategyResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for manual trading operations and status endpoints.
 * <p>
 * Provides endpoints for:
 * - Manually triggering trading cycles
 * - Monitoring open positions
 * - Viewing daily trading summary
 * - Checking open positions
 * <p>
 * Useful for testing and manual intervention outside of scheduled times.
 */
@Slf4j
@RestController
@RequestMapping("/trading")
@RequiredArgsConstructor
@Tag(name = "Trading", description = "Manual trading operations and status endpoints")
public class TradingController {

    private final TradingOrchestrator tradingOrchestrator;
    private final DailyReportService dailyReportService;
    private final PaperTradeRepository paperTradeRepository;
    private final TradingScheduler tradingScheduler;

    @Operation(summary = "Manually trigger expiry day entry")
    @PostMapping("/expiry-day/trigger")
    public Mono<ResponseEntity<String>> triggerExpiryDayEntry() {
        tradingScheduler.triggerDailyEntryManually();
        return Mono.just(ResponseEntity.ok("Expiry day entry triggered manually"));
    }

    /**
     * Manually triggers a trading cycle for a specific symbol.
     * <p>
     * Useful for testing or manual intervention outside of scheduled times.
     * Runs the complete trading cycle: intelligence → strategy → execution.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "BANKNIFTY")
     * @return Mono emitting ResponseEntity with StrategyResult
     */
    @Operation(summary = "Manually trigger a trading cycle for a symbol")
    @PostMapping(value = "/run/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> runTradingCycle(
            @Parameter(description = "Trading symbol (e.g., NIFTY50, NIFTYBANK)")
            @PathVariable String symbol) {
        log.info("Manual request to run trading cycle for symbol: {}", symbol);

        return tradingOrchestrator.runTradingCycle(symbol)
                .switchIfEmpty(Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason("Trade execution was skipped — check logs for details")
                        .build()))
                .map(result -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("symbol", symbol);
                    response.put("executable", result.getIsExecutable());
                    response.put("skipReason", result.getSkipReason());

                    if (result.getSignal() != null) {
                        Map<String, Object> signalInfo = new HashMap<>();
                        signalInfo.put("strategyName", result.getSignal().getStrategyName());
                        signalInfo.put("signalType", result.getSignal().getSignalType());
                        signalInfo.put("reason", result.getSignal().getReason());
                        signalInfo.put("confidence", result.getSignal().getConfidence());
                        signalInfo.put("expectedCredit", result.getSignal().getExpectedCredit());
                        signalInfo.put("maxLoss", result.getSignal().getMaxLoss());
                        response.put("signal", signalInfo);
                    }

                    log.info("Trading cycle completed for {}: executable={}", symbol, result.getIsExecutable());
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.error("Error running trading cycle for {}: {}", symbol, e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error running trading cycle: " + e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }

    /**
     * Retrieves all currently open positions with live P&L calculation.
     * <p>
     * Returns a list of all paper trades with OPEN status, including:
     * - Trade ID, symbol, strategy name
     * - Entry credit and current value (stored DB values, scheduler-managed)
     * - Unrealized P&L (stored DB value, scheduler-managed)
     * - Live unrealized P&L (calculated at read time from Redis)
     * - isPnlLive flag (true if calculated from Redis, false if using stored value)
     * - Entry time and current status
     * <p>
     * This endpoint is READ-ONLY and does NOT write to the database.
     * Live P&L is calculated from current Redis option chain data without persistence.
     * If Redis has no data, gracefully falls back to stored DB value with isPnlLive=false.
     *
     * @return Mono emitting ResponseEntity with list of open positions with live P&L
     */
    @Operation(summary = "Get all currently open positions with live P&L calculation")
    @GetMapping(value = "/positions/open", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getOpenPositions() {
        log.info("Fetching all open positions with live P&L calculation");

        return paperTradeRepository.findByStatus(TradeStatus.OPEN)
                .collectList()
                .flatMap(positions -> {
                    if (positions.isEmpty()) {
                        Map<String, Object> response = new HashMap<>();
                        response.put("count", 0);
                        response.put("positions", List.of());
                        log.info("No open positions found");
                        return Mono.just(ResponseEntity.ok(response));
                    }

                    // Calculate live P&L for each position (read-only, no DB writes)
                    return reactor.core.publisher.Flux.fromIterable(positions)
                            .flatMap(position -> {
                                // Get live P&L data from Redis
                                return tradingOrchestrator.getPaperTradingEngine()
                                        .calculateLiveUnrealizedPnl(position)
                                        .map(livePnlData -> new Object[]{position, livePnlData});
                            })
                            .collectList()
                            .map(positionPnlPairs -> {
                                Map<String, Object> response = new HashMap<>();
                                response.put("count", positionPnlPairs.size());
                                response.put("positions", positionPnlPairs.stream().map(pair -> {
                                    PaperTrade trade = (PaperTrade) pair[0];
                                    com.trademesh.execution.LivePnlData livePnlData =
                                            (com.trademesh.execution.LivePnlData) pair[1];

                                    Map<String, Object> tradeInfo = new HashMap<>();
                                    tradeInfo.put("id", trade.getId());
                                    tradeInfo.put("symbol", trade.getSymbol());
                                    tradeInfo.put("strategyName", trade.getStrategyName());
                                    tradeInfo.put("entryCredit", trade.getEntryCredit());
                                    tradeInfo.put("currentValue", trade.getCurrentValue());
                                    tradeInfo.put("unrealizedPnl", Math.round(trade.getUnrealizedPnl() * 100.0) / 100.0);
                                    tradeInfo.put("liveUnrealizedPnl",
                                            Math.round(livePnlData.getLiveUnrealizedPnl() * 100.0) / 100.0);
                                    tradeInfo.put("isPnlLive", livePnlData.getIsPnlLive());
                                    tradeInfo.put("entryTime", trade.getEntryTime());
                                    tradeInfo.put("status", trade.getStatus());
                                    return tradeInfo;
                                }).toList());

                                log.info("Found {} open positions with live P&L calculation", positionPnlPairs.size());
                                return ResponseEntity.ok(response);
                            });
                })
                .onErrorResume(e -> {
                    log.error("Error fetching open positions: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error fetching open positions: " + e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }

    /**
     * Manually triggers position monitoring for all open positions.
     * <p>
     * Updates current values and checks for stop loss / target triggers.
     * Useful for testing the monitoring logic outside of scheduled times.
     *
     * @return Mono emitting ResponseEntity with monitoring results
     */
    @Operation(summary = "Manually trigger position monitoring for all open positions")
    @PostMapping(value = "/monitor", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> monitorPositions() {
        log.info("Manual request to monitor all open positions");

        return Mono.zip(
                        tradingOrchestrator.monitorOpenPositions("NIFTY"),
                        tradingOrchestrator.monitorOpenPositions("BANKNIFTY")
                )
                .map(tuple -> {
                    int niftyCount = tuple.getT1();
                    int bankniftyCount = tuple.getT2();

                    Map<String, Object> response = new HashMap<>();
                    response.put("niftyPositionsMonitored", niftyCount);
                    response.put("bankniftyPositionsMonitored", bankniftyCount);
                    response.put("totalPositionsMonitored", niftyCount + bankniftyCount);

                    log.info("Monitored {} NIFTY and {} BANKNIFTY positions", niftyCount, bankniftyCount);
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.error("Error monitoring positions: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error monitoring positions: " + e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }

    /**
     * Retrieves today's trading summary.
     * <p>
     * Returns aggregated metrics for all trades executed today:
     * - Entered today, currently open, closed today (three independent counts)
     * - For closed trades: wins, losses, total P&L, best/worst trades, win rate
     * <p>
     * If all three counts are zero (no activity), returns a simple "no trades" message.
     * Otherwise, returns the full breakdown including the three counts.
     *
     * @return Mono emitting ResponseEntity with daily summary
     */
    @Operation(summary = "Get today's trading summary and report")
    @GetMapping(value = "/report/today", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getTodayReport() {
        log.info("Fetching today's trading report");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        return dailyReportService.getTodayStatusSummary(today)
                .map(summary -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("date", today);

                    // If all three counts are zero, show the simple "no trades" message
                    if (summary.enteredToday() == 0 && summary.openCount() == 0 && summary.closedTodayCount() == 0) {
                        response.put("enteredToday", 0);
                        response.put("currentlyOpen", 0);
                        response.put("closedToday", 0);
                        response.put("message", "No trades executed today");
                        log.info("No trades found for today");
                        return ResponseEntity.ok(response);
                    }

                    // Otherwise, show the full breakdown with all three counts
                    response.put("enteredToday", summary.enteredToday());
                    response.put("currentlyOpen", summary.openCount());
                    response.put("closedToday", summary.closedTodayCount());

                    DailyReportService.DailyReportMetrics metrics = summary.metrics();

                    // Include closed-trade metrics only if there are closed trades
                    if (metrics.totalTrades > 0) {
                        response.put("closedTradesSummary", new HashMap<String, Object>() {{
                            put("totalTrades", metrics.totalTrades);
                            put("winningTrades", metrics.winningTrades);
                            put("losingTrades", metrics.losingTrades);
                            put("winRate", String.format("%.1f%%", metrics.winRate));
                            put("totalPnl", String.format("%.2f pts", metrics.totalPnl));
                            put("totalPnlAmount", String.format("₹ %.2f", metrics.totalPnlAmount));

                            if (metrics.bestTrade != null) {
                                Map<String, Object> bestInfo = new HashMap<>();
                                bestInfo.put("strategy", metrics.bestTrade.getStrategyName());
                                bestInfo.put("pnl", String.format("%.2f pts", metrics.bestTrade.getRealizedPnl()));
                                bestInfo.put("pnlAmount", String.format("₹ %.2f", metrics.bestPnlAmount));
                                put("bestTrade", bestInfo);
                            }

                            if (metrics.worstTrade != null) {
                                Map<String, Object> worstInfo = new HashMap<>();
                                worstInfo.put("strategy", metrics.worstTrade.getStrategyName());
                                worstInfo.put("pnl", String.format("%.2f pts", metrics.worstTrade.getRealizedPnl()));
                                worstInfo.put("pnlAmount", String.format("₹ %.2f", metrics.worstPnlAmount));
                                put("worstTrade", worstInfo);
                            }
                        }});
                    }

                    log.info("Today's report: entered={}, open={}, closed={}, closedMetrics.totalTrades={}",
                            summary.enteredToday(), summary.openCount(), summary.closedTodayCount(), metrics.totalTrades);
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.error("Error fetching today's report: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error fetching today's report: " + e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }
}
