package com.trademesh.intelligence;

/**
 * Enumeration of market conditions based on VIX, OI analysis, and calendar.
 *
 * <p>Market condition classification:
 * <ul>
 *   <li>SIDEWAYS - Low VIX (<17), neutral OI bias, range-bound market</li>
 *   <li>TRENDING_BULLISH - Bullish OI bias, upward momentum</li>
 *   <li>TRENDING_BEARISH - Bearish OI bias, downward momentum</li>
 *   <li>EXPIRY_DAY - Tuesday (NIFTY50 weekly/monthly, BANKNIFTY monthly)</li>
 *   <li>HIGH_VOLATILITY - VIX > 20, elevated market stress</li>
 *   <li>NO_TRADE - Extreme VIX (>25) or event risk, avoid trading</li>
 * </ul>
 */
public enum MarketCondition {
    SIDEWAYS("Range-bound market with low volatility"),
    TRENDING_BULLISH("Strong upward momentum with bullish bias"),
    TRENDING_BEARISH("Strong downward momentum with bearish bias"),
    EXPIRY_DAY("Options expiry day - increased volatility expected"),
    HIGH_VOLATILITY("Elevated volatility - caution advised"),
    NO_TRADE("Extreme volatility or event risk - avoid trading");

    private final String description;

    MarketCondition(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
