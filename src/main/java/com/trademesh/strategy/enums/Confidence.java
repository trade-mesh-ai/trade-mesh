package com.trademesh.strategy.enums;

/**
 * Enumeration of confidence levels for trading signals.
 * <p>
 * Represents the confidence level of a generated trading signal:
 * - LOW - Low confidence signal, use with caution
 * - MEDIUM - Medium confidence signal, reasonable risk/reward
 * - HIGH - High confidence signal, strong setup
 */
public enum Confidence {
    LOW("Low confidence signal"),
    MEDIUM("Medium confidence signal"),
    HIGH("High confidence signal");

    private final String description;

    Confidence(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
