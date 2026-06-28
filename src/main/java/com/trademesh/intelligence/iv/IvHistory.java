package com.trademesh.intelligence.iv;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PostgreSQL entity representing daily ATM (At-The-Money) implied volatility data.
 * Stores ATM IV for Iron Condor strategy analysis.
 * Maps to the {@code iv_history} table in PostgreSQL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("iv_history")
public class IvHistory implements Persistable<UUID> {

    /**
     * Unique identifier for this IV history record (UUID primary key).
     */
    @Id
    private UUID id;

    /**
     * Flag to indicate if this entity is new (not yet persisted).
     * Used by Spring Data R2DBC to determine INSERT vs UPDATE.
     * Defaults to FALSE for loaded entities, set to TRUE by createNew() factory method.
     */
    @Builder.Default
    @Transient
    @JsonIgnore
    private boolean isNew = false;

    /**
     * Trading symbol (e.g., "NIFTY50", "NIFTYBANK").
     */
    @Column("symbol")
    private String symbol;

    /**
     * Trading date for this IV data.
     */
    @Column("trade_date")
    private LocalDate tradeDate;

    /**
     * ATM strike price (strike closest to spot price).
     */
    @Column("atm_strike")
    private BigDecimal atmStrike;

    /**
     * ATM implied volatility as percentage (e.g., 14.5000 for 14.5%).
     * Calculated as average of call IV and put IV at ATM strike.
     */
    @Column("atm_iv")
    private BigDecimal atmIv;

    /**
     * Call option implied volatility at ATM strike as percentage.
     */
    @Column("call_iv")
    private BigDecimal callIv;

    /**
     * Put option implied volatility at ATM strike as percentage.
     */
    @Column("put_iv")
    private BigDecimal putIv;

    /**
     * Spot price of the underlying at the time of recording.
     */
    @Column("spot_price")
    private BigDecimal spotPrice;

    /**
     * Expiry date of the option chain used for IV calculation.
     */
    @Column("expiry_date")
    private LocalDate expiryDate;

    /**
     * Timestamp when this record was created.
     */
    @Column("recorded_at")
    private LocalDateTime recordedAt;

    /**
     * Factory method to create a new IvHistory entity.
     * Sets isNew flag to true and generates a new UUID.
     *
     * @return a new IvHistory instance ready for persistence
     */
    public static IvHistory createNew() {
        IvHistory iv = new IvHistory();
        iv.isNew = true;
        iv.id = UUID.randomUUID();
        return iv;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
