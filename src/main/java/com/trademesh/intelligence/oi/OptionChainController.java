package com.trademesh.intelligence.oi;

import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for option chain endpoints.
 * <p>
 * Provides endpoints to fetch and retrieve option chain data for NIFTY and BANKNIFTY.
 */
@Profile("!prod")
@Slf4j
@RestController
@RequestMapping("/intelligence/optionchain")
@RequiredArgsConstructor
public class OptionChainController {

    private final OptionChainService optionChainService;

    /**
     * Fetches the current option chain for a given symbol.
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return {@link Mono} emitting the OptionChain with HTTP 200, or error response
     */
    @GetMapping("/{symbol}")
    public Mono<ResponseEntity<OptionChain>> getOptionChain(@PathVariable String symbol) {
        log.info("Received request for option chain: {}", symbol);

        return optionChainService.fetchOptionChain(symbol)
                .map(optionChain -> {
                    log.info("Successfully fetched option chain for {}", symbol);
                    return ResponseEntity.ok(optionChain);
                })
                .onErrorResume(e -> {
                    log.error("Error fetching option chain for {}: {}", symbol, e.getMessage());
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
