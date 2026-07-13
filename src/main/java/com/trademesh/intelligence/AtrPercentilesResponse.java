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
 * Contains percentile distribution of ATR% values computed from historical
 * NIFTY50 daily price data, along with min/max and count statistics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AtrPercentilesResponse {

    /**
     * Total count of ATR% values computed.
     */
    @JsonProperty("count")
    private int count;

    /**
     * Minimum ATR% value in the dataset.
     */
    @JsonProperty("min")
    private BigDecimal min;

    /**
     * 10th percentile of ATR% distribution.
     */
    @JsonProperty("p10")
    private BigDecimal p10;

    /**
     * 25th percentile of ATR% distribution.
     */
    @JsonProperty("p25")
    private BigDecimal p25;

    /**
     * 40th percentile of ATR% distribution.
     */
    @JsonProperty("p40")
    private BigDecimal p40;

    /**
     * 50th percentile (median) of ATR% distribution.
     */
    @JsonProperty("p50")
    private BigDecimal p50;

    /**
     * 60th percentile of ATR% distribution.
     */
    @JsonProperty("p60")
    private BigDecimal p60;

    /**
     * 75th percentile of ATR% distribution.
     */
    @JsonProperty("p75")
    private BigDecimal p75;

    /**
     * 90th percentile of ATR% distribution.
     */
    @JsonProperty("p90")
    private BigDecimal p90;

    /**
     * Maximum ATR% value in the dataset.
     */
    @JsonProperty("max")
    private BigDecimal max;
}
