package com.trademesh.execution;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Scheduler for mark-to-market updates on open paper trades.
 * Runs periodically during market hours (09:15 to 15:30 IST) to update trade values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkToMarketScheduler {

    private final PaperTradingEngine paperTradingEngine;
    private final PaperTradeRepository paperTradeRepository;
    private final TokenAvailabilityTracker tokenAvailabilityTracker;

    /**
     * Marks all open trades to market at scheduled intervals.
     * Only runs during market hours (09:15 to 15:30 IST).
     * <p>
     * Implements per-trade error handling (Option B) to ensure one trade's failure
     * (e.g., missing Zerodha token) does not abort processing of other open trades.
     * Each trade's markToMarket() call is independently protected with onErrorResume().
     */
    @Scheduled(fixedRateString = "${trading.mark-to-market-interval-ms:60000}")
    public void markToMarketScheduled() {
        LocalTime currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        executeMarkToMarket(currentTime);
    }

    /**
     * Executes mark-to-market processing for all open trades.
     * Extracted as a separate method to allow time injection in tests.
     *
     * @param currentTime the current time to check against market hours
     */
    void executeMarkToMarket(LocalTime currentTime) {
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        if (currentTime.isBefore(marketOpen) || currentTime.isAfter(marketClose)) {
            log.debug("Outside market hours ({}), skipping mark-to-market", currentTime);
            return;
        }

        log.debug("Starting mark-to-market cycle at {}", currentTime);

        // Fetch all open trades and mark them to market with per-trade error handling
        paperTradeRepository.findByStatus(TradeStatus.OPEN)
                .flatMap(trade -> paperTradingEngine.markToMarket(trade)
                        .onErrorResume(e -> {
                            // Per-trade error boundary: catch token-related failures
                            if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                                log.warn("⚠️ Zerodha token missing — MTM running in degraded mode, option prices unavailable for trade {}", trade.getId());
                                tokenAvailabilityTracker.setTokenUnavailable();
                                tokenAvailabilityTracker.trackTokenMissing("MarkToMarketScheduler");
                                return Mono.empty();
                            }
                            // For other errors, log and continue to next trade
                            log.error("Error marking trade {} to market: {}", trade.getId(), e.getMessage(), e);
                            return Mono.empty();
                        }))
                .doOnNext(trade -> log.debug("Marked to market: {} | P&L: {}", trade.getId(), trade.getUnrealizedPnl()))
                .collectList()
                .subscribe(
                        trades -> log.info("Mark-to-market cycle completed: {} trades processed", trades.size()),
                        error -> log.error("Error in mark-to-market scheduler", error)
                );
    }
}
