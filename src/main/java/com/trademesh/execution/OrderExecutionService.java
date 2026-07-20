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
 * Legacy execution service targeting an obsolete signal model.
 * <p>
 * This class was designed against com.trademesh.intelligence.TradingSignal — a
 * single-instrument, single-direction signal shape. This model is incompatible
 * with the multi-leg com.trademesh.strategy.TradeSignal used by all current
 * live strategies (Iron Condor, Bull Put Spread, Bear Call Spread, Expiry Day).
 * <p>
 * This class has zero callers anywhere in the current codebase and is not part
 * of the active execution path. It is retained as a historical reference only,
 * pending deletion when live-trading execution architecture is designed as a
 * separate, dedicated review.
 * <p>
 * Any future live-order-placement work should be designed fresh against
 * TradeSignal and real broker/execution constraints, not by extending this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final RiskManager riskManager;
    private final OrderService orderService;

    /**
     * Executes a trading signal (legacy method operating on obsolete model).
     * <p>
     * This method operates on com.trademesh.intelligence.TradingSignal, which is
     * incompatible with the multi-leg TradeSignal model used by all current
     * strategies. This method is not invoked by any current code path and is
     * retained for historical reference only.
     *
     * @param signal   the trading signal to act on (obsolete TradingSignal model)
     * @param quantity desired quantity (pre-calculated by risk module)
     * @return Mono emitting order ID or empty if validation fails
     */
    public Mono<String> execute(TradingSignal signal, int quantity) {
        log.info("Executing signal for {}: direction={} qty={}",
                signal.getTradingSymbol(), signal.getDirection(), quantity);

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
                .doOnError(e -> log.error("Order placement failed for {}: {}",
                        signal.getTradingSymbol(), e.getMessage(), e));
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
