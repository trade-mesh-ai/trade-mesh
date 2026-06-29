package com.trademesh.trading;

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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PostgreSQL entity for logging strategy execution events.
 * <p>
 * Tracks every strategy evaluation and execution for audit and analysis purposes.
 * Maps to the strategy_execution_logs table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("strategy_execution_logs")
public class StrategyExecutionLog implements Persistable<UUID> {

    /**
     * Unique identifier for this log entry (UUID primary key).
     */
    @Id
    private UUID id;

    /**
     * Flag to indicate if this entity is new (not yet persisted).
     * Used by Spring Data R2DBC to determine INSERT vs UPDATE.
     */
    @Builder.Default
    @Transient
    private boolean isNew = true;

    /**
     * Trading symbol (e.g., "NIFTY", "BANKNIFTY").
     */
    private String symbol;

    /**
     * Name of the strategy that was executed.
     * Examples: "IRON_CONDOR", "EXPIRY_DAY", "BULL_PUT_SPREAD"
     */
    @Column("strategy_name")
    private String strategyName;

    /**
     * Market condition at the time of execution.
     * Examples: "SIDEWAYS", "TRENDING_BULLISH", "TRENDING_BEARISH", "EXPIRY_DAY"
     */
    @Column("market_condition")
    private String marketCondition;

    /**
     * Action taken (ENTER, EXIT, ADJUST, SKIP).
     */
    private String action;

    /**
     * Plain English reason for the signal.
     * Examples: "VIX at 15, NIFTY at support, IV rank 60%"
     */
    private String reason;

    /**
     * VIX level at the time of execution.
     */
    @Column("vix_level")
    private Double vixLevel;

    /**
     * Timestamp when this execution was logged.
     */
    @Column("executed_at")
    private LocalDateTime executedAt;

    /**
     * Timestamp when this record was created.
     */
    @Column("created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

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
