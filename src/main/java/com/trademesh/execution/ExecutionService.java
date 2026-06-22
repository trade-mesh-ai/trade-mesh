package com.trademesh.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.trademesh.intelligence.TradingSignal;
import com.trademesh.risk.RiskManager;
import com.trademesh.zerodha.orders.OrderRequest;
import com.trademesh.zerodha.orders.OrderService;

import reactor.core.publisher.Mono;

/**
 * Execution service that bridges signals → risk checks → order placement.
 * TODO: Add smart order routing, partial fills handling, and retry logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final RiskManager riskManager;
    private final OrderService orderService;

    /**
     * Executes a trading signal after passing risk validation.
     *
     * @param signal   the trading signal to act on
     * @param quantity desired quantity (pre-calculated by risk module)
     * @return Zerodha order ID or empty if risk check fails
     */
    public Mono<String> execute(TradingSignal signal, int quantity) {
        return riskManager.isWithinRiskLimits(signal.getTradingSymbol(), quantity)
                .flatMap(allowed -> {
                    if (!allowed) {
                        log.warn("Risk check failed for signal: {}", signal);
                        return Mono.empty();
                    }
                    String txType = switch (signal.getDirection()) {
                        case LONG -> "BUY";
                        case SHORT -> "SELL";
                        default -> null;
                    };
                    if (txType == null) {
                        log.info("Neutral signal – no order placed for {}", signal.getTradingSymbol());
                        return Mono.empty();
                    }
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
                    return orderService.placeOrder(order);
                });
    }
}
