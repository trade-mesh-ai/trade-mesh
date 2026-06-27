package com.trademesh.strategy.enums;

/**
 * Enumeration of trading signal types.
 * <p>
 * Represents the action type of a trading signal:
 * - ENTER - Signal to enter a new position
 * - EXIT - Signal to exit an existing position
 * - ADJUST - Signal to adjust an existing position (modify legs, hedge, etc.)
 */
public enum SignalType {
    ENTER("Enter a new position"),
    EXIT("Exit an existing position"),
    ADJUST("Adjust an existing position");

    private final String description;

    SignalType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
