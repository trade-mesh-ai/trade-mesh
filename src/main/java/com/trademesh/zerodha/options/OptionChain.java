package com.trademesh.zerodha.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Represents the complete option chain for a symbol (NIFTY / BANKNIFTY).
 * Contains all strikes for the nearest weekly expiry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptionChain {

    /**
     * Trading symbol (e.g., "NIFTY50", "NIFTYBANK").
     */
    private String symbol;

    /**
     * Expiry date of the option chain.
     */
    private LocalDate expiryDate;

    /**
     * Current spot price of the underlying.
     */
    private BigDecimal spotPrice;

    /**
     * List of all strikes for this option chain.
     */
    private List<OptionStrike> strikes;

    /**
     * Timestamp when this option chain was fetched.
     */
    private Instant fetchedAt;
}
