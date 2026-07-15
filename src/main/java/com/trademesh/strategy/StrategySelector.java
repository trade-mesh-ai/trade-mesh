package com.trademesh.strategy;

import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Service for selecting the most appropriate trading strategy based on market conditions.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Filter strategies by applicability to current market condition</li>
 *   <li>Perform direct lookup of the applicable strategy (one regime = one strategy)</li>
 *   <li>Evaluate selected strategy and return result</li>
 *   <li>Log strategy selection decisions</li>
 * </ul>
 *
 * <p>Strategy Selection Logic:
 * <ul>
 *   <li>NO_TRADE condition → skip all strategies (return empty)</li>
 *   <li>HIGH_VOLATILITY → skip all strategies (return empty)</li>
 *   <li>All other conditions → exactly one strategy is applicable by design</li>
 *   <li>Selection is a direct lookup, not priority-based arbitration</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategySelector {

    /**
     * All available trading strategy beans, auto-injected by Spring.
     * Spring will inject all beans that implement TradingStrategy interface.
     */
    private final List<TradingStrategy> strategies;

    /**
     * Selects the most appropriate strategy for the given market intelligence.
     *
     * <p>Selection logic:
     * 1. If market condition is NO_TRADE or HIGH_VOLATILITY → return empty
     * 2. Filter strategies by isApplicable(condition)
     * 3. Direct lookup of the applicable strategy (one regime = one strategy by design)
     * 4. Return the applicable strategy or empty if none found
     *
     * @param intel market intelligence data
     * @return Mono emitting Optional with selected strategy, or empty if none applicable
     */
    public Mono<Optional<TradingStrategy>> selectStrategy(MarketIntelligence intel) {
        return Mono.fromCallable(() -> {
            MarketCondition condition = intel.getMarketCondition();

            // Skip all strategies for NO_TRADE and HIGH_VOLATILITY conditions
            if (condition == MarketCondition.NO_TRADE) {
                log.info("Market condition is NO_TRADE, skipping all strategies");
                return Optional.empty();
            }

            if (condition == MarketCondition.HIGH_VOLATILITY) {
                log.info("Market condition is HIGH_VOLATILITY, skipping all strategies");
                return Optional.empty();
            }

            // Filter strategies by applicability
            List<TradingStrategy> applicableStrategies = strategies.stream()
                    .filter(strategy -> strategy.isApplicable(condition))
                    .toList();

            if (applicableStrategies.isEmpty()) {
                log.info("No applicable strategies found for condition: {}", condition);
                return Optional.empty();
            }

            // Direct lookup - structurally guaranteed to have exactly one element
            // (one regime = one strategy by design)
            if (applicableStrategies.size() == 1) {
                TradingStrategy selectedStrategy = applicableStrategies.getFirst();
                log.info("Selected strategy: {} for condition: {}", selectedStrategy.getName(), condition);
                return Optional.of(selectedStrategy);
            } else {
                // Config error: multiple strategies applicable to same condition
                log.error("Config error: {} strategies applicable to condition {} (expected exactly 1). "
                        + "This indicates overlapping applicable-conditions across strategies. "
                        + "Selecting first strategy as fallback.", applicableStrategies.size(), condition);
                TradingStrategy selectedStrategy = applicableStrategies.getFirst();
                log.info("Selected strategy: {} for condition: {}", selectedStrategy.getName(), condition);
                return Optional.of(selectedStrategy);
            }
        });
    }

    /**
     * Gets the name of the strategy that would be selected for the given market intelligence.
     *
     * <p>Used by callers that need to know which strategy will be evaluated before
     * calling evaluateSelectedStrategy(). Useful for pre-flight checks and logging.
     *
     * @param intel market intelligence data
     * @return Mono emitting Optional with strategy name, or empty if no strategy applicable
     */
    public Mono<Optional<String>> getSelectedStrategyName(MarketIntelligence intel) {
        return selectStrategy(intel)
                .map(optionalStrategy -> optionalStrategy.map(TradingStrategy::getName));
    }

    /**
     * Evaluates the selected strategy for the given market intelligence.
     *
     * <p>Process:
     * 1. Select strategy using selectStrategy()
     * 2. If strategy found → call strategy.evaluate(intel)
     * 3. If no strategy → return skip result with reason
     *
     * @param intel market intelligence data
     * @return Mono emitting StrategyResult with signal or skip reason
     */
    public Mono<StrategyResult> evaluateSelectedStrategy(MarketIntelligence intel) {
        return selectStrategy(intel)
                .flatMap(optionalStrategy -> {
                    if (optionalStrategy.isPresent()) {
                        TradingStrategy strategy = optionalStrategy.get();
                        log.debug("Evaluating strategy: {} for symbol: {}", strategy.getName(), intel.getSymbol());
                        return strategy.evaluate(intel);
                    } else {
                        // No applicable strategy found
                        String skipReason = "No applicable strategy for current market condition: " + intel.getMarketCondition();
                        log.info("Skipping evaluation: {}", skipReason);
                        return Mono.just(StrategyResult.builder()
                                .isExecutable(false)
                                .skipReason(skipReason)
                                .build());
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error evaluating strategy for symbol: {}", intel.getSymbol(), e);
                    return Mono.just(StrategyResult.builder()
                            .isExecutable(false)
                            .skipReason("Strategy evaluation error: " + e.getMessage())
                            .build());
                });
    }

}
