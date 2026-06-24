package com.trademesh.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    /**
     * Marks all open trades to market at scheduled intervals.
     * Only runs during market hours (09:15 to 15:30 IST).
     */
    @Scheduled(fixedRateString = "${strategy.mark-to-market-interval-ms:60000}")
    public void markToMarketScheduled() {
        // Check if current time is within market hours
        LocalTime currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime marketOpen = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        if (currentTime.isBefore(marketOpen) || currentTime.isAfter(marketClose)) {
            log.debug("Outside market hours ({}), skipping mark-to-market", currentTime);
            return;
        }

        log.debug("Starting mark-to-market cycle at {}", currentTime);

        // Fetch all open trades and mark them to market
        paperTradeRepository.findByStatus(TradeStatus.OPEN)
                .flatMap(paperTradingEngine::markToMarket)
                .doOnNext(trade -> log.debug("Marked to market: {} | P&L: {}", trade.getId(), trade.getUnrealizedPnl()))
                .doOnError(error -> log.error("Error marking trade to market", error))
                .collectList()
                .subscribe(
                        trades -> log.info("Mark-to-market cycle completed: {} trades processed", trades.size()),
                        error -> log.error("Error in mark-to-market scheduler", error)
                );
    }
}
