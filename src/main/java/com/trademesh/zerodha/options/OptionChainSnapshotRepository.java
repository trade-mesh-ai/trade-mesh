package com.trademesh.zerodha.options;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.LocalDate;

/**
 * Reactive R2DBC repository for {@link OptionChainSnapshot} entities.
 */
@Repository
public interface OptionChainSnapshotRepository extends ReactiveCrudRepository<OptionChainSnapshot, Long> {

    /**
     * Finds all snapshots for a given symbol.
     */
    Flux<OptionChainSnapshot> findBySymbol(String symbol);

    /**
     * Finds all snapshots for a given symbol and expiry date.
     */
    Flux<OptionChainSnapshot> findBySymbolAndExpiryDate(String symbol, LocalDate expiryDate);
}
