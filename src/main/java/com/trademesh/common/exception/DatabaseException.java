package com.trademesh.common.exception;

/**
 * Exception for R2DBC/PostgreSQL database errors.
 * <p>
 * Thrown when database operations fail due to connection issues, query errors, or timeouts.
 */
public class DatabaseException extends TradeMeshException {

    public DatabaseException(String message) {
        super("DB_CONNECTION_ERROR", message);
    }

    public DatabaseException(String message, Throwable cause) {
        super("DB_CONNECTION_ERROR", message, cause);
    }

    public DatabaseException(String errorCode, String message) {
        super(errorCode, message);
    }

    public DatabaseException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
