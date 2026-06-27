package com.trademesh.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.PostLoad;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import com.trademesh.strategy.TradeLeg;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL entity representing a paper (simulated) trade.
 * <p>
 * Tracks the complete lifecycle of a simulated trade including:
 * - Entry and exit details (date, time, symbol, strategy)
 * - Trade legs (stored as JSONB)
 * - Credit collected and current value
 * - Realized and unrealized P&L
 * - Exit reason and timestamps
 * <p>
 * Maps to the {@code paper_trades} table in PostgreSQL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("paper_trades")
public class PaperTrade implements Persistable<UUID> {

    /**
     * Unique identifier for this paper trade (UUID primary key).
     */
    @Id
    private UUID id;

    /**
     * Flag to indicate if this entity is new (not yet persisted).
     * Used by Spring Data R2DBC to determine INSERT vs UPDATE.
     */
    @Builder.Default
    @Transient
    @JsonIgnore
    private boolean isNew = true;

    /**
     * Date when the trade was entered.
     */
    @Column("trade_date")
    private LocalDate tradeDate;

    /**
     * Trading symbol (e.g., "NIFTY50", "BANKNIFTY").
     */
    private String symbol;

    /**
     * Name of the strategy that generated this trade.
     * Examples: "IronCondor", "CallSpread", "ExpiryDay"
     */
    @Column("strategy_name")
    private String strategyName;

    /**
     * Current status of the trade (OPEN, CLOSED, CANCELLED).
     */
    private TradeStatus status;

    /**
     * Trade legs stored as JSONB.
     * Contains list of TradeLeg objects with strike prices, actions, etc.
     * Automatically converted from/to JSONB by R2DBC converters.
     */
    @Column("legs")
    @JsonIgnoreProperties(ignoreUnknown = true)
    private List<TradeLeg> legs;

    /**
     * Premium/credit collected at entry (in points).
     * Positive value = credit received (income strategy).
     */
    @Column("entry_credit")
    private Double entryCredit;

    /**
     * Current value of the position (in points).
     * Updated via mark-to-market calculations.
     */
    @Column("current_value")
    private Double currentValue;

    /**
     * Realized P&L after trade closure (in points).
     * Calculated as: entryCredit - finalValue
     * Only populated when status = CLOSED.
     */
    @Column("realized_pnl")
    private Double realizedPnl;

    /**
     * Unrealized P&L based on current mark-to-market value (in points).
     * Calculated as: entryCredit - currentValue
     * Updated continuously while trade is OPEN.
     */
    @Column("unrealized_pnl")
    private Double unrealizedPnl;

    /**
     * Timestamp when the trade was entered.
     */
    @Column("entry_time")
    private LocalDateTime entryTime;

    /**
     * Timestamp when the trade was exited.
     * Only populated when status = CLOSED.
     */
    @Column("exit_time")
    private LocalDateTime exitTime;

    /**
     * Reason for trade exit.
     * Examples: "TARGET_HIT", "STOP_LOSS", "MANUAL_EXIT", "EXPIRY"
     */
    @Column("exit_reason")
    private String exitReason;

    /**
     * Timestamp when this record was created.
     */
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * Timestamp when this record was last updated.
     */
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * Called by Spring after loading entity from database.
     * Marks the entity as no longer new to ensure subsequent saves use UPDATE.
     */
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

}
