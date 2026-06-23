package com.trademesh.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Successful response wrapper for API responses.
 * <p>
 * Provides structured response information including status, message, and data payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuccessResponse<T> {

    /**
     * HTTP status code.
     */
    private int status;

    /**
     * Success message.
     */
    private String message;

    /**
     * Response data payload.
     */
    private T data;

    /**
     * Timestamp when the response was generated.
     */
    private LocalDateTime timestamp;

    /**
     * Request ID for tracing (useful for debugging).
     */
    private String requestId;
}
