package com.trademesh.intelligence.maxpain;

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
 * REST Controller for Max Pain calculation endpoints.
 * 
 * <p>Provides endpoints to fetch and calculate max pain for trading symbols.
 * Max Pain is the strike price at which the maximum number of options expire worthless.
 */
@Slf4j
@RestController
@RequestMapping("/intelligence/maxpain")
@RequiredArgsConstructor
public class MaxPainController {

    private final MaxPainCalculatorService maxPainCalculatorService;
    private final OptionChainService optionChainService;

    /**
     * Fetches the max pain calculation for a given symbol.
     *
     * <p>Returns the max pain strike, current spot price, and distance metrics.
     * Data is cached for 15 minutes.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the MaxPainResult with HTTP 200 OK, or error response
     */
    @GetMapping(value = "/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<MaxPainResult>> getMaxPain(@PathVariable String symbol) {
        log.info("Received request for max pain calculation: {}", symbol);

        return optionChainService.fetchOptionChain(symbol)
                .flatMap(maxPainCalculatorService::calculate)
                .map(result -> {
                    log.info("Successfully calculated max pain for {}: strike={}, distance={}%", 
                            symbol, result.getMaxPainStrike(), 
                            String.format("%.2f", result.getDistancePercent()));
                    return ResponseEntity.ok(result);
                })
                .onErrorResume(e -> {
                    log.error("Error calculating max pain for {}: {}", symbol, e.getMessage(), e);
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
