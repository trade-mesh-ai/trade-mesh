package com.trademesh.common.exception;

/**
 * Exception for Redis cache errors.
 * <p>
 * Thrown when cache operations fail due to connection issues, timeouts, or cache unavailability.
 */
public class CacheException extends TradeMeshException {

    public CacheException(String message) {
        super("CACHE_ERROR", message);
    }

    public CacheException(String message, Throwable cause) {
        super("CACHE_ERROR", message, cause);
    }

    public CacheException(String errorCode, String message) {
        super(errorCode, message);
    }

    public CacheException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
