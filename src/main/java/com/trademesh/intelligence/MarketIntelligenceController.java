package com.trademesh.intelligence;

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
 * REST Controller for market intelligence endpoints.
 *
 * <p>Provides comprehensive market intelligence including VIX, OI analysis,
 * max pain, market condition classification, and trading safety assessment.
 */
@Slf4j
@RestController
@RequestMapping("/intelligence")
@RequiredArgsConstructor
public class MarketIntelligenceController {

    private final MarketIntelligenceService marketIntelligenceService;

    /**
     * Fetches complete market intelligence for a given symbol.
     *
     * <p>Returns all components: VIX data, OI analysis, max pain, market condition,
     * and trading safety assessment. Data is cached for 5 minutes.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the complete MarketIntelligence with HTTP 200 OK
     */
    @GetMapping(value = "/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<MarketIntelligence>> getIntelligence(@PathVariable String symbol) {
        log.info("Received request for market intelligence: {}", symbol);

        return marketIntelligenceService.generateIntelligence(symbol)
                .map(intelligence -> {
                    log.info("Successfully generated intelligence for {}: condition={}, safe={}",
                            symbol, intelligence.getMarketCondition(), intelligence.getIsSafeToTrade());
                    return ResponseEntity.ok(intelligence);
                })
                .onErrorResume(e -> {
                    log.error("Error generating intelligence for {}: {}", symbol, e.getMessage(), e);
                    if (e instanceof IllegalArgumentException) {
                        return Mono.just(ResponseEntity.badRequest().build());
                    } else if (e instanceof IllegalStateException) {
                        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
                    } else {
                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                    }
                });
    }

    /**
     * Fetches only the market condition for a given symbol.
     *
     * <p>Returns the classified market condition (SIDEWAYS, TRENDING_BULLISH, etc.)
     * without other intelligence components.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the MarketCondition with HTTP 200 OK
     */
    @GetMapping(value = "/{symbol}/condition", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<MarketCondition>> getMarketCondition(@PathVariable String symbol) {
        log.info("Received request for market condition: {}", symbol);

        return marketIntelligenceService.generateIntelligence(symbol)
                .map(intelligence -> {
                    log.info("Market condition for {}: {}", symbol, intelligence.getMarketCondition());
                    return ResponseEntity.ok(intelligence.getMarketCondition());
                })
                .onErrorResume(e -> {
                    log.error("Error fetching market condition for {}: {}", symbol, e.getMessage(), e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Fetches a one-line summary of market intelligence for a given symbol.
     *
     * <p>Returns a formatted summary string with key metrics:
     * "CONDITION | VIX:value | Support:strike | Resistance:strike | MaxPain:strike"
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the summary string with HTTP 200 OK
     */
    @GetMapping(value = "/{symbol}/summary", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> getSummary(@PathVariable String symbol) {
        log.info("Received request for intelligence summary: {}", symbol);

        return marketIntelligenceService.generateIntelligence(symbol)
                .map(intelligence -> {
                    String summary = intelligence.summary();
                    log.info("Intelligence summary for {}: {}", symbol, summary);
                    return ResponseEntity.ok(summary);
                })
                .onErrorResume(e -> {
                    log.error("Error fetching intelligence summary for {}: {}", symbol, e.getMessage(), e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }
}
