package com.trademesh.common.exception;

/**
 * Exception for order execution failures.
 * <p>
 * Thrown when order placement, cancellation, or modification fails.
 */
public class ExecutionException extends TradeMeshException {

    public ExecutionException(String message) {
        super("ORDER_PLACEMENT_FAILED", message);
    }

    public ExecutionException(String message, Throwable cause) {
        super("ORDER_PLACEMENT_FAILED", message, cause);
    }

    public ExecutionException(String errorCode, String message) {
        super(errorCode, message);
    }

    public ExecutionException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
