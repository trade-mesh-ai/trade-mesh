package com.trademesh.intelligence.vix;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * R2DBC repository for VixHistory entities.
 *
 * <p>Provides reactive CRUD operations and custom queries for VIX historical data.
 */
@Repository
public interface VixHistoryRepository extends ReactiveCrudRepository<VixHistory, Long> {

    /**
     * Finds the most recent VIX history record.
     *
     * @return Mono emitting the latest VixHistory record
     */
    @Query("SELECT * FROM vix_history ORDER BY recorded_at DESC LIMIT 1")
    Mono<VixHistory> findLatest();

    /**
     * Finds all VIX history records within a time range.
     *
     * @param startTime the start of the time range
     * @param endTime   the end of the time range
     * @return Flux emitting VixHistory records in the time range
     */
    @Query("SELECT * FROM vix_history WHERE recorded_at >= :startTime AND recorded_at <= :endTime ORDER BY recorded_at DESC")
    Flux<VixHistory> findByTimeRange(Instant startTime, Instant endTime);

    /**
     * Finds all VIX history records for a specific VIX level.
     *
     * @param vixLevel the VIX level to filter by
     * @return Flux emitting VixHistory records with the specified level
     */
    @Query("SELECT * FROM vix_history WHERE vix_level = :vixLevel ORDER BY recorded_at DESC")
    Flux<VixHistory> findByVixLevel(String vixLevel);

    /**
     * Counts the number of VIX history records.
     *
     * @return Mono emitting the count
     */
    @Query("SELECT COUNT(*) FROM vix_history")
    Mono<Long> countRecords();
}
