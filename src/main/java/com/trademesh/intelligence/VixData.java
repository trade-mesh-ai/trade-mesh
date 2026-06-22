package com.trademesh.intelligence;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Represents current VIX (Volatility Index) data.
 *
 * <p>Contains the current VIX value, its level classification, previous close,
 * percentage change, and timestamp of when the data was fetched.
 */
@Data
@Builder
public class VixData {

    /**
     * Current VIX value
     */
    private Double currentVix;

    /**
     * VIX level classification (LOW, NORMAL, ELEVATED, HIGH, EXTREME)
     */
    private VixLevel vixLevel;

    /**
     * Previous day's closing VIX value
     */
    private Double previousClose;

    /**
     * Percentage change from previous close
     */
    private Double changePercent;

    /**
     * Timestamp when this VIX data was fetched
     */
    private Instant fetchedAt;

    /**
     * Determines if it is safe to trade based on current VIX level.
     *
     * <p>Returns true only if VIX is in LOW or NORMAL range, indicating
     * a calm market with lower volatility.
     *
     * @return true if VIX level is LOW or NORMAL, false otherwise
     */
    public boolean isSafeToTrade() {
        return vixLevel == VixLevel.LOW || vixLevel == VixLevel.NORMAL;
    }
}
