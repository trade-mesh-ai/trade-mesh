package com.trademesh.intelligence;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for ATR% percentile distribution analysis.
 * <p>
 * TEMPORARY DIAGNOSTIC CLASS — Used for one-time ATR% percentile analysis.
 * This class will be removed once the analysis is complete.
 * <p>
 * Contains three separate percentile calculations to isolate the cause of
 * the atrPercentile discrepancy:
 * 1. fullHistorySmoothedVsSmoothed: Full history, ATR14 vs ATR14 (baseline)
 * 2. fullHistorySmoothedVsRaw: Full history, ATR14 vs raw TR (isolates comparison method)
 * 3. last60DaysSmoothedVsRaw: 60-day window, ATR14 vs raw TR (replicates production)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AtrPercentilesResponse {

    // ===== CALCULATION 1: Full History, Smoothed vs Smoothed (BASELINE) =====

    /**
     * Total count of ATR% values computed (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_count")
    private int fullHistorySmoothedVsSmoothed_count;

    /**
     * Minimum ATR% value (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_min")
    private BigDecimal fullHistorySmoothedVsSmoothed_min;

    /**
     * 10th percentile (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_p10")
    private BigDecimal fullHistorySmoothedVsSmoothed_p10;

    /**
     * 25th percentile (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_p25")
    private BigDecimal fullHistorySmoothedVsSmoothed_p25;

    /**
     * 40th percentile (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_p40")
    private BigDecimal fullHistorySmoothedVsSmoothed_p40;

    /**
     * 50th percentile (median, full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_p50")
    private BigDecimal fullHistorySmoothedVsSmoothed_p50;

    /**
     * 60th percentile (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_p60")
    private BigDecimal fullHistorySmoothedVsSmoothed_p60;

    /**
     * 75th percentile (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_p75")
    private BigDecimal fullHistorySmoothedVsSmoothed_p75;

    /**
     * 90th percentile (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_p90")
    private BigDecimal fullHistorySmoothedVsSmoothed_p90;

    /**
     * Maximum ATR% value (full history, smoothed-vs-smoothed).
     */
    @JsonProperty("fullHistorySmoothedVsSmoothed_max")
    private BigDecimal fullHistorySmoothedVsSmoothed_max;

    // ===== CALCULATION 2: Full History, Smoothed vs Raw (ISOLATES COMPARISON METHOD) =====

    /**
     * Total count of TR values (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_count")
    private int fullHistorySmoothedVsRaw_count;

    /**
     * Minimum TR value (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_min")
    private BigDecimal fullHistorySmoothedVsRaw_min;

    /**
     * 10th percentile (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_p10")
    private BigDecimal fullHistorySmoothedVsRaw_p10;

    /**
     * 25th percentile (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_p25")
    private BigDecimal fullHistorySmoothedVsRaw_p25;

    /**
     * 40th percentile (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_p40")
    private BigDecimal fullHistorySmoothedVsRaw_p40;

    /**
     * 50th percentile (median, full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_p50")
    private BigDecimal fullHistorySmoothedVsRaw_p50;

    /**
     * 60th percentile (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_p60")
    private BigDecimal fullHistorySmoothedVsRaw_p60;

    /**
     * 75th percentile (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_p75")
    private BigDecimal fullHistorySmoothedVsRaw_p75;

    /**
     * 90th percentile (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_p90")
    private BigDecimal fullHistorySmoothedVsRaw_p90;

    /**
     * Maximum TR value (full history, smoothed-vs-raw).
     */
    @JsonProperty("fullHistorySmoothedVsRaw_max")
    private BigDecimal fullHistorySmoothedVsRaw_max;

    // ===== CALCULATION 3: Last 60 Days, Smoothed vs Raw (REPLICATES PRODUCTION) =====

    /**
     * Total count of TR values (last 60 days, smoothed-vs-raw).
     * This exactly replicates MarketMetricsService.calculateATRPercentile().
     */
    @JsonProperty("last60DaysSmoothedVsRaw_count")
    private int last60DaysSmoothedVsRaw_count;

    /**
     * Minimum TR value (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_min")
    private BigDecimal last60DaysSmoothedVsRaw_min;

    /**
     * 10th percentile (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_p10")
    private BigDecimal last60DaysSmoothedVsRaw_p10;

    /**
     * 25th percentile (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_p25")
    private BigDecimal last60DaysSmoothedVsRaw_p25;

    /**
     * 40th percentile (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_p40")
    private BigDecimal last60DaysSmoothedVsRaw_p40;

    /**
     * 50th percentile (median, last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_p50")
    private BigDecimal last60DaysSmoothedVsRaw_p50;

    /**
     * 60th percentile (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_p60")
    private BigDecimal last60DaysSmoothedVsRaw_p60;

    /**
     * 75th percentile (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_p75")
    private BigDecimal last60DaysSmoothedVsRaw_p75;

    /**
     * 90th percentile (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_p90")
    private BigDecimal last60DaysSmoothedVsRaw_p90;

    /**
     * Maximum TR value (last 60 days, smoothed-vs-raw).
     */
    @JsonProperty("last60DaysSmoothedVsRaw_max")
    private BigDecimal last60DaysSmoothedVsRaw_max;
}
