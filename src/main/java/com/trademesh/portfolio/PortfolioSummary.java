package com.trademesh.portfolio;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Portfolio summary containing aggregate portfolio metrics.
 */
@Data
@Builder
public class PortfolioSummary {

    /**
     * Total number of open positions.
     */
    private int totalPositions;

    /**
     * Total unrealised P&L across all positions.
     */
    private double totalPnl;

    /**
     * Last time portfolio was updated.
     */
    private LocalDateTime lastUpdated;
}
