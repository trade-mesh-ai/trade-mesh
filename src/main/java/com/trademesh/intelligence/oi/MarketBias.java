package com.trademesh.intelligence.oi;

/**
 * Enumeration of market bias based on OI analysis.
 * 
 * <p>Market bias classification:
 * <ul>
 *   <li>BULLISH - Put OI significantly higher than call OI (support dominant)</li>
 *   <li>BEARISH - Call OI significantly higher than put OI (resistance dominant)</li>
 *   <li>NEUTRAL - Call and put OI within 10% of each other (balanced)</li>
 * </ul>
 */
public enum MarketBias {
    BULLISH,
    BEARISH,
    NEUTRAL
}
