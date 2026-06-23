package com.trademesh.execution;

import com.trademesh.intelligence.TradingSignal;
import com.trademesh.risk.RiskManager;
import com.trademesh.zerodha.orders.OrderRequest;
import com.trademesh.zerodha.orders.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Execution service that bridges signals → risk checks → order placement.
 * <p>
 * Responsibilities:
 * - Validate trading signals against risk limits
 * - Route orders to appropriate execution engine (paper trading in Phase 2)
 * - Handle partial fills and order tracking
 * - Implement retry logic with exponential backoff
 * - Add timeout handling for order execution
 * <p>
 * Phase 2 focuses on paper trading only. Phase 3 will add live order execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final RiskManager riskManager;
    private final OrderService orderService;

    /**
     * Executes a trading signal after passing risk validation.
     * <p>
     * Process:
     * 1. Validate signal against risk limits
     * 2. Build order request from signal
     * 3. Place order via OrderService
     * 4. Record trade in RiskManager for tracking
     * 5. Return order ID or empty if validation fails
     *
     * @param signal   the trading signal to act on
     * @param quantity desired quantity (pre-calculated by risk module)
     * @return Mono emitting order ID or empty if risk check fails
     */
    public Mono<String> execute(TradingSignal signal, int quantity) {
        log.info("Executing signal for {}: direction={} qty={}",
                signal.getTradingSymbol(), signal.getDirection(), quantity);

        return riskManager.isWithinRiskLimits(signal.getTradingSymbol(), quantity)
                .flatMap(allowed -> {
                    if (!allowed) {
                        log.warn("Risk check failed for signal: {}", signal);
                        return Mono.empty();
                    }

                    // Determine transaction type from signal direction
                    String txType = switch (signal.getDirection()) {
                        case LONG -> "BUY";
                        case SHORT -> "SELL";
                        default -> null;
                    };

                    if (txType == null) {
                        log.info("Neutral signal – no order placed for {}", signal.getTradingSymbol());
                        return Mono.empty();
                    }

                    // Build order request
                    OrderRequest order = OrderRequest.builder()
                            .tradingSymbol(signal.getTradingSymbol())
                            .exchange("NSE")
                            .transactionType(txType)
                            .orderType("MARKET")
                            .product("MIS")
                            .quantity(quantity)
                            .validity("DAY")
                            .tag(signal.getStrategyName())
                            .build();

                    // Place order with retry logic
                    return orderService.placeOrder(order)
                            .timeout(Duration.ofSeconds(15))
                            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                                    .maxBackoff(Duration.ofSeconds(4))
                                    .doBeforeRetry(signal2 -> log.warn("Retrying order placement for {}, attempt {}",
                                            signal.getTradingSymbol(), signal2.totalRetries() + 1)))
                            .doOnNext(orderId -> {
                                log.info("Order placed successfully: {} for {}", orderId, signal.getTradingSymbol());
                                // Record trade for risk tracking
                                riskManager.recordTrade(signal.getTradingSymbol(), quantity, 0.0).subscribe();
                            })
                            .doOnError(e -> log.error("Order placement failed for {}: {}",
                                    signal.getTradingSymbol(), e.getMessage(), e));
                });
    }

    /**
     * Tracks the status of an order.
     *
     * @param orderId order ID to track
     * @return Mono emitting order status
     */
    public Mono<String> trackOrderStatus(String orderId) {
        log.info("Tracking order status: {}", orderId);

        return orderService.getOrderStatus(orderId)
                .timeout(Duration.ofSeconds(10))
                .doOnNext(status -> log.info("Order {} status: {}", orderId, status))
                .doOnError(e -> log.error("Error tracking order {}: {}", orderId, e.getMessage(), e));
    }

    /**
     * Handles partial fill of an order.
     *
     * @param orderId        order ID
     * @param filledQuantity quantity filled
     * @return Mono completing when partial fill is handled
     */
    public Mono<Void> handlePartialFill(String orderId, int filledQuantity) {
        log.info("Handling partial fill: orderId={} filledQty={}", orderId, filledQuantity);

        return Mono.fromRunnable(() -> {
            // In Phase 2, just log the partial fill
            // Phase 3 will add: update position tracking, adjust risk limits, etc.
            log.info("Partial fill recorded: {} qty={}", orderId, filledQuantity);
        });
    }
}
