package com.trademesh.zerodha.options;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Represents a single option strike with call and put data.
 */
@Data
@Builder
public class OptionStrike {

    /** Strike price (e.g., 24000, 24100, etc.). */
    private BigDecimal strikePrice;

    // ─── Call Option Data ─────────────────────────────────────────────────────────
    /** Call open interest. */
    private long callOI;

    /** Call open interest change. */
    private long callOIChange;

    /** Call last traded price. */
    private BigDecimal callLTP;

    /** Call implied volatility (%). */
    private BigDecimal callIV;

    // ─── Put Option Data ──────────────────────────────────────────────────────────
    /** Put open interest. */
    private long putOI;

    /** Put open interest change. */
    private long putOIChange;

    /** Put last traded price. */
    private BigDecimal putLTP;

    /** Put implied volatility (%). */
    private BigDecimal putIV;

    // ─── Aggregate Data ───────────────────────────────────────────────────────────
    /** Total open interest (call OI + put OI). */
    private long totalOI;
}
