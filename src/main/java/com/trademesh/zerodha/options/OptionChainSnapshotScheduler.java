package com.trademesh.zerodha.options;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Scheduled service for capturing option chain snapshots every 5 minutes.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Fetches option chain data for NIFTY50 and NIFTYBANK every 5 minutes.</li>
 *   <li>Persists snapshots to PostgreSQL for historical analysis.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptionChainSnapshotScheduler {

    private final OptionChainService optionChainService;
    private final OptionChainSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    // ─── Symbols to snapshot ───────────────────────────────────────────────────────
    private static final String[] SYMBOLS = {"NIFTY50", "NIFTYBANK"};

    /**
     * Captures option chain snapshots every 5 minutes (300,000 milliseconds).
     * Runs during market hours (Mon–Fri, 9:15 AM – 3:30 PM IST).
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void captureOptionChainSnapshots() {
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
