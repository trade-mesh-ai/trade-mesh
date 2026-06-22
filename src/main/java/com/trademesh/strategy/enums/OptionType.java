package com.trademesh.strategy.enums;

/**
 * Enumeration of option types.
 *
 * <p>Represents the type of option contract:
 * <ul>
 *   <li>CE - Call option (right to buy)</li>
 *   <li>PE - Put option (right to sell)</li>
 * </ul>
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
