package com.trademesh.strategy.enums;

/**
 * Enumeration of option types.
 * <p>
 * Represents the type of option contract:
 * - CE - Call option (right to buy)
 * - PE - Put option (right to sell)
 */
public enum OptionType {
    CE("Call option - right to buy"),
    PE("Put option - right to sell");

    private final String description;

    OptionType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
