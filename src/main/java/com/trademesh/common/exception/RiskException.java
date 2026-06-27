package com.trademesh.common.exception;

/**
 * Exception for risk validation failures.
 * <p>
 * Thrown when a trade violates risk limits such as position size, daily loss limit, or exposure limits.
 */
public class RiskException extends TradeMeshException {

    public RiskException(String message) {
        super("RISK_LIMIT_EXCEEDED", message);
    }

    public RiskException(String message, Throwable cause) {
        super("RISK_LIMIT_EXCEEDED", message, cause);
    }

    public RiskException(String errorCode, String message) {
        super(errorCode, message);
    }

    public RiskException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
