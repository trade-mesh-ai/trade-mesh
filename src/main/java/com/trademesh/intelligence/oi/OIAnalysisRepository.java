package com.trademesh.intelligence.oi;

import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for OIAnalysis entities.
 * 
 * <p>Provides reactive database operations for OI analysis snapshots.
 */
@Repository
public interface OIAnalysisRepository extends ReactiveCrudRepository<OIAnalysis, Long> {

    /**
     * Finds the latest OI analysis for a given symbol.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return Mono emitting the latest OIAnalysis, or empty if not found
     */
    Mono<OIAnalysis> findFirstBySymbolOrderByAnalyzedAtDesc(@Param("symbol") String symbol);
}
