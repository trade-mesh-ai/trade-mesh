package com.trademesh.intelligence.oi;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final OptionChainService optionChainService;
    private final OIAnalyserService oiAnalyserService;
    private final OIAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final TokenAvailabilityTracker tokenTracker;

    /**
     * Captures OI analysis snapshots every 15 minutes (900,000 milliseconds).
     * Runs during market hours (Mon–Fri, 9:15 AM – 3:30 PM IST).
     * Performs a single token availability check before fan-out to prevent redundant
     * Redis lookups and log spam when unauthenticated.
     */
    @Scheduled(fixedRate = 900000) // 15 minutes
    public void captureOIAnalysisSnapshots() {

        if (!tokenTracker.isTokenAvailable()) {
            log.warn("[OIAnalysisScheduler] Zerodha token unavailable. Skipping OI analysis snapshot capture.");
            return;
        }

        log.info("Starting OI analysis snapshot capture ...");

        for (String symbol : SYMBOLS) {
            captureAnalysis(symbol)
                    .doOnError(e -> log.error("Failed to capture OI analysis for {}: {}", symbol, e.getMessage()))
                    .subscribe();
        }
    }

    /**
     * Captures a single OI analysis snapshot for a given symbol.
     */
    private Mono<Void> captureAnalysis(String symbol) {
        return optionChainService.fetchOptionChain(symbol)
                .flatMap(oiAnalyserService::analyseOI)
                .flatMap(analysis -> {
                    try {
                        // Set the ID to null to ensure a new record is created
                        analysis.setId(null);

                        // Ensure analyzedAt is set
                        if (analysis.getAnalyzedAt() == null) {
                            analysis.setAnalyzedAt(Instant.now());
                        }

                        // Serialize the full analysis to JSON
                        String jsonData = objectMapper.writeValueAsString(analysis);
                        analysis.setData(jsonData);

                        // Save to PostgreSQL
                        return analysisRepository.save(analysis)
                                .doOnSuccess(saved -> log.info("Saved OI analysis snapshot for {} (id={}, bias={})",
                                        symbol, saved.getId(), saved.getMarketBias()))
                                .then();
                    } catch (Exception e) {
                        log.error("Failed to serialize OI analysis for {}: {}", symbol, e.getMessage());
                        return Mono.error(e);
                    }
                });
    }
}
