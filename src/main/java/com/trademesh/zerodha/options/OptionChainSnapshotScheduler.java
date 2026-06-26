package com.trademesh.zerodha.options;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static com.trademesh.config.ApplicationConstants.SYMBOLS;

/**
 * Scheduled service for capturing option chain snapshots every 5 minutes.
 * <p>
 * Responsibilities:
 * - Fetches option chain data for NIFTY50 and NIFTYBANK every 5 minutes.
 * - Persists snapshots to PostgreSQL for historical analysis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptionChainSnapshotScheduler {

    private final OptionChainService optionChainService;
    private final OptionChainSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final TokenAvailabilityTracker tokenTracker;

    /**
     * Captures option chain snapshots every 5 minutes (300,000 milliseconds).
     * Runs during market hours (Mon–Fri, 9:15 AM – 3:30 PM IST).
     * Performs a single token availability check before fan-out to prevent redundant
     * Redis lookups and log spam when unauthenticated.
     */
    //@Scheduled(fixedRate = 300000) // 5 minutes
    public void captureOptionChainSnapshots() {

        if (!tokenTracker.isTokenAvailable()) {
            log.warn("[OptionChainSnapshotScheduler] Zerodha token unavailable. Skipping option chain snapshot.");
            return;
        }

        log.info("Starting option chain snapshot capture ...");

        for (String symbol : SYMBOLS) {
            captureSnapshot(symbol)
                    .doOnError(e -> log.error("Failed to capture snapshot for {}: {}", symbol, e.getMessage()))
                    .subscribe();
        }
    }

    /**
     * Captures a single option chain snapshot for a given symbol.
     */
    private Mono<Void> captureSnapshot(String symbol) {
        return optionChainService.fetchOptionChain(symbol)
                .flatMap(optionChain -> {
                    try {
                        // Serialize the option chain to JSON
                        String jsonData = objectMapper.writeValueAsString(optionChain);

                        // Create a snapshot entity
                        OptionChainSnapshot snapshot = OptionChainSnapshot.builder()
                                .symbol(optionChain.getSymbol())
                                .expiryDate(optionChain.getExpiryDate())
                                .spotPrice(optionChain.getSpotPrice())
                                .data(jsonData)
                                .createdAt(Instant.now())
                                .build();

                        // Save to PostgreSQL
                        return snapshotRepository.save(snapshot)
                                .doOnSuccess(saved -> log.info("Saved option chain snapshot for {} (id={})", symbol, saved.getId()))
                                .then();
                    } catch (Exception e) {
                        log.error("Failed to serialize option chain for {}: {}", symbol, e.getMessage());
                        return Mono.error(e);
                    }
                });
    }
}
