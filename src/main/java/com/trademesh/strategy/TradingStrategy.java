package com.trademesh.strategy;

import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import reactor.core.publisher.Mono;

/**
 * Core interface for all trading strategies.
 * <p>
 * Defines the contract that all strategy implementations must follow.
 * Strategies are responsible for:
 * - Identifying themselves with a unique name
 * - Determining applicability to market conditions
 * - Evaluating market intelligence to generate trade signals
 * - Providing their configuration parameters
 * <p>
 * All methods use reactive types (Mono) to support non-blocking,
 * asynchronous evaluation in a WebFlux environment.
 * <p>
 * Example implementation:
 * <pre>
 * @Component
 * public class IronCondorStrategy implements TradingStrategy {
 *
 *     @Override
 *     public String getName() {
 *         return "IronCondor";
 *     }
 *
 *     @Override
 *     public boolean isApplicable(MarketCondition condition) {
 *         return condition == MarketCondition.SIDEWAYS;
 *     }
 *
 *     @Override
 *     public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
 *         // Analyze intelligence and generate signal
 *         return Mono.just(result);
 *     }
 *
 *     @Override
 *     public StrategyConfig getConfig() {
 *         return config;
 *     }
 * }
 * </pre>
 */
public interface TradingStrategy {

    /**
     * Returns the unique name of this strategy.
     * <p>
     * Used for identification, logging, and configuration lookup.
     *
     * @return strategy name (e.g., "IronCondor", "CallSpread", "CashSecuredPut")
     */
    String getName();

    /**
     * Determines if this strategy is applicable to the given market condition.
     * <p>
     * Strategies may only be suitable for specific market conditions.
     * For example, an iron condor works best in sideways markets, while
     * a call spread works better in bullish markets.
     *
     * @param condition the current market condition
     * @return true if this strategy is applicable to the condition, false otherwise
     */
    boolean isApplicable(MarketCondition condition);

    /**
     * Evaluates market intelligence and generates a trade signal (or skip decision).
     * <p>
     * This is the main method where the strategy logic resides. It analyzes
     * the provided market intelligence (VIX, OI, max pain, option chain, etc.)
     * and decides whether to generate a trade signal or skip.
     * <p>
     * The method is non-blocking and returns a Mono that emits a StrategyResult.
     * The result contains either:
     * - A TradeSignal with isExecutable=true (ready for execution)
     * - A TradeSignal with isExecutable=false and skipReason (skipped)
     * - No signal at all (null signal)
     *
     * @param intelligence comprehensive market intelligence data
     * @return Mono emitting a StrategyResult with signal or skip decision
     */
    Mono<StrategyResult> evaluate(MarketIntelligence intelligence);

    /**
     * Returns the configuration for this strategy.
     * <p>
     * Configuration includes operational constraints like position sizing,
     * risk limits, allowed trading days, and VIX thresholds.
     *
     * @return the strategy configuration
     */
    StrategyConfig getConfig();
}
