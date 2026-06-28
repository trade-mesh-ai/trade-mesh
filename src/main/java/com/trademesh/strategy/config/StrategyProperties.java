package com.trademesh.strategy.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for a single strategy from the strategies list.
 * Represents one entry in the strategies: list from application.yml.
 */
@Data
public class StrategyProperties {

    /**
     * Name of the strategy (e.g., "EXPIRY_DAY_STRANGLE", "THETA_SPREAD").
     * Used for identification and lookup.
     */
    private String name;

    /**
     * Whether this strategy is currently enabled.
     * If false, the strategy will not be evaluated or executed.
     */
    private boolean enabled;

    /**
     * List of days of the week when this strategy is allowed to trade.
     * Example: [THURSDAY] or [MONDAY, TUESDAY, WEDNESDAY, FRIDAY]
     * Useful for avoiding expiry days or specific market conditions.
     */
    private List<String> allowedDays = new ArrayList<>();

    /**
     * List of market conditions when this strategy is applicable.
     * Example: [EXPIRY_DAY] or [SIDEWAYS]
     */
    private List<String> applicableConditions = new ArrayList<>();

    /**
     * Maximum VIX level allowed to trade this strategy.
     * Strategy will not generate signals if VIX exceeds this threshold.
     * Default: 20.0
     */
    private Double maxVix = 20.0;

    /**
     * Minimum VIX level required to trade this strategy.
     * Strategy will not generate signals if VIX is below this threshold.
     * Default: 8.0
     */
    private Double minVix = 8.0;

    /**
     * Latest time of day to enter a trade (HH:mm format).
     * If current time exceeds this, the strategy will skip signal generation.
     * Default: "13:00" (1:00 PM)
     */
    private String latestEntryTime = "13:00";

    /**
     * Minimum total credit (premium) required for the trade.
     * If total credit is below this, the strategy will skip.
     * Default: 10.0 points
     */
    private Double minCredit = 10.0;

    /**
     * Minimum distance from spot price for strike selection.
     * Call strike must be > spot + this value.
     * Put strike must be < spot - this value.
     * Default: 100 points
     */
    private Integer minDistanceFromSpot = 100;

    /**
     * Maximum number of lots per trade.
     * Controls position size to limit exposure.
     * Default: 1 lot
     */
    private Integer maxLots = 1;

    /**
     * Stop loss percentage for the strategy.
     * If loss reaches this percentage of max loss, position is exited.
     * Example: 100.0 means exit if premium doubles (100% loss).
     * Default: 100.0
     */
    private Double stopLossPercent = 100.0;

    /**
     * Target profit percentage for the strategy.
     * If profit reaches this percentage of expected credit, position is exited.
     * Example: 50.0 means exit if premium halves (50% profit).
     * Default: 50.0
     */
    private Double targetProfitPercent = 50.0;

    /**
     * Strategy-specific extra configuration parameters.
     * Allows each strategy to define custom fields without
     * polluting the shared StrategyProperties model.
     */
    private Map<String, Object> extra = new HashMap<>();

    /**
     * Safely retrieves a double value from the extra map.
     *
     * @param key          the configuration key
     * @param defaultValue the default value if key not found or conversion fails
     * @return the double value or defaultValue
     */
    public double getExtraDouble(String key, double defaultValue) {
        Object val = extra.get(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Safely retrieves an integer value from the extra map.
     *
     * @param key          the configuration key
     * @param defaultValue the default value if key not found or conversion fails
     * @return the integer value or defaultValue
     */
    public int getExtraInt(String key, int defaultValue) {
        Object val = extra.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
