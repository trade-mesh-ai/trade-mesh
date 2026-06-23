package com.trademesh.trading;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
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
public class StrategyExecutionLog {

    /**
     * Unique identifier for this log entry (UUID primary key).
     */
    @Id
    private UUID id;

    /**
     * Trading symbol (e.g., "NIFTY", "BANKNIFTY").
     */
    private String symbol;

    /**
     * Name of the strategy that was executed.
     * Examples: "IRON_CONDOR", "THETA_SPREAD", "BULL_PUT_SPREAD"
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
}
