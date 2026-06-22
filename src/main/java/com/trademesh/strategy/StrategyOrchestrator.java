package com.trademesh.strategy;

import com.trademesh.config.ApplicationConstants;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketIntelligenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Orchestrates the complete strategy evaluation pipeline.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Fetch market intelligence for a symbol</li>
 *   <li>Select appropriate strategy based on market conditions</li>
 *   <li>Evaluate selected strategy</li>
 *   <li>Log results and return strategy result</li>
 *   <li>Check market hours and trading days</li>
 * </ul>
 *
 * <p>Pipeline Flow:
 * 1. Get MarketIntelligence for symbol
 * 2. Select strategy via StrategySelector
 * 3. Evaluate strategy
 * 4. Log result
 * 5. Return StrategyResult
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyOrchestrator {

    private final MarketIntelligenceService marketIntelligenceService;
    private final StrategySelector strategySelector;

    /**
     * Orchestrates the complete strategy evaluation pipeline for a given symbol.
     *
     * <p>Process:
     * 1. Fetch market intelligence for the symbol
     * 2. Select appropriate strategy based on market condition
     * 3. Evaluate the selected strategy
     * 4. Log the result
     * 5. Return the strategy result
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return Mono emitting StrategyResult with signal or skip reason
     */
    public Mono<StrategyResult> orchestrate(String symbol) {
        log.info("Starting strategy orchestration for symbol: {}", symbol);

        return marketIntelligenceService.generateIntelligence(symbol)
                .flatMap(intel -> {
                    log.debug("Generated market intelligence for {}: condition={}, safe={}",
                            symbol, intel.getMarketCondition(), intel.getIsSafeToTrade());

                    // Evaluate strategy
                    return strategySelector.evaluateSelectedStrategy(intel)
                            .doOnNext(result -> {
                                // Log result
                                if (result.getIsExecutable()) {
                                    log.info("Strategy evaluation result for {}: EXECUTABLE | Strategy: {} | Signal: {}",
                                            symbol,
                                            result.getSignal() != null ? result.getSignal().getStrategyName() : "N/A",
                                            result.getSignal() != null ? result.getSignal().getReason() : "N/A");
                                } else {
                                    log.info("Strategy evaluation result for {}: SKIPPED | Reason: {}",
                                            symbol, result.getSkipReason());
                                }
                            });
                })
                .onErrorResume(e -> {
                    log.error("Error during strategy orchestration for symbol: {}", symbol, e);
                    return Mono.just(StrategyResult.builder()
                            .isExecutable(false)
                            .skipReason("Orchestration error: " + e.getMessage())
                            .build());
                });
    }

    /**
     * Checks if the market is currently open for trading.
     *
     * <p>Market is open if:
     * - Current time is between 9:15 AM and 3:30 PM IST
     * - Today is Monday through Friday (not weekend)
     *
     * @return true if market is open, false otherwise
     */
    public boolean isMarketOpen() {
        LocalTime now = LocalTime.now(ApplicationConstants.IST);
        LocalDate today = LocalDate.now(ApplicationConstants.IST);
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        // Check if today is a trading day (Monday to Friday)
        boolean isWeekday = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;

        // Check if current time is within market hours
        boolean isWithinMarketHours = !now.isBefore(ApplicationConstants.MARKET_OPEN)
                && !now.isAfter(ApplicationConstants.MARKET_CLOSE);

        boolean marketOpen = isWeekday && isWithinMarketHours;

        log.debug("Market open check: weekday={}, withinHours={}, result={} | Time: {} | Day: {}",
                isWeekday, isWithinMarketHours, marketOpen, now, dayOfWeek);

        return marketOpen;
    }
}
