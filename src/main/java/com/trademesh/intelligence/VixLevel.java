package com.trademesh.intelligence;

/**
 * Enumeration of VIX levels based on current VIX value.
 *
 * VIX (Volatility Index) ranges:
 * - LOW: VIX < 13 (Low volatility, calm market)
 * - NORMAL: VIX 13-17 (Normal volatility)
 * - ELEVATED: VIX 17-20 (Elevated volatility, caution advised)
 * - HIGH: VIX 20-25 (High volatility, risk alert)
 * - EXTREME: VIX > 25 (Extreme volatility, market stress)
 */
public enum VixLevel {
    LOW(0, 13),
    NORMAL(13, 17),
    ELEVATED(17, 20),
    HIGH(20, 25),
    EXTREME(25, Double.MAX_VALUE);

    private final double minValue;
    private final double maxValue;

    VixLevel(double minValue, double maxValue) {
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public double getMinValue() {
        return minValue;
    }

    public double getMaxValue() {
        return maxValue;
    }

    /**
     * Determines the VixLevel for a given VIX value.
     *
     * @param vixValue the current VIX value
     * @return the corresponding VixLevel
     */
    public static VixLevel fromValue(double vixValue) {
        if (vixValue < 13) {
            return LOW;
        } else if (vixValue < 17) {
            return NORMAL;
        } else if (vixValue < 20) {
            return ELEVATED;
        } else if (vixValue < 25) {
            return HIGH;
        } else {
            return EXTREME;
        }
    }
}
