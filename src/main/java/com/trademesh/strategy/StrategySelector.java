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
 *   <li>Apply priority-based selection based on market condition</li>
 *   <li>Evaluate selected strategy and return result</li>
 *   <li>Log strategy selection decisions</li>
 * </ul>
 *
 * <p>Strategy Selection Logic:
 * <ul>
 *   <li>NO_TRADE condition → skip all strategies (return empty)</li>
 *   <li>HIGH_VOLATILITY → skip all strategies (return empty)</li>
 *   <li>EXPIRY_DAY → prioritize ExpiryDayStrategy</li>
 *   <li>SIDEWAYS → prioritize ThetaSpreadStrategy</li>
 *   <li>TRENDING_BULLISH → prioritize BullPutSpreadStrategy</li>
 *   <li>TRENDING_BEARISH → prioritize BullCallSpreadStrategy</li>
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
     * 3. Apply priority-based selection based on condition
     * 4. Return first applicable strategy or empty if none found
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

            // Apply priority-based selection
            TradingStrategy selectedStrategy = selectByPriority(applicableStrategies, condition);

            if (selectedStrategy != null) {
                log.info("Selected strategy: {} for condition: {}", selectedStrategy.getName(), condition);
                return Optional.of(selectedStrategy);
            }

            log.info("No strategy selected after priority filtering for condition: {}", condition);
            return Optional.empty();
        });
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

    /**
     * Selects a strategy from applicable ones based on priority for the given condition.
     *
     * <p>Priority logic:
     * <ul>
     *   <li>EXPIRY_DAY → ExpiryDayStrategy first</li>
     *   <li>SIDEWAYS → ThetaSpreadStrategy first</li>
     *   <li>TRENDING_BULLISH → BullPutSpreadStrategy first</li>
     *   <li>TRENDING_BEARISH → BullCallSpreadStrategy first</li>
     * </ul>
     *
     * @param applicableStrategies list of strategies applicable to the condition
     * @param condition            market condition
     * @return selected strategy or null if none match priority
     */
    private TradingStrategy selectByPriority(List<TradingStrategy> applicableStrategies,
                                             MarketCondition condition) {
        return switch (condition) {
            case EXPIRY_DAY -> applicableStrategies.stream()
                    .filter(s -> s.getName().equals("EXPIRY_DAY_STRANGLE"))
                    .findFirst()
                    .orElseGet(() -> applicableStrategies.get(0));

            case SIDEWAYS -> applicableStrategies.stream()
                    .filter(s -> s.getName().equals("THETA_SPREAD"))
                    .findFirst()
                    .orElseGet(() -> applicableStrategies.get(0));

            case TRENDING_BULLISH -> applicableStrategies.stream()
                    .filter(s -> s.getName().equals("BULL_PUT_SPREAD"))
                    .findFirst()
                    .orElseGet(() -> applicableStrategies.get(0));

            case TRENDING_BEARISH -> applicableStrategies.stream()
                    .filter(s -> s.getName().equals("BEAR_CALL_SPREAD"))
                    .findFirst()
                    .orElseGet(() -> applicableStrategies.get(0));

            default -> applicableStrategies.get(0);
        };
    }
}
