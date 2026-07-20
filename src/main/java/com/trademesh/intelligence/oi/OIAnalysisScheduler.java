package com.trademesh.intelligence.oi;

import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import static com.trademesh.config.ApplicationConstants.SYMBOLS;

/**
 * Scheduled service for capturing OI analysis snapshots every 15 minutes.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Fetches option chain data for NIFTY50 and NIFTYBANK every 15 minutes.</li>
 *   <li>Performs OI analysis on the fetched data.</li>
 *   <li>Persists analysis snapshots to PostgreSQL for historical analysis.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OIAnalysisScheduler {

    private static final String SCHEDULER_NAME = "OIAnalysisScheduler";

    private final OptionChainService optionChainService;
    private final OIAnalyserService oiAnalyserService;
    private final TokenAvailabilityTracker tokenTracker;

    /**
     * Captures OI analysis snapshots every 15 minutes during market hours.
     * Runs during market hours (Mon–Fri, 9:15 AM – 3:30 PM IST).
     * Performs a single token availability check before fan-out to prevent redundant
     * Redis lookups and log spam when unauthenticated.
     */
    @Scheduled(cron = "${intelligence.oi.persist-interval-minutes:0 */15 9-15 ? * MON-FRI}", zone = "Asia/Kolkata")
    public void captureOIAnalysisSnapshots() {

        if (!tokenTracker.isTokenAvailable()) {
            tokenTracker.trackTokenMissing(SCHEDULER_NAME);
            return;
        }

        log.info("Starting OI analysis snapshot capture ...");

        for (String symbol : SYMBOLS) {
            captureAnalysis(symbol)
                    .doOnError(e -> {
                        if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                            tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                        } else {
                            log.error("Failed to capture OI analysis for {}: {}", symbol, e.getMessage());
                        }
                    })
                    .onErrorResume(e -> {
                        if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                            log.debug("OI analysis capture failed due to missing token, continuing with next symbol");
                            return Mono.empty();
                        }
                        return Mono.error(e);
                    })
                    .subscribe();
        }
    }

    /**
     * Captures a single OI analysis snapshot for a given symbol.
     */
    private Mono<Void> captureAnalysis(String symbol) {
        return optionChainService.fetchOptionChain(symbol)
                .flatMap(oiAnalyserService::captureAndSave)
                .then();
    }
}
