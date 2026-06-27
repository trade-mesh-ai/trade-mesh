package com.trademesh.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Main error response wrapper for API error responses.
 * <p>
 * Provides structured error information including timestamp, status, and error details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /**
     * HTTP status code.
     */
    private int status;

    /**
     * Error message.
     */
    private String message;

    /**
     * Request path that caused the error.
     */
    private String path;

    /**
     * Timestamp when the error occurred.
     */
    private LocalDateTime timestamp;

    /**
     * List of error details (can contain multiple errors).
     */
    private List<ErrorDetail> errors;

    /**
     * Request ID for tracing (useful for debugging).
     */
    private String requestId;
}
