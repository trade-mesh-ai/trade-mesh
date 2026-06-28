package com.trademesh.intelligence.iv;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link IvHistory} entities.
 * Provides database access for daily ATM IV data with custom query methods
 * for filtering by symbol, date, and date ranges.
 */
@Repository
public interface IvHistoryRepository extends ReactiveCrudRepository<IvHistory, UUID> {

    /**
     * Finds an IV history record by symbol and trade date.
     *
     * @param symbol the trading symbol (e.g., "NIFTY50")
     * @param date   the trade date to filter by
     * @return Mono emitting the IV history if found
     */
    Mono<IvHistory> findBySymbolAndTradeDate(String symbol, LocalDate date);

    /**
     * Finds all IV history records for a symbol, ordered by trade date descending.
     *
     * @param symbol the trading symbol
     * @return Flux of IV history records ordered by trade date descending
     */
    Flux<IvHistory> findBySymbolOrderByTradeDateDesc(String symbol);

    /**
     * Finds all IV history records for a symbol within a date range, ordered by trade date ascending.
     *
     * @param symbol the trading symbol
     * @param from   the start date (inclusive)
     * @param to     the end date (inclusive)
     * @return Flux of IV history records ordered by trade date ascending
     */
    Flux<IvHistory> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(String symbol, LocalDate from, LocalDate to);

    /**
     * Checks if an IV history record exists for the given symbol and trade date.
     *
     * @param symbol the trading symbol
     * @param date   the trade date to check
     * @return Mono emitting true if a record exists, false otherwise
     */
    Mono<Boolean> existsBySymbolAndTradeDate(String symbol, LocalDate date);

    /**
     * Finds the most recent IV history record for a symbol (latest trade date).
     *
     * @param symbol the trading symbol
     * @return Mono emitting the most recent IV history record
     */
    Mono<IvHistory> findTopBySymbolOrderByTradeDateDesc(String symbol);
}
