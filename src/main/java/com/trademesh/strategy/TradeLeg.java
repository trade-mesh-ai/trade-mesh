package com.trademesh.strategy;

import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.LegType;
import com.trademesh.strategy.enums.OptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Represents a single leg of a multi-leg trading strategy.
 *
 * <p>A trade leg defines a specific action on an instrument (buy/sell) with
 * associated parameters like strike price, expiry, quantity, and limit price.
 * Multiple legs combine to form a complete trading strategy (e.g., call spread,
 * iron condor, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeLeg {

    /**
     * Trading instrument identifier (e.g., "NIFTY26JUN22300CE").
     * Format: SYMBOL + EXPIRY + STRIKE + OPTION_TYPE
     */
    private String instrument;

    /**
     * Action to be taken on this leg (BUY or SELL).
     */
    private Action action;

    /**
     * Type of option contract (CE for Call, PE for Put).
     */
    private OptionType optionType;

    /**
     * Strike price of the option.
     */
    private Double strikePrice;

    /**
     * Expiry date of the option contract.
     */
    private LocalDate expiryDate;

    /**
     * Quantity to trade in lots (not individual contracts).
     * For example, quantity=1 means 1 lot (typically 75 contracts for NIFTY options).
     */
    private Integer quantity;

    /**
     * Limit price for order execution.
     * For BUY legs: maximum price willing to pay.
     * For SELL legs: minimum price willing to accept.
     */
    private Double limitPrice;

    /**
     * Type of leg within the strategy (ENTRY or HEDGE).
     * ENTRY legs are primary income-generating legs.
     * HEDGE legs are protective legs to limit downside.
     */
    private LegType legType;
}
