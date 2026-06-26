package com.trademesh.zerodha.options;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Represents a single strike in an option chain, containing both call and put option data.
 * This class is used to encapsulate the details of a specific strike price, including open interest, last traded price, and implied volatility for both call and put options.
 */
@Data
@AllArgsConstructor
@Builder
public class OptionStrike {

    /**
     * Strike price (e.g., 24000, 24100, etc.).
     */
    private BigDecimal strikePrice;

    // ─── Call Option Data ─────────────────────────────────────────────────────────
    /**
     * Call open interest.
     */
    private long callOI;

    /**
     * Call open interest change.
     */
    private long callOIChange;

    /**
     * Call last traded price.
     */
    private BigDecimal callLTP;

    /**
     * Call implied volatility (%).
     */
    private BigDecimal callIV;

    // ─── Put Option Data ──────────────────────────────────────────────────────────
    /**
     * Put open interest.
     */
    private long putOI;

    /**
     * Put open interest change.
     */
    private long putOIChange;

    /**
     * Put last traded price.
     */
    private BigDecimal putLTP;

    /**
     * Put implied volatility (%).
     */
    private BigDecimal putIV;

    // ─── Aggregate Data ───────────────────────────────────────────────────────────
    /**
     * Total open interest (call OI + put OI).
     */
    private long totalOI;
}
