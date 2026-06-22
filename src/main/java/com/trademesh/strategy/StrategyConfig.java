package com.trademesh.strategy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.util.List;

/**
 * Configuration parameters for a trading strategy.
 * <p>
 * Defines the operational constraints and parameters that govern how a
 * strategy behaves, including position sizing, risk limits, market conditions,
 * and trading hours.
 * <p>
 * This configuration is typically loaded from a database or configuration
 * file and used by the strategy implementation to make decisions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConfig {

    /**
     * Name of the strategy (e.g., "IronCondor", "CallSpread").
     * Used for identification and logging.
     */
    private String strategyName;

    /**
     * Maximum number of lots per trade.
     * Controls position size to limit exposure.
     * Example: maxLotsPerTrade=2 means max 2 lots (150 contracts for NIFTY).
     */
    private Integer maxLotsPerTrade;

    /**
     * Stop loss percentage for the strategy.
     * If loss reaches this percentage of max loss, position is exited.
     * Example: stopLossPercent=50.0 means exit at 50% of max loss.
     */
    private Double stopLossPercent;

    /**
     * Target profit percentage for the strategy.
     * If profit reaches this percentage of expected credit, position is exited.
     * Example: targetProfitPercent=50.0 means exit at 50% of expected credit.
     */
    private Double targetProfitPercent;

    /**
     * List of days of the week when this strategy is allowed to trade.
     * Example: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
     * Useful for avoiding expiry days or specific market conditions.
     */
    private List<DayOfWeek> allowedDays;

    /**
     * Minimum VIX level required to trade this strategy.
     * Strategy will not generate signals if VIX is below this threshold.
     * Example: minVix=12.0 means only trade when VIX >= 12.
     */
    private Double minVix;

    /**
     * Maximum VIX level allowed to trade this strategy.
     * Strategy will not generate signals if VIX exceeds this threshold.
     * Example: maxVix=20.0 means only trade when VIX <= 20.
     */
    private Double maxVix;

    /**
     * Whether this strategy is currently enabled.
     * If false, the strategy will not be evaluated or executed.
     */
    private Boolean isEnabled;
}
