package com.trademesh.common.exception;

/**
 * Base exception for all trade-mesh application errors.
 * <p>
 * All specific exceptions should extend this class for consistent error handling.
 */
public class TradeMeshException extends RuntimeException {

    private final String errorCode;

    public TradeMeshException(String message) {
        super(message);
        this.errorCode = "UNKNOWN_ERROR";
    }

    public TradeMeshException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TradeMeshException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
