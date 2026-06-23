package com.trademesh.common.resilience;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker pattern implementation for resilience.
 * <p>
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Too many failures, requests fail immediately
 * - HALF_OPEN: Testing if service recovered, limited requests allowed
 * <p>
 * Configuration:
 * - failureThreshold: Number of failures before opening circuit
 * - successThreshold: Number of successes in HALF_OPEN before closing
 * - timeout: Duration to wait before transitioning from OPEN to HALF_OPEN
 */
@Slf4j
public class CircuitBreaker {

    private final String name;
    private final int failureThreshold;
    private final int successThreshold;
    private final long timeoutMillis;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>();
    /**
     * Creates a circuit breaker with default configuration.
     *
     * @param name             circuit breaker name
     * @param failureThreshold failures before opening (default: 5)
     * @param successThreshold successes in HALF_OPEN before closing (default: 2)
     * @param timeoutSeconds   timeout before HALF_OPEN (default: 30)
     */
    public CircuitBreaker(String name, int failureThreshold, int successThreshold, long timeoutSeconds) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.timeoutMillis = timeoutSeconds * 1000;
    }

    /**
     * Executes a request through the circuit breaker.
     *
     * @param operation the operation to execute
     * @return Mono with the operation result or error if circuit is open
     */
    public <T> Mono<T> execute(Mono<T> operation) {
        return Mono.defer(() -> {
            State currentState = state.get();

            switch (currentState) {
                case CLOSED:
                    return executeInClosedState(operation);
                case OPEN:
                    return executeInOpenState();
                case HALF_OPEN:
                    return executeInHalfOpenState(operation);
                default:
                    return Mono.error(new IllegalStateException("Unknown circuit breaker state: " + currentState));
            }
        });
    }

    /**
     * Executes operation in CLOSED state (normal operation).
     */
    private <T> Mono<T> executeInClosedState(Mono<T> operation) {
        return operation
                .doOnNext(result -> {
                    // Success - reset failure count
                    failureCount.set(0);
                    log.debug("Circuit breaker [{}] success in CLOSED state", name);
                })
                .doOnError(error -> {
                    // Failure - increment counter
                    int failures = failureCount.incrementAndGet();
                    lastFailureTime.set(Instant.now());
                    log.warn("Circuit breaker [{}] failure {}/{} in CLOSED state", name, failures, failureThreshold);

                    // Open circuit if threshold exceeded
                    if (failures >= failureThreshold) {
                        state.set(State.OPEN);
                        log.error("Circuit breaker [{}] opened after {} failures", name, failures);
                    }
                });
    }

    /**
     * Executes operation in OPEN state (circuit is open, fail fast).
     */
    private <T> Mono<T> executeInOpenState() {
        Instant lastFailure = lastFailureTime.get();
        if (lastFailure != null && Instant.now().isAfter(lastFailure.plusMillis(timeoutMillis))) {
            // Timeout expired, transition to HALF_OPEN
            state.set(State.HALF_OPEN);
            successCount.set(0);
            log.info("Circuit breaker [{}] transitioned to HALF_OPEN", name);
            return Mono.error(new CircuitBreakerOpenException(name + " is OPEN, transitioning to HALF_OPEN"));
        }

        return Mono.error(new CircuitBreakerOpenException(name + " is OPEN"));
    }

    /**
     * Executes operation in HALF_OPEN state (testing recovery).
     */
    private <T> Mono<T> executeInHalfOpenState(Mono<T> operation) {
        return operation
                .doOnNext(result -> {
                    // Success in HALF_OPEN
                    int successes = successCount.incrementAndGet();
                    log.debug("Circuit breaker [{}] success {}/{} in HALF_OPEN state", name, successes, successThreshold);

                    // Close circuit if threshold reached
                    if (successes >= successThreshold) {
                        state.set(State.CLOSED);
                        failureCount.set(0);
                        log.info("Circuit breaker [{}] closed after {} successes", name, successes);
                    }
                })
                .doOnError(error -> {
                    // Failure in HALF_OPEN - reopen circuit
                    state.set(State.OPEN);
                    lastFailureTime.set(Instant.now());
                    failureCount.set(0);
                    successCount.set(0);
                    log.warn("Circuit breaker [{}] reopened due to failure in HALF_OPEN state", name);
                });
    }

    /**
     * Gets the current state of the circuit breaker.
     */
    public State getState() {
        return state.get();
    }

    /**
     * Gets the current failure count.
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Gets the current success count.
     */
    public int getSuccessCount() {
        return successCount.get();
    }

    /**
     * Resets the circuit breaker to CLOSED state.
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(null);
        log.info("Circuit breaker [{}] reset to CLOSED state", name);
    }

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    /**
     * Exception thrown when circuit breaker is open.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
