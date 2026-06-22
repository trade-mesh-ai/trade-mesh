package com.trademesh.execution;

/**
 * Enumeration of paper trade statuses.
 *
 * <p>Represents the lifecycle state of a paper trade:
 * <ul>
 *   <li>OPEN - Trade is currently active and being monitored</li>
 *   <li>CLOSED - Trade has been exited and P&L is realized</li>
 *   <li>CANCELLED - Trade was cancelled before execution</li>
 * </ul>
 */
public enum TradeStatus {
    OPEN("Trade is currently active"),
    CLOSED("Trade has been closed and P&L realized"),
    CANCELLED("Trade was cancelled before execution");

    private final String description;

    TradeStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
