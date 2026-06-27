package com.trademesh.intelligence.maxpain;

import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;

/**
 * R2DBC repository for MaxPainResult entities.
 *
 * <p>Provides reactive database operations for max pain history records.
 */
@Repository
public interface MaxPainHistoryRepository extends ReactiveCrudRepository<MaxPainResult, Long> {

    /**
     * Finds the latest max pain calculation for a given symbol.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return Mono emitting the latest MaxPainResult, or empty if not found
     */
    Mono<MaxPainResult> findFirstBySymbolOrderByCalculatedAtDesc(@Param("symbol") String symbol);

    /**
     * Finds all max pain records for a given symbol and expiry date.
     *
     * @param symbol     trading symbol
     * @param expiryDate option expiry date
     * @return Flux emitting all matching MaxPainResult records
     */
    Flux<MaxPainResult> findBySymbolAndExpiryDateOrderByCalculatedAtDesc(
            @Param("symbol") String symbol,
            @Param("expiryDate") LocalDate expiryDate);

    /**
     * Finds all max pain records for a given symbol within a time range.
     *
     * @param symbol    trading symbol
     * @param startTime start of time range
     * @param endTime   end of time range
     * @return Flux emitting all matching MaxPainResult records
     */
    Flux<MaxPainResult> findBySymbolAndCalculatedAtBetweenOrderByCalculatedAtDesc(
            @Param("symbol") String symbol,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    /**
     * Counts the total number of max pain records for a given symbol.
     *
     * @param symbol trading symbol
     * @return Mono emitting the count
     */
    Mono<Long> countBySymbol(@Param("symbol") String symbol);
}
