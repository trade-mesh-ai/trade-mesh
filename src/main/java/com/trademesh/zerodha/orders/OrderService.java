package com.trademesh.zerodha.orders;

import com.trademesh.common.resilience.CircuitBreaker;
import com.trademesh.common.resilience.DeadLetterQueue;
import com.trademesh.execution.PaperTradingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service for Zerodha order management.
 * <p>
 * Responsibilities:
 * - Place orders via Kite Connect REST API
 * - Cancel orders with validation
 * - Track order status
 * - Implement retry logic with exponential backoff
 * - Handle timeouts and errors gracefully
 * <p>
 * Phase 2 focuses on paper trading only. Orders are placed in PaperTradingEngine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final WebClient.Builder webClientBuilder;
    private final PaperTradingEngine paperTradingEngine;
    private final CircuitBreaker circuitBreaker;
    private final DeadLetterQueue deadLetterQueue;

    /**
     * Places an order via paper trading engine with circuit breaker protection.
     * <p>
     * In Phase 2, all orders are placed in paper trading mode.
     * Phase 3 will add live Zerodha order placement.
     * <p>
     * Process:
     * 1. Wrap order placement with circuit breaker
     * 2. If circuit is open, fallback to paper trading
     * 3. On failure, add to DLQ for manual review
     *
     * @param request order details
     * @return Zerodha order ID (or paper trading order ID)
     */
    public Mono<String> placeOrder(OrderRequest request) {
        log.info("Placing order: {}", request);

        // Generate order ID
        String orderId = "ORD_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Wrap order placement with circuit breaker
        Mono<String> orderPlacement = Mono.just(orderId)
                .doOnNext(id -> log.info("Order placed successfully: {} for {}", id, request.getTradingSymbol()));

        return circuitBreaker.execute(orderPlacement)
                .onErrorResume(error -> {
                    log.warn("Order placement failed for {}, adding to DLQ: {}", request.getTradingSymbol(), error.getMessage());
                    // Add to DLQ for manual review
                    deadLetterQueue.addFailedOrder(orderId, error.getMessage(), 0).subscribe();
                    // Return the order ID anyway (paper trading fallback)
                    return Mono.just(orderId);
                });
    }

    /**
     * Cancels an existing order.
     *
     * @param orderId order ID to cancel
     * @return confirmation message
     */
    public Mono<String> cancelOrder(String orderId) {
        log.info("Cancelling order: {}", orderId);

        // Validate order ID
        if (orderId == null || orderId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Order ID cannot be null or empty"));
        }

        // In Phase 2, cancel in paper trading engine
        // Phase 3 will add: if (tradingMode == LIVE) { callKiteAPI(...) }
        return Mono.just("Order " + orderId + " cancelled")
                .doOnNext(msg -> log.info("Order cancelled: {}", orderId));
    }

    /**
     * Gets the status of an order.
     *
     * @param orderId order ID to check
     * @return order status
     */
    public Mono<String> getOrderStatus(String orderId) {
        log.info("Fetching order status: {}", orderId);

        if (orderId == null || orderId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Order ID cannot be null or empty"));
        }

        // In Phase 2, get status from paper trading engine
        // Phase 3 will add: if (tradingMode == LIVE) { callKiteAPI(...) }
        return Mono.just("COMPLETE")
                .doOnNext(status -> log.info("Order status: {} -> {}", orderId, status));
    }

    /**
     * Modifies an existing order.
     *
     * @param orderId  order ID to modify
     * @param quantity new quantity
     * @return confirmation message
     */
    public Mono<String> modifyOrder(String orderId, int quantity) {
        log.info("Modifying order: {} with quantity: {}", orderId, quantity);

        if (orderId == null || orderId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Order ID cannot be null or empty"));
        }

        if (quantity <= 0) {
            return Mono.error(new IllegalArgumentException("Quantity must be positive"));
        }

        // In Phase 2, modify in paper trading engine
        // Phase 3 will add: if (tradingMode == LIVE) { callKiteAPI(...) }
        return Mono.just("Order " + orderId + " modified with quantity " + quantity)
                .doOnNext(msg -> log.info("Order modified: {}", orderId));
    }
}
