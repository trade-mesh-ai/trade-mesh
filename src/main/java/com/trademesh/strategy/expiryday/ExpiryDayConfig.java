package com.trademesh.strategy.expiryday;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Expiry Day Strangle strategy.
 *
 * <p>Loads configuration from {@code application.yml} under the {@code strategy.expiry-day} section.
 * Controls VIX thresholds, entry time limits, credit requirements, and risk management parameters.
 *
 * <p>Example configuration:
 * <pre>
 * strategy:
 *   expiry-day:
 *     max-vix: 18.0
 *     latest-entry-time: "12:30"
 *     min-credit: 20.0
 *     min-distance-from-spot: 100
 *     max-lots: 1
 *     stop-loss-percent: 100
 *     target-profit-percent: 50
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "strategy.expiry-day")
public class ExpiryDayConfig {

    /**
     * Maximum VIX level allowed to trade this strategy.
     * If VIX exceeds this threshold, the strategy will skip signal generation.
     * Default: 18.0
     */
    private Double maxVix = 18.0;

    /**
     * Latest time of day to enter a trade (HH:mm format).
     * If current time exceeds this, the strategy will skip signal generation.
     * Default: "12:30" (12:30 PM)
     */
    private String latestEntryTime = "12:30";

    /**
     * Minimum total credit (premium) required for the strangle.
     * If total credit from both legs is below this, the strategy will skip.
     * Default: 20.0 points
     */
    private Double minCredit = 20.0;

    /**
     * Minimum distance from spot price for strike selection.
     * Call strike must be > spot + this value.
     * Put strike must be < spot - this value.
     * If not met, strikes are widened by 50 points.
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
}
