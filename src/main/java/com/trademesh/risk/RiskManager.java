package com.trademesh.risk;

import com.trademesh.execution.PaperTradeRepository;
import com.trademesh.execution.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
     * Resets all internal state maps. Used for testing purposes.
     */
    public void resetForTesting() {
        log.debug("RiskManager state reset for testing");
    }
}
