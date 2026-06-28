package com.trademesh.trading;

import com.trademesh.intelligence.KiteHistoricalService;
import com.trademesh.intelligence.NiftyDailyPriceService;
import com.trademesh.intelligence.iv.IvHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST controller for administrative operations.
 * <p>
 * Provides endpoints for bootstrapping and managing historical data.
 * These endpoints are intended for internal/admin use only.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Administrative operations for data management")
public class AdminController {

    private final KiteHistoricalService kiteHistoricalService;
    private final NiftyDailyPriceService niftyDailyPriceService;
    private final IvHistoryService ivHistoryService;

    /**
     * Bootstrap endpoint to fetch historical NIFTY50 OHLC data from Kite Connect
     * and store it in the database.
     * <p>
     * This endpoint is idempotent: calling it multiple times with the same date range
     * will skip records that already exist.
     * <p>
     * Example: {@code POST /admin/bootstrap/historical-prices?from=2024-01-01&to=2024-12-31}
     *
     * @param from the start date (default "2024-01-01")
     * @param to   the end date (default today)
     * @return Mono emitting ResponseEntity with summary of saved/skipped records
     */
    @Operation(summary = "Bootstrap historical NIFTY50 prices from Kite Connect")
    @PostMapping(value = "/bootstrap/historical-prices", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> bootstrapHistoricalPrices(
            @Parameter(description = "Start date (YYYY-MM-DD), default 2024-01-01")
            @RequestParam(defaultValue = "2024-01-01") String from,
            @Parameter(description = "End date (YYYY-MM-DD), default today")
            @RequestParam(required = false) String to) {

        log.info("Bootstrap request: from={}, to={}", from, to);

        try {
            LocalDate fromDate = LocalDate.parse(from);
            LocalDate toDate = to != null ? LocalDate.parse(to) : LocalDate.now();

            if (fromDate.isAfter(toDate)) {
                log.warn("Invalid date range: from {} is after to {}", fromDate, toDate);
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "Invalid date range: 'from' must be before or equal to 'to'");
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse));
            }

            AtomicInteger saved = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);

            return kiteHistoricalService.fetchHistoricalOHLC(fromDate, toDate)
                    .flatMap(price -> niftyDailyPriceService.saveIfNotExists(price)
                            .doOnNext(p -> {
                                if (p.isNew()) {
                                    saved.incrementAndGet();
                                } else {
                                    skipped.incrementAndGet();
                                }
                            }))
                    .collectList()
                    .map(prices -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("saved", saved.get());
                        response.put("skipped", skipped.get());
                        response.put("from", fromDate.toString());
                        response.put("to", toDate.toString());
                        response.put("total", saved.get() + skipped.get());

                        log.info("Bootstrap completed: saved={}, skipped={}, total={}",
                                saved.get(), skipped.get(), saved.get() + skipped.get());

                        return ResponseEntity.ok(response);
                    })
                    .onErrorResume(e -> {
                        log.error("Error during bootstrap: {}", e.getMessage(), e);
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("error", "Error during bootstrap: " + e.getMessage());
                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                    });

        } catch (Exception e) {
            log.error("Invalid date format: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Use YYYY-MM-DD");
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse));
        }
    }

    /**
     * Debug endpoint to test ATM IV calculation without persisting to database.
     * <p>
     * This endpoint calls the READ method of IvHistoryService (calculateAtmIv)
     * which fetches the option chain, finds the ATM strike, and calculates IV
     * without saving to the database.
     * <p>
     * Example: {@code GET /admin/test/atm-iv?symbol=NIFTY50}
     *
     * @param symbol the trading symbol (default "NIFTY50")
     * @return Mono emitting ResponseEntity with IvHistory object or error
     */
    @Operation(summary = "Test ATM IV calculation (debug endpoint, no DB save)")
    @GetMapping(value = "/test/atm-iv", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> testAtmIvCalculation(
            @Parameter(description = "Trading symbol, default NIFTY50")
            @RequestParam(defaultValue = "NIFTY50") String symbol) {

        log.info("Testing ATM IV calculation for symbol: {}", symbol);

        return ivHistoryService.calculateAtmIv(symbol)
                .<ResponseEntity<?>>map(ivHistory -> {
                    log.info("ATM IV calculated successfully for {}: atmIv={}%", symbol, ivHistory.getAtmIv());
                    return ResponseEntity.ok((Object) ivHistory);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Could not calculate ATM IV for {}: option chain unavailable or both IVs are zero", symbol);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Could not calculate ATM IV");
                    errorResponse.put("reason", "Option chain unavailable or both IVs are zero");
                    return Mono.<ResponseEntity<?>>just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((Object) errorResponse));
                }))
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error calculating ATM IV for {}: {}", symbol, e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error calculating ATM IV");
                    errorResponse.put("message", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((Object) errorResponse));
                });
    }
}
