package com.trademesh.strategy;

import com.trademesh.intelligence.MarketIntelligenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Strategy execution engine for evaluating and executing trading strategies.
 * <p>
 * Responsibilities:
 * - Load active strategies from database
 * - Evaluate strategies against market intelligence
 * - Emit execution decisions to ExecutionService
 * - Handle strategy selection based on MarketCondition
 * - Implement retry logic for failed evaluations
 * - Add timeout handling for strategy evaluation
 * <p>
 * Integrates with StrategyOrchestrator for complete pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEngine {

    private final StrategyOrchestrator strategyOrchestrator;
    private final MarketIntelligenceService marketIntelligenceService;

    /**
     * Evaluates all active strategies for a given symbol.
     * <p>
     * Process:
     * 1. Fetch market intelligence for the symbol
     * 2. Orchestrate strategy evaluation pipeline
     * 3. Return strategy result with signal or skip reason
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return Mono emitting StrategyResult with signal or skip reason
     */
    public Mono<StrategyResult> evaluate(String symbol) {
        log.info("Evaluating strategies for symbol: {}", symbol);

        return strategyOrchestrator.orchestrate(symbol)
                .timeout(Duration.ofSeconds(10))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .doBeforeRetry(signal -> log.warn("Retrying strategy evaluation for {}, attempt {}",
                                symbol, signal.totalRetries() + 1)))
                .doOnNext(result -> {
                    if (result.getIsExecutable()) {
                        log.info("Strategy evaluation result for {}: EXECUTABLE | Signal: {}",
                                symbol, result.getSignal() != null ? result.getSignal().getReason() : "N/A");
                    } else {
                        log.info("Strategy evaluation result for {}: SKIPPED | Reason: {}",
                                symbol, result.getSkipReason());
                    }
                })
                .doOnError(e -> log.error("Error evaluating strategies for {}: {}", symbol, e.getMessage(), e))
                .onErrorResume(e -> {
                    log.error("Strategy evaluation failed for {}, returning skip result", symbol, e);
                    return Mono.just(StrategyResult.builder()
                            .isExecutable(false)
                            .skipReason("Strategy evaluation error: " + e.getMessage())
                            .build());
                });
    }

    /**
     * Evaluates strategies for multiple symbols in parallel.
     *
     * @param symbols list of trading symbols
     * @return Flux emitting StrategyResult for each symbol
     */
    public Flux<StrategyResult> evaluateMultiple(Flux<String> symbols) {
        log.info("Evaluating strategies for multiple symbols");

        return symbols
                .flatMap(this::evaluate)
                .doOnError(e -> log.error("Error evaluating multiple symbols: {}", e.getMessage(), e));
    }

    /**
     * Checks if the market is currently open for trading.
     *
     * @return true if market is open, false otherwise
     */
    public boolean isMarketOpen() {
        return strategyOrchestrator.isMarketOpen();
    }
}
