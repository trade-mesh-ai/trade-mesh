package com.trademeshai.portfolio;

import lombok.Builder;
import lombok.Data;

/**
 * Summary of a single open position in the portfolio.
 */
@Data
@Builder
public class PositionSummary {

    private String tradingSymbol;
    private String exchange;
    private String product;
    private int quantity;
    private double averagePrice;
    private double lastPrice;
    private double pnl;
    private double pnlPercent;
}
