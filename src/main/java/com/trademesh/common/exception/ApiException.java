package com.trademesh.common.exception;

/**
 * Exception for Kite Connect API errors.
 * <p>
 * Thrown when API calls fail due to network issues, timeouts, or API errors.
 */
public class ApiException extends TradeMeshException {

    public ApiException(String message) {
        super("API_ERROR", message);
    }

    public ApiException(String message, Throwable cause) {
        super("API_ERROR", message, cause);
    }

    public ApiException(String errorCode, String message) {
        super(errorCode, message);
    }

    public ApiException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
