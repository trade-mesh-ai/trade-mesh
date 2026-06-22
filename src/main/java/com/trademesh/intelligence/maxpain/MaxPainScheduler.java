package com.trademesh.intelligence.maxpain;

import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

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

    // ─── Symbols to analyze ───────────────────────────────────────────────────────
    private static final String[] SYMBOLS = {"NIFTY50", "NIFTYBANK"};
    private final OptionChainService optionChainService;
    private final MaxPainCalculatorService maxPainCalculatorService;
    private final MaxPainHistoryRepository maxPainHistoryRepository;

    /**
     * Captures max pain calculation snapshots every 30 minutes (1,800,000 milliseconds).
     * Runs during market hours (Mon–Fri, 9:15 AM – 3:30 PM IST).
     */
    @Scheduled(fixedRate = 1800000) // 30 minutes
    public void captureMaxPainSnapshots() {
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
                .flatMap(maxPainCalculatorService::calculate)
                .flatMap(result -> {
                    // Set the ID to null to ensure a new record is created
                    result.setId(null);

                    // Ensure calculatedAt is set
                    if (result.getCalculatedAt() == null) {
                        result.setCalculatedAt(Instant.now());
                    }

                    // Save to PostgreSQL
                    return maxPainHistoryRepository.save(result)
                            .doOnSuccess(saved -> log.info("Saved max pain snapshot for {} (id={}, strike={})",
                                    symbol, saved.getId(), saved.getMaxPainStrike()))
                            .then();
                });
    }
}
