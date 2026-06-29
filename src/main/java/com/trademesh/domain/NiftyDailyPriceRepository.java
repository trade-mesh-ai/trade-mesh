package com.trademesh.domain;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reactive R2DBC repository for {@link NiftyDailyPrice} entities.
 * <p>
 * Provides database access for NIFTY50 historical daily OHLC data with custom
 * query methods for filtering by date and date ranges.
 */
@Repository
public interface NiftyDailyPriceRepository extends ReactiveCrudRepository<NiftyDailyPrice, UUID> {

    /**
     * Finds a daily price record by trade date.
     *
     * @param tradeDate the date to filter by
     * @return Mono emitting the daily price if found
     */
    Mono<NiftyDailyPrice> findByTradeDate(LocalDate tradeDate);

    /**
     * Finds all daily price records within a date range, ordered by date ascending.
     *
     * @param from the start date (inclusive)
     * @param to   the end date (inclusive)
     * @return Flux of daily prices ordered by trade date ascending
     */
    Flux<NiftyDailyPrice> findByTradeDateBetweenOrderByTradeDateAsc(LocalDate from, LocalDate to);

    /**
     * Finds the most recent daily price record (latest trade date).
     *
     * @return Mono emitting the most recent daily price
     */
    Mono<NiftyDailyPrice> findTopByOrderByTradeDateDesc();

    /**
     * Checks if a daily price record exists for the given trade date.
     *
     * @param tradeDate the date to check
     * @return Mono emitting true if a record exists, false otherwise
     */
    Mono<Boolean> existsByTradeDate(LocalDate tradeDate);
}
