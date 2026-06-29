package com.trademesh.intelligence.iv;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled service for daily ATM IV collection.
 * Captures ATM implied volatility for NIFTY50 at market open (9:25 AM IST on weekdays).
 * <p>
 * Responsibilities:
 * - Checks token availability before proceeding
 * - Calls IvHistoryService to calculate and save ATM IV
 * - Logs success/failure with structured logging
 * - Handles errors gracefully without blocking
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IvHistoryScheduler {

    private static final String SCHEDULER_NAME = "IvHistoryScheduler";

    private final IvHistoryService ivHistoryService;
    private final TokenAvailabilityTracker tokenTracker;

    /**
     * Captures ATM IV for NIFTY50 at 9:25 AM IST on weekdays.
     * Runs after market open when option chain is liquid.
     * <p>
     * Checks token availability before proceeding to avoid unnecessary API calls.
     * Uses onErrorResume to handle errors gracefully without blocking.
     */
    @Scheduled(cron = "0 25 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureAtmIv() {

        if (!tokenTracker.isTokenAvailable()) {
            log.warn("[IvHistoryScheduler] Zerodha token unavailable. Skipping ATM IV capture.");
            return;
        }

        log.info("Starting ATM IV capture for NIFTY50");

        ivHistoryService.calculateAndSaveAtmIv("NIFTY50")
                .doOnSuccess(saved -> {
                    if (saved != null) {
                        log.info("ATM IV recorded for NIFTY50: {}%", saved.getAtmIv());
                    }
                })
                .doOnError(e -> log.error("Error capturing ATM IV for NIFTY50: {}", e.getMessage()))
                .onErrorResume(e -> {
                    log.debug("ATM IV capture failed for NIFTY50, continuing gracefully");
                    return reactor.core.publisher.Mono.empty();
                })
                .subscribe();

        ivHistoryService.calculateAndSaveAtmIv("NIFTYBANK")
                .doOnSuccess(saved -> {
                    if (saved != null) {
                        log.info("ATM IV recorded for NIFTYBANK: {}%", saved.getAtmIv());
                    }
                })
                .doOnError(e -> log.error("Error capturing ATM IV for NIFTYBANK: {}", e.getMessage()))
                .onErrorResume(e -> {
                    log.debug("ATM IV capture failed for NIFTYBANK, continuing gracefully");
                    return reactor.core.publisher.Mono.empty();
                })
                .subscribe();
    }
}
