package com.trademesh.strategy.enums;

/**
 * Enumeration of trade leg types.
 *
 * <p>Represents the role of a leg within a multi-leg strategy:
 * <ul>
 *   <li>ENTRY - Primary entry leg for the strategy</li>
 *   <li>HEDGE - Hedging leg to limit downside risk</li>
 * </ul>
 */
public enum LegType {
    ENTRY("Primary entry leg for the strategy"),
    HEDGE("Hedging leg to limit downside risk");

    private final String description;

    LegType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
