package com.trademesh.trading;

import com.trademesh.alert.Alert;
import com.trademesh.alert.AlertType;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.PaperTradingEngine;
import com.trademesh.execution.TradeStatus;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketIntelligenceService;
import com.trademesh.risk.RiskManager;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.StrategySelector;
import com.trademesh.strategy.TradeSignal;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.enums.Confidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Main orchestrator for end-to-end paper trading cycles.
 * <p>
 * Connects all layers of the trading system:
 * - Market intelligence generation
 * - Strategy selection and evaluation
 * - Trade execution
 * - Position monitoring
 * - Alert notifications
 * <p>
 * Responsibilities:
 * - Orchestrate complete trading cycles (entry and monitoring)
 * - Manage position lifecycle
 * - Send trading alerts
 * - Log execution events
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingOrchestrator {

    private final MarketIntelligenceService marketIntelligenceService;
    private final StrategySelector strategySelector;
    private final PaperTradingEngine paperTradingEngine;
    private final PaperTradeRepository paperTradeRepository;
    private final StrategiesConfig strategiesConfig;
    private final TelegramNotificationBot telegramNotificationBot;
    private final StrategyExecutionLogRepository executionLogRepository;
    private final RiskManager riskManager;

    /**
     * Getter for PaperTradingEngine to allow REST controllers to access live P&L calculation.
     * Used by TradingController to calculate live unrealized P&L without DB writes.
     *
     * @return the PaperTradingEngine instance
     */
    public PaperTradingEngine getPaperTradingEngine() {
        return paperTradingEngine;
    }

    /**
     * Runs a complete trading cycle for a given symbol.
     * <p>
     * Process:
     * Step 1: Generate market intelligence
     * Step 2: Check if safe to trade
     * Step 3: Select and execute strategy
     * Step 4: If signal is not SKIP, execute the trade
     * Step 5: Log execution
     * Step 6: Send summary alert
     *
     * @param symbol trading symbol (e.g., "NIFTY", "BANKNIFTY")
     * @return Mono emitting the strategy result
     */
    public Mono<StrategyResult> runTradingCycle(String symbol) {
        log.info("Starting trading cycle for symbol: {}", symbol);

        return marketIntelligenceService.generateFreshIntelligence(symbol)
                .flatMap(intelligence -> {
                    // Step 2: Check if safe to trade
                    if (!intelligence.getIsSafeToTrade()) {
                        log.warn("Market not safe to trade for {}: condition={}, VIX={}",
                                symbol, intelligence.getMarketCondition(), intelligence.getVixData().getCurrentVix());
                        return Mono.just(StrategyResult.builder()
                                .isExecutable(false)
                                .skipReason("Market not safe to trade: " + intelligence.getMarketCondition())
                                .build());
                    }

                    // Step 3: Select and execute strategy
                    return strategySelector.evaluateSelectedStrategy(intelligence)
                            .flatMap(result -> {
                                // Step 4: If signal is executable, execute the trade
                                if (result.getIsExecutable() && result.getSignal() != null) {
                                    return riskManager.isWithinConcurrentPositionLimit()
                                            .flatMap(withinLimit -> {
                                                if (!withinLimit) {
                                                    int maxAllowed = riskManager.getMaxConcurrentOpenPositions();
                                                    log.info("Max concurrent open positions ({}) reached, skipping entry", maxAllowed);
                                                    return Mono.just(StrategyResult.builder()
                                                            .isExecutable(false)
                                                            .skipReason("Max concurrent open positions (" + maxAllowed + ") reached")
                                                            .build());
                                                }

                                                TradeSignal signal = result.getSignal();
                                                return paperTradingEngine.executeTrade(result)
                                                        .flatMap(trade -> {
                                                            // Step 5: Log execution
                                                            return logExecution(symbol, signal, intelligence)
                                                                    .then(Mono.just(result));
                                                        })
                                                        .flatMap(result2 -> {
                                                            // Step 6: Send summary alert
                                                            return sendTradingCycleSummary(symbol, result2.getSignal(), intelligence)
                                                                    .then(Mono.just(result2));
                                                        });
                                            });
                                } else {
                                    // Signal not executable, just log and return
                                    log.debug("Strategy signal not executable for {}: {}", symbol, result.getSkipReason());
                                    return Mono.just(result);
                                }
                            });
                })
                .doOnError(e -> log.error("Error in trading cycle for {}: {}", symbol, e.getMessage(), e));
    }

    /**
     * Monitors open positions for a given symbol.
     * <p>
     * Process:
     * - Get all open paper positions for the symbol
     * - For each position:
     * - Get fresh market intelligence
     * - Check if position should be exited
     * - If exit signal: close the position
     * - Else: update current prices via mark-to-market
     *
     * @param symbol trading symbol (e.g., "NIFTY", "BANKNIFTY")
     * @return Mono emitting the count of monitored positions
     */
    public Mono<Integer> monitorOpenPositions(String symbol) {
        log.debug("Monitoring open positions for symbol: {}", symbol);

        return executionLogRepository.findOpenPositionsBySymbol(symbol)
                .flatMap(position -> {
                    // TODO: Architectural Decision - Market Intelligence Freshness for Position Monitoring
                    // CURRENT STATE: Uses cached generateIntelligence() because current exit logic
                    // (mark-to-market only) does not depend on fresh VIX, Market Regime, or Open Interest.
                    //
                    // FUTURE RE-EVALUATION REQUIRED IF:
                    // - VIX-based exit conditions are implemented
                    // - Market Regime-based exits are added
                    // - Open Interest-based exits are implemented
                    // - Any other Market Intelligence-derived exit conditions are introduced
                    //
                    // When any of these conditions are met, evaluate whether fresh intelligence should
                    // be fetched for each position monitoring cycle (similar to trade entry in runTradingCycle).
                    return marketIntelligenceService.generateIntelligence(symbol)
                            .flatMap(intelligence -> {
                                // Mark to market to update current value and P&L
                                return paperTradingEngine.markToMarket(position)
                                        .doOnSuccess(updatedPosition -> {
                                            log.debug("Updated position {} for {}: unrealizedPnl={}",
                                                    updatedPosition.getId(), symbol, updatedPosition.getUnrealizedPnl());
                                        });
                            });
                })
                .count()
                .map(Math::toIntExact)
                .doOnSuccess(count -> log.info("Monitored {} open positions for {}", count, symbol))
                .onErrorResume(e -> {
                    log.error("Error monitoring positions for {}: {}", symbol, e.getMessage(), e);
                    return Mono.just(0);
                });
    }

     /**
      * Exits all open positions for a given symbol.
      *
      * @param symbol trading symbol
      * @return Mono emitting the count of closed positions
      */
     public Mono<Integer> exitAllPositions(String symbol) {
         log.info("Exiting all open positions for symbol: {}", symbol);

         return executionLogRepository.findOpenPositionsBySymbol(symbol)
                 .flatMap(position -> {
                     return paperTradingEngine.closeTrade(position, "FORCED_EXIT_END_OF_DAY");
                 })
                 .count()
                 .map(Math::toIntExact)
                 .doOnSuccess(count -> {
                     log.info("Closed {} positions for {} at end of day", count, symbol);
                     if (count > 0) {
                         sendEndOfDayAlert(symbol, count).subscribe();
                     }
                 })
                 .onErrorResume(e -> {
                     log.error("Error exiting positions for {}: {}", symbol, e.getMessage(), e);
                     return Mono.just(0);
                 });
     }

     /**
      * Exits open positions for a specific strategy and symbol.
      * <p>
      * Closes only positions matching the given strategy name, leaving other strategies untouched.
      * Used for strategy-specific end-of-day exits (e.g., EXPIRY_DAY_STRANGLE at 3:15 PM).
      *
      * @param symbol trading symbol
      * @param strategyName strategy name to filter by
      * @return Mono emitting the count of closed positions
      */
     public Mono<Integer> exitPositionsForStrategy(String symbol, String strategyName) {
         log.info("Exiting open positions for strategy {} and symbol: {}", strategyName, symbol);

         return paperTradeRepository.findByStrategyNameAndStatus(strategyName, TradeStatus.OPEN)
                 .flatMap(position -> {
                     return paperTradingEngine.closeTrade(position, "FORCED_EXIT_END_OF_DAY");
                 })
                 .count()
                 .map(Math::toIntExact)
                 .doOnSuccess(count -> {
                     log.info("Closed {} {} positions for {} at end of day", count, strategyName, symbol);
                     if (count > 0) {
                         sendEndOfDayAlert(symbol, count).subscribe();
                     }
                 })
                 .onErrorResume(e -> {
                     log.error("Error exiting {} positions for {}: {}", strategyName, symbol, e.getMessage(), e);
                     return Mono.just(0);
                 });
     }

    /**
     * Logs strategy execution to the database.
     *
     * @param symbol       trading symbol
     * @param signal       the trade signal
     * @param intelligence market intelligence
     * @return Mono emitting the saved log entry
     */
    private Mono<StrategyExecutionLog> logExecution(String symbol, TradeSignal signal, MarketIntelligence intelligence) {
        StrategyExecutionLog logger = StrategyExecutionLog.builder()
                .id(java.util.UUID.randomUUID())
                .symbol(symbol)
                .strategyName(signal.getStrategyName())
                .marketCondition(intelligence.getMarketCondition().name())
                .action(signal.getSignalType() != null ? signal.getSignalType().name() : com.trademesh.strategy.enums.SignalType.ENTER.name())
                .reason(signal.getReason())
                .vixLevel(intelligence.getVixData().getCurrentVix())
                .executedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        return executionLogRepository.save(logger)
                .doOnSuccess(saved -> log.info("Logged execution: {} | {} | {}", symbol, signal.getStrategyName(), signal.getReason()));
    }

    /**
     * Sends a trading cycle summary alert via Telegram.
     *
     * @param symbol       trading symbol
     * @param signal       the trade signal
     * @param intelligence market intelligence
     * @return Mono emitting void
     */
    private Mono<Void> sendTradingCycleSummary(String symbol, TradeSignal signal, MarketIntelligence intelligence) {
        if (signal == null) {
            return Mono.empty();
        }

        String message = String.format(
                "🤖 <b>Trading Cycle Complete</b>\n\n" +
                        "Symbol: <b>%s</b>\n" +
                        "Condition: <b>%s</b>\n" +
                        "Strategy: <b>%s</b>\n" +
                        "Action: <b>%s</b>\n" +
                        "Signal: <code>%s</code>\n" +
                        "VIX: <b>%.1f</b>\n" +
                        "Confidence: <b>%s</b>",
                symbol,
                intelligence.getMarketCondition().name(),
                signal.getStrategyName(),
                signal.getSignalType() != null ? signal.getSignalType().name() : com.trademesh.strategy.enums.SignalType.ENTER.name(),
                signal.getReason(),
                intelligence.getVixData().getCurrentVix(),
                signal.getConfidence() != null ? signal.getConfidence().name() : Confidence.MEDIUM.name()
        );

        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol(symbol)
                .message(message)
                .triggeredAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        return telegramNotificationBot.sendAlert(alert);
    }


    /**
     * Runs DTE exit safety net check for DTE-governed strategies.
     * <p>
     * Closes positions where isDteExitDue() returns true, using exit reason "DTE_EXIT_SAFETY_NET".
     * Only processes IRON_CONDOR, BULL_PUT_SPREAD, and BEAR_CALL_SPREAD positions.
     * EXPIRY_DAY_STRANGLE positions are explicitly excluded (not DTE-governed).
     *
     * @return Mono emitting the count of closed positions
     */
    public Mono<Integer> runDteExitSafetyNetCheck() {
        log.info("Running DTE exit safety net check for DTE-governed strategies");

        String[] dteGovernedStrategies = {"IRON_CONDOR", "BULL_PUT_SPREAD", "BEAR_CALL_SPREAD"};

        return Flux.fromArray(dteGovernedStrategies)
                .flatMap(strategyName -> {
                    com.trademesh.strategy.config.StrategyProperties cfg = strategiesConfig.getStrategy(strategyName);

                    // Determine default threshold based on strategy
                    int defaultThreshold = "IRON_CONDOR".equals(strategyName) ? 2 : 1;

                    return paperTradeRepository.findByStrategyNameAndStatus(strategyName, TradeStatus.OPEN)
                            .flatMap(trade -> {
                                if (paperTradingEngine.isDteExitDue(trade, cfg, defaultThreshold)) {
                                    log.info("DTE exit due for {} position {}, closing with reason DTE_EXIT_SAFETY_NET",
                                            strategyName, trade.getId());
                                    return paperTradingEngine.closeTrade(trade, "DTE_EXIT_SAFETY_NET");
                                }
                                return Mono.empty();
                            });
                })
                .count()
                .map(Math::toIntExact)
                .doOnSuccess(count -> log.info("DTE safety net closed {} positions", count))
                .onErrorResume(e -> {
                    log.error("Error in DTE exit safety net check: {}", e.getMessage(), e);
                    return Mono.just(0);
                });
    }

    /**
     * Sends an end-of-day alert when positions are forcefully exited.
     *
     * @param symbol        trading symbol
     * @param positionCount number of positions closed
     * @return Mono emitting void
     */
    private Mono<Void> sendEndOfDayAlert(String symbol, Integer positionCount) {
        String message = String.format(
                "🔚 <b>End of Day - Positions Closed</b>\n\n" +
                        "Symbol: <b>%s</b>\n" +
                        "Positions Closed: <b>%d</b>\n" +
                        "Reason: <b>Market Close</b>",
                symbol,
                positionCount
        );

        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol(symbol)
                .message(message)
                .triggeredAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();

        return telegramNotificationBot.sendAlert(alert);
    }
}
