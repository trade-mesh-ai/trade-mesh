package com.trademesh.domain;

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
 * PostgreSQL entity representing NIFTY50 historical daily OHLC data.
 * <p>
 * Stores daily open, high, low, close prices and volume for NIFTY50 index.
 * Maps to the {@code nifty_daily_prices} table in PostgreSQL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("nifty_daily_prices")
public class NiftyDailyPrice implements Persistable<UUID> {

    /**
     * Unique identifier for this daily price record (UUID primary key).
     */
    @Id
    private UUID id;

    /**
     * Flag to indicate if this entity is new (not yet persisted).
     * Used by Spring Data R2DBC to determine INSERT vs UPDATE.
     */
    @Transient
    @JsonIgnore
    private boolean isNew = false;

    /**
     * Trading date for this OHLC data.
     */
    @Column("trade_date")
    private LocalDate tradeDate;

    /**
     * Opening price for the day.
     */
    @Column("open")
    private BigDecimal open;

    /**
     * Highest price during the day.
     */
    @Column("high")
    private BigDecimal high;

    /**
     * Lowest price during the day.
     */
    @Column("low")
    private BigDecimal low;

    /**
     * Closing price for the day.
     */
    @Column("close")
    private BigDecimal close;

    /**
     * Trading volume for the day.
     */
    @Column("volume")
    private Long volume;

    /**
     * Timestamp when this record was created.
     */
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * Factory method to create a new NiftyDailyPrice entity.
     * Sets isNew=true to indicate this is a new record not yet persisted.
     *
     * @return a new NiftyDailyPrice instance with isNew=true
     */
    public static NiftyDailyPrice createNew() {
        NiftyDailyPrice price = new NiftyDailyPrice();
        price.isNew = true;
        price.id = UUID.randomUUID();
        return price;
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
