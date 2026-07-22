package com.trademesh.risk;

import com.trademesh.config.LotSizeService;
import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * Risk management component enforcing aggregate concurrent-open-position limits.
 * <p>
 * Responsibilities:
 * - Enforce the maximum concurrent open positions limit across all strategies
 * - Query database for current open position count
 * - Provide configuration access for position limit thresholds
 * <p>
 * All risk limits are loaded from application.yml configuration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RiskLimitsConfig.class)
public class RiskManager {

    private final RiskLimitsConfig riskLimitsConfig;
    private final PaperTradeRepository paperTradeRepository;
    private final LotSizeService lotSizeService;

    /**
     * Gets the maximum concurrent open positions limit from configuration.
     *
     * @return the max concurrent open positions limit
     */
    public int getMaxConcurrentOpenPositions() {
        return riskLimitsConfig.getMaxConcurrentOpenPositions();
    }

    /**
     * Checks if the current number of open positions is within the concurrent position limit.
     * <p>
     * Queries the database directly to count all OPEN trades across all strategies.
     * This is the source of truth for aggregate position tracking.
     *
     * @return Mono emitting true if open count is below limit, false if at or above limit
     */
    public Mono<Boolean> isWithinConcurrentPositionLimit() {
        return paperTradeRepository.findByStatus(TradeStatus.OPEN)
                .count()
                .map(openCount -> {
                    int maxAllowed = riskLimitsConfig.getMaxConcurrentOpenPositions();
                    boolean withinLimit = openCount < maxAllowed;
                    log.debug("Concurrent position check: openCount={} maxAllowed={} result={}",
                            openCount, maxAllowed, withinLimit);
                    return withinLimit;
                });
    }

    /**
     * Checks if the aggregate risk across all open positions is within the budget.
     * <p>
     * Queries the database for all OPEN trades and sums their maxLoss values.
     * Compares the sum against: accountCapital × maxAggregateRiskPercent / 100.
     * <p>
     * Trades with maxLoss == null are excluded from the sum and logged as WARN.
     * This is a separate, independent check from isWithinConcurrentPositionLimit().
     *
     * @return Mono emitting true if aggregate risk is strictly below budget, false if at or above budget
     */
    public Mono<Boolean> isWithinAggregateRiskBudget() {
        return paperTradeRepository.findByStatus(TradeStatus.OPEN)
                .collectList()
                .map(openTrades -> {
                    BigDecimal totalRisk = BigDecimal.ZERO;

                    for (var trade : openTrades) {
                        if (trade.getMaxLoss() != null) {
                            totalRisk = totalRisk.add(trade.getMaxLoss());
                        } else {
                            log.warn("Trade {} (strategy: {}) has maxLoss == null, excluding from aggregate risk check",
                                    trade.getId(), trade.getStrategyName());
                        }
                    }

                    double accountCapital = lotSizeService.getAccountCapital();
                    double maxAggregateRiskPercent = riskLimitsConfig.getMaxAggregateRiskPercent();
                    BigDecimal riskBudget = BigDecimal.valueOf(accountCapital)
                            .multiply(BigDecimal.valueOf(maxAggregateRiskPercent / 100.0));

                    boolean withinBudget = totalRisk.compareTo(riskBudget) < 0;
                    log.debug("Aggregate risk check: totalRisk={} riskBudget={} ({}% of {}) result={}",
                            totalRisk, riskBudget, maxAggregateRiskPercent, accountCapital, withinBudget);
                    return withinBudget;
                });
    }

    /**
     * Resets all internal state maps. Used for testing purposes.
     */
    public void resetForTesting() {
        log.debug("RiskManager state reset for testing");
    }
}
