package com.trademesh.common.handler;

import com.trademesh.common.dto.ErrorDetail;
import com.trademesh.common.dto.ErrorResponse;
import com.trademesh.common.exception.*;
import com.trademesh.common.resilience.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.UUID;

/**
 * Global exception handler for all TradeMeshException subclasses.
 * <p>
 * Handles:
 * - TradeMeshException and all subclasses
 * - Spring validation exceptions
 * - Generic exceptions
 * <p>
 * Returns structured error responses with error codes, messages, and context.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles ApiException (Kite Connect API errors).
     */
    @ExceptionHandler(ApiException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleApiException(
            ApiException ex,
            ServerWebExchange exchange) {
        log.error("API Exception: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);

        HttpStatus status = mapErrorCodeToStatus(ex.getErrorCode());
        ErrorResponse response = buildErrorResponse(
                status,
                ex.getMessage(),
                ex.getErrorCode(),
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles DatabaseException (R2DBC/PostgreSQL errors).
     */
    @ExceptionHandler(DatabaseException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleDatabaseException(
            DatabaseException ex,
            ServerWebExchange exchange) {
        log.error("Database Exception: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse response = buildErrorResponse(
                status,
                "Database operation failed: " + ex.getMessage(),
                ex.getErrorCode(),
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles CacheException (Redis errors).
     */
    @ExceptionHandler(CacheException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleCacheException(
            CacheException ex,
            ServerWebExchange exchange) {
        log.error("Cache Exception: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);

        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        ErrorResponse response = buildErrorResponse(
                status,
                "Cache operation failed: " + ex.getMessage(),
                ex.getErrorCode(),
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles RiskException (risk validation failures).
     */
    @ExceptionHandler(RiskException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleRiskException(
            RiskException ex,
            ServerWebExchange exchange) {
        log.warn("Risk Exception: {} - {}", ex.getErrorCode(), ex.getMessage());

        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse response = buildErrorResponse(
                status,
                "Risk validation failed: " + ex.getMessage(),
                ex.getErrorCode(),
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles ExecutionException (order execution failures).
     */
    @ExceptionHandler(ExecutionException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleExecutionException(
            ExecutionException ex,
            ServerWebExchange exchange) {
        log.error("Execution Exception: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse response = buildErrorResponse(
                status,
                "Order execution failed: " + ex.getMessage(),
                ex.getErrorCode(),
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles CircuitBreakerOpenException (circuit breaker is open).
     */
    @ExceptionHandler(CircuitBreaker.CircuitBreakerOpenException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleCircuitBreakerOpenException(
            CircuitBreaker.CircuitBreakerOpenException ex,
            ServerWebExchange exchange) {
        log.warn("Circuit Breaker Open: {}", ex.getMessage());

        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        ErrorResponse response = buildErrorResponse(
                status,
                "Service temporarily unavailable: " + ex.getMessage(),
                "CIRCUIT_BREAKER_OPEN",
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles generic TradeMeshException.
     */
    @ExceptionHandler(TradeMeshException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleTradeMeshException(
            TradeMeshException ex,
            ServerWebExchange exchange) {
        log.error("TradeMesh Exception: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse response = buildErrorResponse(
                status,
                ex.getMessage(),
                ex.getErrorCode(),
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles IllegalArgumentException (validation errors).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleIllegalArgumentException(
            IllegalArgumentException ex,
            ServerWebExchange exchange) {
        log.warn("Illegal Argument: {}", ex.getMessage());

        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse response = buildErrorResponse(
                status,
                "Invalid argument: " + ex.getMessage(),
                "INVALID_ARGUMENT",
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Handles generic exceptions.
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleGenericException(
            Exception ex,
            ServerWebExchange exchange) {
        log.error("Unexpected Exception: {}", ex.getMessage(), ex);

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse response = buildErrorResponse(
                status,
                "An unexpected error occurred: " + ex.getMessage(),
                "INTERNAL_SERVER_ERROR",
                exchange.getRequest().getPath().value()
        );

        return Mono.just(ResponseEntity.status(status).body(response));
    }

    /**
     * Builds an ErrorResponse with error details.
     */
    private ErrorResponse buildErrorResponse(
            HttpStatus status,
            String message,
            String errorCode,
            String path) {
        ErrorDetail errorDetail = ErrorDetail.builder()
                .code(errorCode)
                .message(message)
                .context(Collections.emptyMap())
                .build();

        return ErrorResponse.builder()
                .status(status.value())
                .message(message)
                .path(path)
                .timestamp(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .errors(Collections.singletonList(errorDetail))
                .requestId(UUID.randomUUID().toString())
                .build();
    }

    /**
     * Maps error codes to HTTP status codes.
     */
    private HttpStatus mapErrorCodeToStatus(String errorCode) {
        return switch (errorCode) {
            case "API_TIMEOUT", "DB_TIMEOUT", "CACHE_TIMEOUT", "EXECUTION_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "API_RATE_LIMIT" -> HttpStatus.TOO_MANY_REQUESTS;
            case "CIRCUIT_BREAKER_OPEN" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "CACHE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "RISK_LIMIT_EXCEEDED", "POSITION_SIZE_EXCEEDED", "DAILY_LOSS_LIMIT_EXCEEDED" -> HttpStatus.BAD_REQUEST;
            case "ORDER_PLACEMENT_FAILED", "ORDER_CANCELLED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
