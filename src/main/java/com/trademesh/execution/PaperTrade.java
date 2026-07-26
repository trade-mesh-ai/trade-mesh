package com.trademesh.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.trademesh.config.BigDecimalTwoDecimalSerializer;
import com.trademesh.strategy.TradeLeg;
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
     * Default is false because entities loaded from DB are not new.
     * Only use createNew() factory method to create entities with isNew=true.
     */
    @Builder.Default
    @Transient
    @JsonIgnore
    private boolean isNew = false;

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
    @JsonSerialize(using = BigDecimalTwoDecimalSerializer.class)
    private BigDecimal entryCredit;

    /**
     * Current value of the position (in points).
     * Updated via mark-to-market calculations.
     */
    @Column("current_value")
    @JsonSerialize(using = BigDecimalTwoDecimalSerializer.class)
    private BigDecimal currentValue;

    /**
     * Realized P&L after trade closure (in points).
     * Calculated as: entryCredit - finalValue
     * Only populated when status = CLOSED.
     */
    @Column("realized_pnl")
    @JsonSerialize(using = BigDecimalTwoDecimalSerializer.class)
    private BigDecimal realizedPnl;

    /**
     * Unrealized P&L based on current mark-to-market value (in points).
     * Calculated as: entryCredit - currentValue
     * Updated continuously while trade is OPEN.
     */
    @Column("unrealized_pnl")
    @JsonSerialize(using = BigDecimalTwoDecimalSerializer.class)
    private BigDecimal unrealizedPnl;

    /**
     * Maximum loss for this trade (in rupees).
     * Set once at trade creation from signal.getMaxLoss(), never updated.
     * Nullable — existing/historical trades and strategies without defined max loss will have NULL.
     * Used for portfolio-level aggregate risk budgeting.
     */
    @Column("max_loss")
    @JsonSerialize(using = BigDecimalTwoDecimalSerializer.class)
    private BigDecimal maxLoss;

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

    /**
     * Stop distance in points (unadjusted, no multiplier applied).
     * Computed at entry time as: entryCredit × (stopLossPercent / 100.0)
     * Nullable — may be null for trades from strategies that don't compute this.
     */
    @Column("stop_distance_points")
    private BigDecimal stopDistancePoints;

    /**
     * Target threshold in points (unadjusted, no multiplier applied).
     * Computed at entry time as: entryCredit × (targetProfitPercent / 100.0)
     * Nullable — may be null for trades from strategies that don't compute this.
     */
    @Column("target_threshold")
    private BigDecimal targetThreshold;

    /**
     * Planned risk per lot in rupees.
     * Computed at entry time as: stopDistancePoints × lotSize
     * Nullable — may be null for trades from strategies that don't compute this.
     */
    @Column("planned_risk_per_lot")
    private BigDecimal plannedRiskPerLot;

    /**
     * ATR percentile at entry time.
     * Read from MarketMetrics during signal generation.
     * Nullable — may be null if ATR percentile unavailable at entry.
     */
    @Column("atr_percentile")
    private Integer atrPercentile;

    /**
     * Confidence score at entry time (raw numeric, before enum bucketing).
     * Computed by strategy's calculateConfidenceScore() method.
     * Nullable — may be null for strategies that don't compute a raw score.
     */
    @Column("confidence_score")
    private Integer confidenceScore;

    /**
     * Risk snapshot as JSON string.
     * Captures entry-time risk metrics for later reconstruction:
     * - atrPercentile, baseStopLossPercent, entryCredit, lotSize, strategy name, timestamp
     * Nullable — may be null if not populated at entry.
     */
    @Column("risk_snapshot")
    private String riskSnapshot;

    /**
     * Factory method to create a new PaperTrade entity for insertion.
     * Sets isNew=true and generates a UUID for the trade.
     * Use this method whenever creating a new trade that needs to be persisted.
     *
     * @return a new PaperTrade with isNew=true and a generated UUID
     */
    public static PaperTrade createNew() {
        PaperTrade trade = new PaperTrade();
        trade.isNew = true;
        trade.id = UUID.randomUUID();
        return trade;
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
