package com.trademesh.intelligence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Market metrics DTO containing calculated volatility and technical indicators.
 * This is a plain DTO (not a DB entity) used for REST responses only.
 * Includes HV20, ATR14, IVR, IV/HV ratio, and ATR percentile calculations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketMetrics {

    /**
     * Trading symbol (e.g., "NIFTY50", "NIFTYBANK").
     */
    private String symbol;

    /**
     * Timestamp when these metrics were calculated.
     */
    private LocalDateTime calculatedAt;

    /**
     * Current spot price of the underlying.
     */
    private BigDecimal spotPrice;

    /**
     * 20-day historical volatility, annualized as percentage.
     * Calculated from 21 days of price data (20 log returns).
     * Example: 14.5000 for 14.5%
     */
    private BigDecimal hv20;

    /**
     * 14-day average true range in points.
     * Example: 185.50
     */
    private BigDecimal atr14;

    /**
     * ATR percentile (0-100).
     * Percentage of days in last 60 days where TR < current ATR14.
     * Example: 45.00
     */
    private BigDecimal atrPercentile;

    /**
     * Current ATM (At-The-Money) implied volatility as percentage.
     * Fetched from live option chain.
     * Example: 15.06 for 15.06%
     */
    private BigDecimal currentAtmIv;

    /**
     * IV Rank (0-100).
     * Measures where current IV stands relative to 365-day range.
     * Null if insufficient IV history (< 30 days).
     */
    private BigDecimal ivr;

    /**
     * IV/HV ratio.
     * Ratio of current ATM IV to HV20.
     * Null if HV20 is zero or unavailable.
     * Example: 1.0400
     */
    private BigDecimal ivHvRatio;

    /**
     * Number of days of IV history available (from last 365 days).
     */
    private int ivHistoryDays;

    /**
     * True if at least 21 days of price data exists for HV20 calculation.
     */
    private boolean hv20Available;

    /**
     * True if at least 30 days of IV history exists for IVR calculation.
     */
    private boolean ivrAvailable;

     private BigDecimal atrPct; // ATR14 / spotPrice × 100

     /**
      * Put-Call Ratio = total put OI / total call OI across all strikes.
      * null if option chain data unavailable.
      * Balanced range: 0.8 - 1.2
      */
     private BigDecimal pcr;
}
