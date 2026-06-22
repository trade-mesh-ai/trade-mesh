package com.trademesh.execution;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link PaperTrade} entities.
 *
 * <p>Provides database access for paper trading records with custom query methods
 * for filtering by status, date, symbol, and other criteria.
 */
@Repository
public interface PaperTradeRepository extends ReactiveCrudRepository<PaperTrade, UUID> {

    /**
     * Finds all paper trades with a specific status.
     *
     * @param status the trade status to filter by (OPEN, CLOSED, CANCELLED)
     * @return Flux of matching paper trades
     */
    Flux<PaperTrade> findByStatus(TradeStatus status);

    /**
     * Finds all paper trades for a specific date and symbol.
     *
     * @param tradeDate the date to filter by
     * @param symbol    the trading symbol to filter by
     * @return Flux of matching paper trades
     */
    Flux<PaperTrade> findByTradeDateAndSymbol(LocalDate tradeDate, String symbol);

    /**
     * Finds all currently open paper trades.
     *
     * <p>Convenience method equivalent to findByStatus(TradeStatus.OPEN).
     *
     * @return Flux of all open paper trades
     */
    default Flux<PaperTrade> findAllOpenTrades() {
        return findByStatus(TradeStatus.OPEN);
    }

    /**
     * Finds all closed paper trades.
     *
     * @return Flux of all closed paper trades
     */
    default Flux<PaperTrade> findAllClosedTrades() {
        return findByStatus(TradeStatus.CLOSED);
    }

    /**
     * Finds all paper trades for a specific symbol.
     *
     * @param symbol the trading symbol to filter by
     * @return Flux of matching paper trades
     */
    Flux<PaperTrade> findBySymbol(String symbol);

    /**
     * Finds all paper trades for a specific strategy.
     *
     * @param strategyName the strategy name to filter by
     * @return Flux of matching paper trades
     */
    Flux<PaperTrade> findByStrategyName(String strategyName);

    /**
     * Finds a single paper trade by ID.
     *
     * @param id the UUID of the paper trade
     * @return Mono emitting the paper trade if found
     */
    Mono<PaperTrade> findById(UUID id);
}
