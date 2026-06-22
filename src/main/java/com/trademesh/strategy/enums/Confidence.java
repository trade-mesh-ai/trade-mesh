package com.trademesh.strategy.enums;

/**
 * Enumeration of confidence levels for trading signals.
 *
 * <p>Represents the confidence level of a generated trading signal:
 * <ul>
 *   <li>LOW - Low confidence signal, use with caution</li>
 *   <li>MEDIUM - Medium confidence signal, reasonable risk/reward</li>
 *   <li>HIGH - High confidence signal, strong setup</li>
 * </ul>
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
