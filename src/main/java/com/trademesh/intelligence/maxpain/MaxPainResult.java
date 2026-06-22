package com.trademesh.intelligence.maxpain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * R2DBC entity representing a Max Pain calculation result.
 * 
 * <p>Persisted to the {@code max_pain_history} table for historical analysis.
 * Max Pain is the strike price at which the maximum number of options expire worthless,
 * causing maximum loss to option buyers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("max_pain_history")
public class MaxPainResult {

    @Id
    private Long id;

    /** Trading symbol (e.g., "NIFTY50", "NIFTYBANK"). */
    private String symbol;

    /** Expiry date of the option chain. */
    @Column("expiry_date")
    private LocalDate expiryDate;

    /** Strike price with maximum pain (minimum total loss to option buyers). */
    @Column("max_pain_strike")
    private Double maxPainStrike;

    /** Current spot price of the underlying. */
    @Column("spot_price")
    private Double currentSpot;

    /** Absolute distance of max pain strike from current spot price. */
    @Column("distance_from_spot")
    private Double distanceFromSpot;

    /** Percentage distance of max pain strike from current spot price. */
    @Column("distance_percent")
    private Double distancePercent;

    /** Timestamp when this calculation was performed. */
    @Column("calculated_at")
    private Instant calculatedAt;
}
