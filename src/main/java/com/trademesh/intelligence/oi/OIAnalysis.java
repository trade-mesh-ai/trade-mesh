package com.trademesh.intelligence.oi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * R2DBC entity representing a complete OI (Open Interest) analysis snapshot.
 *
 * <p>Persisted to the {@code oi_snapshots} table for historical analysis and backtesting.
 * Contains analysis of resistance walls (call OI), support walls (put OI), and overall market bias.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("oi_snapshots")
public class OIAnalysis {

    @Id
    private Long id;

    /**
     * Trading symbol (e.g., "NIFTY50", "NIFTYBANK").
     */
    @Column("symbol")
    private String symbol;

    /**
     * Strike price with maximum call OI (resistance wall).
     */
    @Column("max_call_strike")
    private BigDecimal maxCallOIStrike;

    /**
     * Strike price with maximum put OI (support wall).
     */
    @Column("max_put_strike")
    private BigDecimal maxPutOIStrike;

    /**
     * Top 3 call OI walls (resistance levels).
     */
    @Column("top_call_walls")
    private String topCallWallsJson;

    /**
     * Top 3 put OI walls (support levels).
     */
    @Column("top_put_walls")
    private String topPutWallsJson;

    /**
     * Overall market bias based on OI distribution.
     */
    @Column("market_bias")
    private String marketBias;

    /**
     * Full analysis data serialized as JSON.
     */
    @Column("data")
    private String data;

    /**
     * Timestamp when this analysis was performed.
     */
    @Column("recorded_at")
    private Instant analyzedAt;

    // ─── Transient fields (not persisted) ──────────────────────────────────────────

    /**
     * Top 3 call OI walls (transient, for API responses).
     */
    @org.springframework.data.annotation.Transient
    private List<OIWall> topCallWalls;

    /**
     * Top 3 put OI walls (transient, for API responses).
     */
    @org.springframework.data.annotation.Transient
    private List<OIWall> topPutWalls;
}
