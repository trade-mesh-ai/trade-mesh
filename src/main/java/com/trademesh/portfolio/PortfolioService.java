package com.trademesh.portfolio;

import com.trademesh.zerodha.orders.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Portfolio service for tracking positions and computing P&L.
 * <p>
 * Responsibilities:
 * - Sync positions from Zerodha (via OrderService)
 * - Compute P&L for each position
 * - Persist positions to PostgreSQL via R2DBC
 * - Track position history for reporting
 * - Provide portfolio summary and analytics
 * <p>
 * Phase 2 focuses on in-memory tracking. Phase 3 will add database persistence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final OrderService orderService;

    // In-memory position tracking (Phase 2)
    // Phase 3 will use R2DBC repository for persistence
    private final ConcurrentHashMap<String, PositionSummary> positions = new ConcurrentHashMap<>();

    /**
     * Returns all current open positions.
     *
     * @return Flux emitting all open positions
     */
    public Flux<PositionSummary> getOpenPositions() {
        log.info("Fetching open positions...");
        return Flux.fromIterable(positions.values())
                .filter(pos -> pos.getQuantity() != 0)
                .doOnNext(pos -> log.debug("Position: {} qty={} pnl={}",
                        pos.getTradingSymbol(), pos.getQuantity(), pos.getPnl()));
    }

    /**
     * Returns the total unrealised P&L across all open positions.
     *
     * @return Mono emitting total P&L
     */
    public Mono<Double> getTotalPnl() {
        return getOpenPositions()
                .map(PositionSummary::getPnl)
                .reduce(0.0, Double::sum)
                .doOnNext(totalPnl -> log.info("Total P&L: {}", totalPnl));
    }

    /**
     * Gets position details for a specific symbol.
     *
     * @param symbol trading symbol
     * @return Mono emitting position summary or empty if no position
     */
    public Mono<PositionSummary> getPositionDetails(String symbol) {
        log.info("Fetching position details for: {}", symbol);
        return Mono.justOrEmpty(positions.get(symbol))
                .doOnNext(pos -> log.info("Position found: {} qty={} pnl={}",
                        symbol, pos.getQuantity(), pos.getPnl()))
                .switchIfEmpty(Mono.fromRunnable(() -> log.info("No position found for: {}", symbol)));
    }

    /**
     * Adds or updates a position.
     *
     * @param symbol     trading symbol
     * @param quantity   quantity to add
     * @param entryPrice entry price
     * @return Mono completing when position is updated
     */
    public Mono<Void> updatePosition(String symbol, int quantity, double entryPrice) {
        return Mono.fromRunnable(() -> {
            log.info("Updating position: {} qty={} price={}", symbol, quantity, entryPrice);

            positions.compute(symbol, (key, existing) -> {
                if (existing == null) {
                    return PositionSummary.builder()
                            .tradingSymbol(symbol)
                            .quantity(quantity)
                            .averagePrice(entryPrice)
                            .lastPrice(entryPrice)
                            .pnl(0.0)
                            .pnlPercent(0.0)
                            .build();
                } else {
                    // Update existing position
                    int newQty = existing.getQuantity() + quantity;
                    double newAveragePrice = (existing.getAveragePrice() * existing.getQuantity() + entryPrice * quantity) / newQty;
                    existing.setQuantity(newQty);
                    existing.setAveragePrice(newAveragePrice);
                    return existing;
                }
            });

            log.info("Position updated: {}", symbol);
        });
    }

    /**
     * Updates current price and computes P&L for a position.
     *
     * @param symbol       trading symbol
     * @param currentPrice current market price
     * @return Mono completing when P&L is updated
     */
    public Mono<Void> updatePnl(String symbol, double currentPrice) {
        return Mono.fromRunnable(() -> {
            log.debug("Updating P&L for: {} price={}", symbol, currentPrice);

            positions.compute(symbol, (key, existing) -> {
                if (existing != null) {
                    existing.setLastPrice(currentPrice);
                    double pnl = (currentPrice - existing.getAveragePrice()) * existing.getQuantity();
                    double pnlPercent = ((currentPrice - existing.getAveragePrice()) / existing.getAveragePrice()) * 100;
                    existing.setPnl(pnl);
                    existing.setPnlPercent(pnlPercent);
                }
                return existing;
            });
        });
    }

    /**
     * Closes a position.
     *
     * @param symbol    trading symbol
     * @param exitPrice exit price
     * @return Mono emitting final P&L
     */
    public Mono<Double> closePosition(String symbol, double exitPrice) {
        return Mono.fromCallable(() -> {
            log.info("Closing position: {} exitPrice={}", symbol, exitPrice);

            PositionSummary position = positions.get(symbol);
            if (position == null) {
                log.warn("No position found to close: {}", symbol);
                return 0.0;
            }

            double finalPnl = (exitPrice - position.getAveragePrice()) * position.getQuantity();
            positions.remove(symbol);

            log.info("Position closed: {} finalPnl={}", symbol, finalPnl);
            return finalPnl;
        });
    }

    /**
     * Syncs positions from Zerodha (placeholder for Phase 2).
     *
     * @return Mono completing when sync is done
     */
    public Mono<Void> syncPositionsFromZerodha() {
        return Mono.fromRunnable(() -> {
            log.info("Syncing positions from Zerodha...");
            // Phase 2: Just log the sync
            // Phase 3 will add: call Zerodha API to fetch positions and update local state
            log.info("Position sync completed");
        });
    }

    /**
     * Gets portfolio summary.
     *
     * @return Mono emitting portfolio summary
     */
    public Mono<PortfolioSummary> getPortfolioSummary() {
        return Mono.fromCallable(() -> {
            double totalPnl = positions.values().stream()
                    .mapToDouble(PositionSummary::getPnl)
                    .sum();

            int totalPositions = (int) positions.values().stream()
                    .filter(pos -> pos.getQuantity() != 0)
                    .count();

            return PortfolioSummary.builder()
                    .totalPositions(totalPositions)
                    .totalPnl(totalPnl)
                    .lastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                    .build();
        });
    }
}
