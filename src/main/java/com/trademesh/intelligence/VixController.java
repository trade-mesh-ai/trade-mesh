package com.trademesh.intelligence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST Controller for VIX (Volatility Index) monitoring endpoints.
 * 
 * <p>Provides real-time VIX data and market intelligence for trading decisions.
 */
@Slf4j
@RestController
@RequestMapping("/intelligence/vix")
@RequiredArgsConstructor
public class VixController {

    private final VixMonitorService vixMonitorService;

    /**
     * Fetches the current VIX data.
     * 
     * <p>Returns the latest VIX value, its level classification, previous close,
     * percentage change, and timestamp. Data is cached for 5 minutes.
     *
     * @return {@link Mono} emitting the current VixData with HTTP 200 OK
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<VixData>> getCurrentVix() {
        log.debug("Received request for current VIX data");
        
        return vixMonitorService.fetchCurrentVix()
                .map(vixData -> {
                    log.info("Returning VIX data: value={}, level={}, safeToTrade={}",
                            vixData.getCurrentVix(),
                            vixData.getVixLevel(),
                            vixData.isSafeToTrade());
                    return ResponseEntity.ok(vixData);
                })
                .onErrorResume(e -> {
                    log.error("Error fetching VIX data: {}", e.getMessage(), e);
                    return Mono.just(ResponseEntity.status(500).build());
                });
    }
}
