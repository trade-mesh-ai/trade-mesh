package com.trademesh.zerodha.options;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Domain entity representing a snapshot of option chain data.
 * Persisted to PostgreSQL for historical analysis.
 */
@Data
@Builder
@Table("option_chain_snapshots")
public class OptionChainSnapshot {

    @Id
    private Long id;

    /**
     * Trading symbol (e.g., "NIFTY50", "NIFTYBANK").
     */
    private String symbol;

    /**
     * Expiry date of the option chain.
     */
    @Column("expiry_date")
    private LocalDate expiryDate;

    /**
     * Spot price of the underlying at the time of snapshot.
     */
    @Column("spot_price")
    private BigDecimal spotPrice;

    /**
     * Full option chain data serialized as JSON.
     */
    private String data;

    /**
     * Timestamp when this snapshot was created.
     */
    @Column("created_at")
    private Instant createdAt;
}
