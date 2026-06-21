package com.trademesh.portfolio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Placeholder portfolio service.
 * TODO: Sync positions from Zerodha, compute P&L, and persist to SQLite via R2DBC.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioService {

    /**
     * Returns all current open positions.
     */
    public Flux<PositionSummary> getOpenPositions() {
        log.info("Fetching open positions...");
        // TODO: query portfolio_positions table via R2DBC repository
        return Flux.empty();
    }

    /**
     * Returns the total unrealised P&L across all open positions.
     */
    public Mono<Double> getTotalPnl() {
        return getOpenPositions()
                .map(PositionSummary::getPnl)
                .reduce(0.0, Double::sum);
    }
}
