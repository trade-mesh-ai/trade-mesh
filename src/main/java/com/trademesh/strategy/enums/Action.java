package com.trademesh.strategy.enums;

/**
 * Enumeration of trade leg actions.
 *
 * <p>Represents the action to be taken for a specific leg in a multi-leg strategy:
 * <ul>
 *   <li>BUY - Buy the instrument (long position)</li>
 *   <li>SELL - Sell the instrument (short position)</li>
 * </ul>
 */
public enum Action {
    BUY("Buy the instrument"),
    SELL("Sell the instrument");

    private final String description;

    Action(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
