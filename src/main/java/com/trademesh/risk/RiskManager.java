package com.trademesh.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Placeholder risk management component.
 * TODO: Implement position sizing, max drawdown checks, exposure limits, and VaR calculations.
 */
@Slf4j
@Component
public class RiskManager {

    /**
     * Validates whether a proposed trade passes all risk checks.
     *
     * @param tradingSymbol instrument to trade
     * @param quantity      proposed quantity
     * @return {@code true} if the trade is within risk limits
     */
    public Mono<Boolean> isWithinRiskLimits(String tradingSymbol, int quantity) {
        log.info("Risk check for {} qty={}", tradingSymbol, quantity);
        // TODO: check max position size, daily loss limit, sector exposure, etc.
        return Mono.just(true);
    }

    /**
     * Calculates the recommended position size based on account equity and risk %.
     *
     * @param accountEquity total account equity
     * @param riskPercent   percentage of equity to risk per trade (e.g. 1.0 = 1%)
     * @param stopLossPoints stop-loss distance in price points
     * @return recommended quantity
     */
    public Mono<Integer> calculatePositionSize(double accountEquity, double riskPercent, double stopLossPoints) {
        double riskAmount = accountEquity * (riskPercent / 100.0);
        int qty = (stopLossPoints > 0) ? (int) (riskAmount / stopLossPoints) : 0;
        log.info("Position size: equity={} risk%={} sl={} -> qty={}", accountEquity, riskPercent, stopLossPoints, qty);
        return Mono.just(qty);
    }
}
