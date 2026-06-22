package com.trademesh.intelligence.oi;

import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST Controller for OI (Open Interest) analysis endpoints.
 *
 * <p>Provides endpoints to fetch and analyze OI patterns for trading symbols.
 */
@Slf4j
@RestController
@RequestMapping("/intelligence/oi")
@RequiredArgsConstructor
public class OIController {

    private final OIAnalyserService oiAnalyserService;
    private final OptionChainService optionChainService;

    /**
     * Fetches the OI analysis for a given symbol.
     * <p>
     * Returns the latest OI analysis including:
     * - Maximum call OI strike (resistance wall)
     * - Maximum put OI strike (support wall)
     * - Top 3 call and put OI walls
     * - Overall market bias (BULLISH/BEARISH/NEUTRAL)
     * - Analysis timestamp
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the OIAnalysis with HTTP 200 OK, or error response
     */
    @GetMapping(value = "/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<OIAnalysis>> getOIAnalysis(@PathVariable String symbol) {
        log.info("Received request for OI analysis: {}", symbol);

        return optionChainService.fetchOptionChain(symbol)
                .flatMap(oiAnalyserService::analyseOI)
                .map(analysis -> {
                    log.info("Successfully fetched OI analysis for {}: bias={}", symbol, analysis.getMarketBias());
                    return ResponseEntity.ok(analysis);
                })
                .onErrorResume(e -> {
                    log.error("Error fetching OI analysis for {}: {}", symbol, e.getMessage(), e);
                    if (e instanceof IllegalArgumentException) {
                        return Mono.just(ResponseEntity.badRequest().build());
                    } else if (e instanceof IllegalStateException) {
                        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
                    } else {
                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                    }
                });
    }
}
