package com.trademesh.intelligence.maxpain;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static com.trademesh.config.ApplicationConstants.SYMBOLS;

/**
 * Scheduled service for capturing max pain calculation snapshots every 30 minutes.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Fetches option chain data for NIFTY50 and NIFTYBANK every 30 minutes.</li>
 *   <li>Calculates max pain for the fetched data.</li>
 *   <li>Persists max pain snapshots to PostgreSQL for historical analysis.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaxPainScheduler {

    private static final String SCHEDULER_NAME = "MaxPainScheduler";

    private final OptionChainService optionChainService;
    private final MaxPainCalculatorService maxPainCalculatorService;
    private final MaxPainHistoryRepository maxPainHistoryRepository;
    private final TokenAvailabilityTracker tokenTracker;

    /**
     * Captures max pain calculation snapshots every 30 minutes (1,800,000 milliseconds).
     * Runs during market hours (Mon–Fri, 9:15 AM – 3:30 PM IST).
     * Performs a single token availability check before fan-out to prevent redundant
     * Redis lookups and log spam when unauthenticated.
     */
    @Scheduled(fixedRateString = "${intelligence.maxpain.persist-interval-minutes:30}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void captureMaxPainSnapshots() {

        if (!tokenTracker.isTokenAvailable()) {
            log.warn("[MaxPainScheduler] Zerodha token unavailable. Skipping max pain snapshot capture.");
            return;
        }

        log.info("Starting max pain snapshot capture ...");

        for (String symbol : SYMBOLS) {
            captureMaxPain(symbol)
                    .doOnError(e -> log.error("Failed to capture max pain for {}: {}", symbol, e.getMessage()))
                    .subscribe();
        }
    }

    /**
     * Captures a single max pain calculation snapshot for a given symbol.
     */
    private Mono<Void> captureMaxPain(String symbol) {
        return optionChainService.fetchOptionChain(symbol)
                .flatMap(maxPainCalculatorService::calculateAndSave)
                .then()
                .doOnError(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                    } else {
                        log.error("Failed to capture max pain for {}: {}", symbol, e.getMessage(), e);
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        log.debug("Max pain capture failed due to missing token, continuing with next symbol");
                        return Mono.empty();
                    }
                    return Mono.error(e);
                });
    }
}
