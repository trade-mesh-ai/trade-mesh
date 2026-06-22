package com.trademesh.strategy.enums;

/**
 * Enumeration of trading signal types.
 *
 * <p>Represents the action type of a trading signal:
 * <ul>
 *   <li>ENTER - Signal to enter a new position</li>
 *   <li>EXIT - Signal to exit an existing position</li>
 *   <li>ADJUST - Signal to adjust an existing position (modify legs, hedge, etc.)</li>
 * </ul>
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
