package com.trademesh.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Error detail containing error code, message, and context information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorDetail {

    /**
     * Error code (e.g., API_ERROR, DB_CONNECTION_ERROR, RISK_LIMIT_EXCEEDED).
     */
    private String code;

    /**
     * Human-readable error message.
     */
    private String message;

    /**
     * Additional context information about the error.
     */
    private Map<String, Object> context;

    /**
     * Stack trace (only in development/debug mode).
     */
    private String stackTrace;
}
