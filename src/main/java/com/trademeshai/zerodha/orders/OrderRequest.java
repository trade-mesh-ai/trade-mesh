package com.trademeshai.zerodha.orders;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request payload for placing an order via Zerodha Kite Connect.
 */
@Data
@Builder
public class OrderRequest {

    private String tradingSymbol;
    private String exchange;           // NSE, BSE, NFO, etc.
    private String transactionType;    // BUY or SELL
    private String orderType;          // MARKET, LIMIT, SL, SL-M
    private String product;            // CNC, MIS, NRML
    private int quantity;
    private BigDecimal price;          // for LIMIT orders
    private BigDecimal triggerPrice;   // for SL / SL-M orders
    private String validity;           // DAY, IOC
    private String tag;                // optional strategy tag
}
