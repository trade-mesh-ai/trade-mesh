package com.trademesh.domain;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * Reactive R2DBC repository for {@link Instrument} entities.
 */
@Repository
public interface InstrumentRepository extends ReactiveCrudRepository<Instrument, Long> {

    /**
     * Finds all instruments for a given exchange (e.g. "NSE", "BSE").
     */
    Flux<Instrument> findByExchange(String exchange);

    /**
     * Finds instruments by trading symbol (case-sensitive).
     */
    Flux<Instrument> findByTradingsymbol(String tradingsymbol);
}
