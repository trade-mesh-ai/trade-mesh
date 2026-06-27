package com.trademesh.trading;

import com.trademesh.execution.PaperTrade;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive R2DBC repository for StrategyExecutionLog entities.
 * <p>
 * Provides database access for strategy execution logs with custom query methods
 * for filtering by symbol, date, strategy, and other criteria.
 */
@Repository
public interface StrategyExecutionLogRepository extends ReactiveCrudRepository<StrategyExecutionLog, UUID> {

    /**
     * Finds all execution logs for a specific symbol.
     *
     * @param symbol the trading symbol to filter by
     * @return Flux of matching execution logs
     */
    Flux<StrategyExecutionLog> findBySymbol(String symbol);

    /**
     * Finds all execution logs for a specific strategy.
     *
     * @param strategyName the strategy name to filter by
     * @return Flux of matching execution logs
     */
    Flux<StrategyExecutionLog> findByStrategyName(String strategyName);

    /**
     * Finds all execution logs for a specific market condition.
     *
     * @param marketCondition the market condition to filter by
     * @return Flux of matching execution logs
     */
    Flux<StrategyExecutionLog> findByMarketCondition(String marketCondition);

    /**
     * Finds all open paper positions for a given symbol.
     * <p>
     * This is a custom method that queries the paper_trades table
     * to get all positions with OPEN status for the symbol.
     *
     * @param symbol the trading symbol
     * @return Flux of open PaperTrade entities
     */
    Flux<PaperTrade> findOpenPositionsBySymbol(String symbol);

    /**
     * Finds a single execution log by ID.
     *
     * @param id the UUID of the execution log
     * @return Mono emitting the execution log if found
     */
    Mono<StrategyExecutionLog> findById(UUID id);
}
