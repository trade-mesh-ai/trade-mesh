package com.trademesh.trading;

import com.trademesh.execution.PaperTrade;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.TradeStatus;
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
public class TradingController {

    private final TradingOrchestrator tradingOrchestrator;
    private final DailyReportService dailyReportService;
    private final PaperTradeRepository paperTradeRepository;

    /**
     * Manually triggers a trading cycle for a specific symbol.
     * <p>
     * Useful for testing or manual intervention outside of scheduled times.
     * Runs the complete trading cycle: intelligence → strategy → execution.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "BANKNIFTY")
     * @return Mono emitting ResponseEntity with StrategyResult
     */
    @PostMapping(value = "/run/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> runTradingCycle(@PathVariable String symbol) {
        log.info("Manual request to run trading cycle for symbol: {}", symbol);

        return tradingOrchestrator.runTradingCycle(symbol)
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
     * Retrieves all currently open positions.
     * <p>
     * Returns a list of all paper trades with OPEN status, including:
     * - Trade ID, symbol, strategy name
     * - Entry credit and current value
     * - Unrealized P&L
     * - Entry time and current status
     *
     * @return Mono emitting ResponseEntity with list of open positions
     */
    @GetMapping(value = "/positions/open", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getOpenPositions() {
        log.info("Fetching all open positions");

        return paperTradeRepository.findByStatus(TradeStatus.OPEN)
                .collectList()
                .map(positions -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("count", positions.size());
                    response.put("positions", positions.stream().map(trade -> {
                        Map<String, Object> tradeInfo = new HashMap<>();
                        tradeInfo.put("id", trade.getId());
                        tradeInfo.put("symbol", trade.getSymbol());
                        tradeInfo.put("strategyName", trade.getStrategyName());
                        tradeInfo.put("entryCredit", trade.getEntryCredit());
                        tradeInfo.put("currentValue", trade.getCurrentValue());
                        tradeInfo.put("unrealizedPnl", trade.getUnrealizedPnl());
                        tradeInfo.put("entryTime", trade.getEntryTime());
                        tradeInfo.put("status", trade.getStatus());
                        return tradeInfo;
                    }).toList());

                    log.info("Found {} open positions", positions.size());
                    return ResponseEntity.ok(response);
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
     * - Total trades, wins, losses
     * - Total P&L
     * - Best and worst trades
     * - Win rate percentage
     *
     * @return Mono emitting ResponseEntity with daily summary
     */
    @GetMapping(value = "/report/today", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getTodayReport() {
        log.info("Fetching today's trading report");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        return paperTradeRepository.findByStatus(TradeStatus.CLOSED)
                .filter(trade -> trade.getTradeDate().equals(today))
                .collectList()
                .map(trades -> {
                    Map<String, Object> response = new HashMap<>();

                    if (trades.isEmpty()) {
                        response.put("date", today);
                        response.put("totalTrades", 0);
                        response.put("message", "No trades executed today");
                        log.info("No trades found for today");
                        return ResponseEntity.ok(response);
                    }

                    // Calculate metrics
                    int totalTrades = trades.size();
                    long winningTrades = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() > 0)
                            .count();
                    long losingTrades = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() < 0)
                            .count();

                    double totalPnl = trades.stream()
                            .mapToDouble(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : 0)
                            .sum();

                    PaperTrade bestTrade = trades.stream()
                            .max((t1, t2) -> Double.compare(
                                    t1.getRealizedPnl() != null ? t1.getRealizedPnl() : 0,
                                    t2.getRealizedPnl() != null ? t2.getRealizedPnl() : 0
                            ))
                            .orElse(null);

                    PaperTrade worstTrade = trades.stream()
                            .min((t1, t2) -> Double.compare(
                                    t1.getRealizedPnl() != null ? t1.getRealizedPnl() : 0,
                                    t2.getRealizedPnl() != null ? t2.getRealizedPnl() : 0
                            ))
                            .orElse(null);

                    double winRate = totalTrades > 0 ? (winningTrades * 100.0) / totalTrades : 0;

                    // Build response
                    response.put("date", today);
                    response.put("totalTrades", totalTrades);
                    response.put("winningTrades", winningTrades);
                    response.put("losingTrades", losingTrades);
                    response.put("winRate", String.format("%.1f%%", winRate));
                    response.put("totalPnl", String.format("%.2f pts", totalPnl));
                    response.put("totalPnlAmount", String.format("₹ %.2f", totalPnl * 50)); // Average lot size

                    if (bestTrade != null) {
                        Map<String, Object> bestInfo = new HashMap<>();
                        bestInfo.put("strategy", bestTrade.getStrategyName());
                        bestInfo.put("pnl", String.format("%.2f pts", bestTrade.getRealizedPnl()));
                        bestInfo.put("pnlAmount", String.format("₹ %.2f", bestTrade.getRealizedPnl() * 50));
                        response.put("bestTrade", bestInfo);
                    }

                    if (worstTrade != null) {
                        Map<String, Object> worstInfo = new HashMap<>();
                        worstInfo.put("strategy", worstTrade.getStrategyName());
                        worstInfo.put("pnl", String.format("%.2f pts", worstTrade.getRealizedPnl()));
                        worstInfo.put("pnlAmount", String.format("₹ %.2f", worstTrade.getRealizedPnl() * 50));
                        response.put("worstTrade", worstInfo);
                    }

                    log.info("Today's report: {} trades, {} wins, {} losses, P&L: {}", totalTrades, winningTrades, losingTrades, totalPnl);
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
