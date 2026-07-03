package com.trademesh.execution;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link PaperTrade} entities.
 * <p>
 * Provides database access for paper trading records with custom query methods
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
     * <p>
     * Convenience method equivalent to findByStatus(TradeStatus.OPEN).
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
     * Finds all paper trades for a specific strategy with a given status.
     *
     * @param strategyName the strategy name to filter by
     * @param status       the trade status to filter by (OPEN, CLOSED, CANCELLED)
     * @return Flux of matching paper trades
     */
    Flux<PaperTrade> findByStrategyNameAndStatus(String strategyName, TradeStatus status);

    /**
     * Finds all paper trades with a specific status and trade date.
     *
     * @param status    the trade status to filter by (OPEN, CLOSED, CANCELLED)
     * @param tradeDate the date to filter by
     * @return Flux of matching paper trades
     */
    Flux<PaperTrade> findByStatusAndTradeDate(TradeStatus status, LocalDate tradeDate);

    /**
     * Finds a single paper trade by ID.
     *
     * @param id the UUID of the paper trade
     * @return Mono emitting the paper trade if found
     */
    Mono<PaperTrade> findById(UUID id);

    /**
     * Checks if an open trade exists for a given strategy on a specific date.
     *
     * @param strategyName the strategy name to filter by
     * @param tradeDate    the trade date to filter by
     * @param status       the trade status to filter by
     * @return Mono emitting true if a trade exists, false otherwise
     */
    Mono<Boolean> existsByStrategyNameAndTradeDateAndStatus(String strategyName, LocalDate tradeDate, TradeStatus status);

    /**
     * Checks if an open trade exists for a given strategy regardless of date.
     * <p>
     * This method is used to detect duplicate entries across multiple days,
     * ensuring that once positions can survive overnight (future stages),
     * we don't accidentally open a second position for the same strategy
     * when one is already open from a prior day.
     *
     * @param strategyName the strategy name to filter by
     * @param status       the trade status to filter by
     * @return Mono emitting true if a trade exists, false otherwise
     */
    Mono<Boolean> existsByStrategyNameAndStatus(String strategyName, TradeStatus status);
}
