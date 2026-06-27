package com.trademesh.alert;

/**
 * Types of alerts that can be triggered by the system.
 */
public enum AlertType {
    PRICE_TARGET_HIT,
    STOP_LOSS_HIT,
    SIGNAL_GENERATED,
    ORDER_PLACED,
    ORDER_FILLED,
    ORDER_REJECTED,
    RISK_LIMIT_BREACHED,
    SYSTEM_ERROR,
    PAPER_TRADE
}
