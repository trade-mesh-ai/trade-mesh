package com.trademesh.execution;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for paper trading endpoints.
 * <p>
 * Provides endpoints for:
 * - Viewing all open paper trades
 * - Viewing closed trades history
 * - Calculating weekly P&L summary
 * - Manually closing a paper trade
 */
@Slf4j
@RestController
@RequestMapping("/paper/trades")
@RequiredArgsConstructor
@Tag(name = "Paper Trading", description = "Paper trading endpoints for viewing trades and P&L")
public class PaperTradeController {

    private final PaperTradeRepository paperTradeRepository;
    private final PaperTradingEngine paperTradingEngine;

    /**
     * GET /paper/trades/open
     * <p>
     * Returns all currently open paper trades.
     *
     * @return Flux of open PaperTrade objects
     */
    @Operation(summary = "Get all currently open paper trades")
    @GetMapping(value = "/open", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<PaperTrade> getOpenTrades() {
        log.info("Fetching all open paper trades");
        return paperTradeRepository.findAllOpenTrades()
                .doOnError(error -> log.error("Error fetching open trades", error));
    }

    /**
     * GET /paper/trades/history
     * <p>
     * Returns all closed paper trades (history).
     *
     * @return Flux of closed PaperTrade objects
     */
    @Operation(summary = "Get all closed paper trades (history)")
    @GetMapping(value = "/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<PaperTrade> getTradeHistory() {
        log.info("Fetching paper trade history");
        return paperTradeRepository.findAllClosedTrades()
                .doOnError(error -> log.error("Error fetching trade history", error));
    }

    /**
     * GET /paper/trades/pnl/weekly
     * <p>
     * Returns weekly P&L summary aggregated by date.
     * <p>
     * Response format:
     * <pre>
     * {
     *   "weekStartDate": "2026-06-15",
     *   "weekEndDate": "2026-06-21",
     *   "totalTrades": 10,
     *   "winningTrades": 7,
     *   "losingTrades": 3,
     *   "totalPnL": 1250.50,
     *   "winRate": 70.0,
     *   "dailyBreakdown": [
     *     {
     *       "date": "2026-06-15",
     *       "trades": 2,
     *       "pnl": 150.25
     *     },
     *     ...
     *   ]
     * }
     * </pre>
     *
     * @return Mono emitting ResponseEntity with weekly P&L summary
     */
    @Operation(summary = "Get weekly P&L summary with daily breakdown")
    @GetMapping(value = "/pnl/weekly", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getWeeklyPnL() {
        log.info("Fetching weekly P&L summary");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1);
        LocalDate weekEnd = weekStart.plusDays(6);

        return paperTradeRepository.findAllClosedTrades()
                .filter(trade -> !trade.getTradeDate().isBefore(weekStart) && !trade.getTradeDate().isAfter(weekEnd))
                .collectList()
                .map(trades -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("weekStartDate", weekStart);
                    response.put("weekEndDate", weekEnd);
                    response.put("totalTrades", trades.size());

                    long winningTrades = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() > 0)
                            .count();
                    long losingTrades = trades.size() - winningTrades;

                    double totalPnL = trades.stream()
                            .mapToDouble(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : 0)
                            .sum();

                    double winRate = trades.isEmpty() ? 0 : (winningTrades * 100.0) / trades.size();

                    response.put("winningTrades", winningTrades);
                    response.put("losingTrades", losingTrades);
                    response.put("totalPnL", totalPnL);
                    response.put("winRate", winRate);

                    // Daily breakdown
                    Map<LocalDate, Double> dailyPnL = new HashMap<>();
                    for (PaperTrade trade : trades) {
                        dailyPnL.merge(trade.getTradeDate(),
                                trade.getRealizedPnl() != null ? trade.getRealizedPnl() : 0,
                                Double::sum);
                    }

                    response.put("dailyBreakdown", dailyPnL);

                    log.info("Weekly P&L summary: {} trades, {} winning, {} losing, Total P&L: {}",
                            trades.size(), winningTrades, losingTrades, totalPnL);

                    return ResponseEntity.ok(response);
                })
                .onErrorResume(error -> {
                    log.error("Error fetching weekly P&L", error);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error fetching weekly P&L: " + error.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }

    /**
     * POST /paper/trades/{id}/close
     * <p>
     * Manually closes a paper trade with an optional reason.
     * <p>
     * Request body (optional):
     * <pre>
     * {
     *   "reason": "MANUAL_EXIT"
     * }
     * </pre>
     *
     * @param id     the UUID of the paper trade to close
     * @param reason optional reason for closing (defaults to "MANUAL_EXIT")
     * @return Mono emitting ResponseEntity with the closed PaperTrade
     */
    @Operation(summary = "Manually close a paper trade")
    @PostMapping(value = "/{id}/close", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaperTrade>> closeTrade(
            @Parameter(description = "UUID of the paper trade to close")
            @PathVariable UUID id,
            @Parameter(description = "Reason for closing the trade (defaults to MANUAL_EXIT)")
            @RequestParam(required = false, defaultValue = "MANUAL_EXIT") String reason) {
        log.info("Received request to close paper trade: {} with reason: {}", id, reason);

        return paperTradeRepository.findById(id)
                .flatMap(trade -> {
                    if (trade.getStatus() != TradeStatus.OPEN) {
                        log.warn("Trade {} is not open, cannot close", id);
                        return Mono.just(ResponseEntity.badRequest().<PaperTrade>build());
                    }
                    return paperTradingEngine.closeTrade(trade, reason)
                            .map(ResponseEntity::ok);
                })
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(error -> {
                    log.error("Error closing paper trade: {}", id, error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });

    }

    /**
     * GET /paper/trades/{id}
     * <p>
     * Retrieves a specific paper trade by ID.
     *
     * @param id the UUID of the paper trade
     * @return Mono emitting ResponseEntity with the PaperTrade
     */
    @Operation(summary = "Get a specific paper trade by ID")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<PaperTrade>> getTrade(
            @Parameter(description = "UUID of the paper trade")
            @PathVariable UUID id) {
        log.info("Fetching paper trade: {}", id);
        return paperTradeRepository.findById(id)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(error -> {
                    log.error("Error fetching paper trade: {}", id, error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * GET /paper/trades/symbol/{symbol}
     * <p>
     * Retrieves all paper trades for a specific symbol.
     *
     * @param symbol the trading symbol (e.g., "NIFTY50", "BANKNIFTY")
     * @return Flux of PaperTrade objects for the symbol
     */
    @Operation(summary = "Get all paper trades for a specific symbol")
    @GetMapping(value = "/symbol/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<PaperTrade> getTradesBySymbol(
            @Parameter(description = "Trading symbol (e.g., NIFTY50, BANKNIFTY)")
            @PathVariable String symbol) {
        log.info("Fetching paper trades for symbol: {}", symbol);
        return paperTradeRepository.findBySymbol(symbol)
                .doOnError(error -> log.error("Error fetching trades for symbol: {}", symbol, error));
    }

    /**
     * GET /paper/trades/strategy/{strategyName}
     * <p>
     * Retrieves all paper trades for a specific strategy.
     *
     * @param strategyName the strategy name
     * @return Flux of PaperTrade objects for the strategy
     */
    @Operation(summary = "Get all paper trades for a specific strategy")
    @GetMapping(value = "/strategy/{strategyName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<PaperTrade> getTradesByStrategy(
            @Parameter(description = "Strategy Name(e.g., EXPIRY_DAY_STRANGLE, IRON_CONDOR, BULL_PUT_SPREAD, BULL_CALL_SPREAD)")
            @PathVariable String strategyName) {
        log.info("Fetching paper trades for strategy: {}", strategyName);
        return paperTradeRepository.findByStrategyName(strategyName)
                .doOnError(error -> log.error("Error fetching trades for strategy: {}", strategyName, error));
    }

    /**
     * GET /paper/trades/stats
     * <p>
     * Returns overall paper trading statistics.
     * <p>
     * Response format:
     * <pre>
     * {
     *   "totalTrades": 100,
     *   "openTrades": 5,
     *   "closedTrades": 95,
     *   "totalPnL": 5250.75,
     *   "winRate": 65.26,
     *   "avgWin": 125.50,
     *   "avgLoss": -85.25,
     *   "profitFactor": 1.85
     * }
     * </pre>
     *
     * @return Mono emitting ResponseEntity with trading statistics
     */
    @Operation(summary = "Get overall paper trading statistics")
    @GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getStats() {
        log.info("Fetching paper trading statistics");

        return paperTradeRepository.findAllClosedTrades()
                .collectList()
                .map(trades -> {
                    Map<String, Object> stats = new HashMap<>();

                    long totalTrades = trades.size();
                    long winningTrades = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() > 0)
                            .count();

                    double totalPnL = trades.stream()
                            .mapToDouble(t -> t.getRealizedPnl() != null ? t.getRealizedPnl() : 0)
                            .sum();

                    double avgWin = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() > 0)
                            .mapToDouble(PaperTrade::getRealizedPnl)
                            .average()
                            .orElse(0);

                    double avgLoss = trades.stream()
                            .filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl() < 0)
                            .mapToDouble(PaperTrade::getRealizedPnl)
                            .average()
                            .orElse(0);

                    double profitFactor = avgLoss != 0 ? Math.abs(avgWin / avgLoss) : 0;
                    double winRate = totalTrades > 0 ? (winningTrades * 100.0) / totalTrades : 0;

                    stats.put("totalTrades", totalTrades);
                    stats.put("closedTrades", totalTrades);
                    stats.put("totalPnL", totalPnL);
                    stats.put("winRate", winRate);
                    stats.put("avgWin", avgWin);
                    stats.put("avgLoss", avgLoss);
                    stats.put("profitFactor", profitFactor);

                    log.info("Trading stats: {} total trades, {} win rate, {} total P&L",
                            totalTrades, winRate, totalPnL);

                    return ResponseEntity.ok(stats);
                })
                .onErrorResume(error -> {
                    log.error("Error fetching trading statistics", error);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error fetching statistics: " + error.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }
}
