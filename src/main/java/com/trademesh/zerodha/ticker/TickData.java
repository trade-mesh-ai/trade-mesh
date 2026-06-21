package com.trademesh.zerodha.ticker;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a single market tick received from Zerodha Kite Ticker.
 */
@Data
@Builder
public class TickData {

    private long instrumentToken;
    private String tradingSymbol;
    private BigDecimal lastPrice;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private long volume;
    private BigDecimal buyQuantity;
    private BigDecimal sellQuantity;
    private Instant timestamp;
}
