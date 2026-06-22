package com.trademesh.intelligence;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * R2DBC repository for VixAlert entities.
 *
 * <p>Provides reactive CRUD operations and custom queries for VIX alert events.
 */
@Repository
public interface VixAlertRepository extends ReactiveCrudRepository<VixAlert, Long> {

    /**
     * Finds the most recent VIX alert.
     *
     * @return Mono emitting the latest VixAlert record
     */
    @Query("SELECT * FROM vix_alerts ORDER BY triggered_at DESC LIMIT 1")
    Mono<VixAlert> findLatest();

    /**
     * Finds all VIX alerts within a time range.
     *
     * @param startTime the start of the time range
     * @param endTime   the end of the time range
     * @return Flux emitting VixAlert records in the time range
     */
    @Query("SELECT * FROM vix_alerts WHERE triggered_at >= :startTime AND triggered_at <= :endTime ORDER BY triggered_at DESC")
    Flux<VixAlert> findByTimeRange(Instant startTime, Instant endTime);

    /**
     * Finds all VIX alerts that have been sent to Telegram.
     *
     * @return Flux emitting VixAlert records with telegram_sent = true
     */
    @Query("SELECT * FROM vix_alerts WHERE telegram_sent = true ORDER BY triggered_at DESC")
    Flux<VixAlert> findTelegramSent();

    /**
     * Finds all VIX alerts that have NOT been sent to Telegram.
     *
     * @return Flux emitting VixAlert records with telegram_sent = false
     */
    @Query("SELECT * FROM vix_alerts WHERE telegram_sent = false ORDER BY triggered_at DESC")
    Flux<VixAlert> findPendingTelegram();

    /**
     * Counts the number of VIX alerts.
     *
     * @return Mono emitting the count
     */
    @Query("SELECT COUNT(*) FROM vix_alerts")
    Mono<Long> countAlerts();
}
