package com.trademesh.intelligence.oi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents a significant OI (Open Interest) wall at a specific strike price.
 *
 * <p>An OI wall is a concentration of open interest at a particular strike,
 * indicating potential resistance (call wall) or support (put wall) levels.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OIWall {

    /**
     * Strike price where the OI wall exists.
     */
    private BigDecimal strikePrice;

    /**
     * Type of wall: CALL_WALL (resistance) or PUT_WALL (support).
     */
    private OIWallType wallType;

    /**
     * Total open interest at this strike (call OI + put OI).
     */
    private long totalOI;

    /**
     * Percentage change in OI from previous day.
     */
    private Double oiChangePercent;

    /**
     * Strength classification of this wall (WEAK, MODERATE, STRONG).
     */
    private OIWallStrength strength;
}
