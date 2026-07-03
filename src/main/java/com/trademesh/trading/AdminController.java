package com.trademesh.trading;

import com.trademesh.intelligence.KiteHistoricalService;
import com.trademesh.intelligence.MarketIntelligenceService;
import com.trademesh.intelligence.NiftyDailyPriceService;
import com.trademesh.intelligence.iv.IvHistoryService;
import com.trademesh.strategy.expiryday.ExpiryDayStrategy;
import com.trademesh.strategy.ironcondor.IronCondorStrategy;
import com.trademesh.strategy.spread.BearCallSpreadStrategy;
import com.trademesh.strategy.spread.BullPutSpreadStrategy;
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
    private final MarketIntelligenceService marketIntelligenceService;
    private final IronCondorStrategy ironCondorStrategy;
    private final BullPutSpreadStrategy bullPutSpreadStrategy;
    private final BearCallSpreadStrategy bearCallSpreadStrategy;
    private final ExpiryDayStrategy expiryDayStrategy;

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

    /**
     * Debug endpoint to test ATM IV calculation and save to database.
     * <p>
     * This endpoint calls the SAVE method of IvHistoryService (calculateAndSaveAtmIv)
     * which fetches the option chain, calculates IV, and persists to the database.
     * Intended for manual catch-up scenarios only.
     * <p>
     * Example: {@code GET /admin/test/atm-iv-save?symbol=NIFTY50}
     *
     * @param symbol the trading symbol (default "NIFTY50")
     * @return Mono emitting ResponseEntity with saved IvHistory object or error
     */
    @Operation(summary = "Test ATM IV calculation and save (debug endpoint, saves to DB)")
    @GetMapping(value = "/test/atm-iv-save", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> testAtmIvSave(
            @Parameter(description = "Trading symbol, default NIFTY50")
            @RequestParam(defaultValue = "NIFTY50") String symbol) {

        log.info("Testing ATM IV calculation and save for symbol: {}", symbol);

        return ivHistoryService.calculateAndSaveAtmIv(symbol)
                .<ResponseEntity<?>>map(ivHistory -> {
                    log.info("ATM IV saved successfully for {}: atmIv={}%", symbol, ivHistory.getAtmIv());
                    return ResponseEntity.ok((Object) ivHistory);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Could not save ATM IV for {}: already recorded today or calculation failed", symbol);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Could not save ATM IV");
                    errorResponse.put("reason", "Already recorded today or calculation failed");
                    return Mono.<ResponseEntity<?>>just(ResponseEntity.status(HttpStatus.NOT_FOUND).body((Object) errorResponse));
                }))
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error saving ATM IV for {}: {}", symbol, e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error saving ATM IV");
                    errorResponse.put("message", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((Object) errorResponse));
                });
    }

    /**
     * Debug endpoint to test Iron Condor strategy evaluation without persisting to database.
     * <p>
     * This endpoint generates market intelligence for NIFTY50 and calls the evaluate()
     * method of IronCondorStrategy to verify the strategy logic without executing any trades.
     * <p>
     * Example: {@code GET /admin/test/iron-condor-evaluate}
     *
     * @return Mono emitting ResponseEntity with StrategyResult as JSON
     */
    @Operation(summary = "Test Iron Condor strategy evaluation (debug endpoint, no DB save)")
    @GetMapping(value = "/test/iron-condor-evaluate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> testIronCondorEvaluation() {

        log.info("Testing Iron Condor strategy evaluation");

        return marketIntelligenceService.generateIntelligence("NIFTY50")
                .flatMap(intelligence -> {
                    log.debug("Generated market intelligence for Iron Condor evaluation: {}", intelligence.summary());
                    return ironCondorStrategy.evaluate(intelligence);
                })
                .<ResponseEntity<?>>map(strategyResult -> {
                    log.info("Iron Condor evaluation completed: isExecutable={}, skipReason={}",
                            strategyResult.getIsExecutable(), strategyResult.getSkipReason());
                    return ResponseEntity.ok((Object) strategyResult);
                })
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error evaluating Iron Condor strategy: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error evaluating Iron Condor strategy");
                    errorResponse.put("message", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((Object) errorResponse));
                });
    }

    /**
     * Debug endpoint to test Bull Put Spread strategy evaluation without persisting to database.
     * <p>
     * This endpoint generates market intelligence for NIFTY50 and calls the evaluate()
     * method of BullPutSpreadStrategy to verify the strategy logic without executing any trades.
     * <p>
     * Example: {@code GET /admin/test/bull-put-spread-evaluate}
     *
     * @return Mono emitting ResponseEntity with StrategyResult as JSON
     */
    @Operation(summary = "Test Bull Put Spread strategy evaluation (debug endpoint, no DB save)")
    @GetMapping(value = "/test/bull-put-spread-evaluate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> testBullPutSpreadEvaluation() {

        log.info("Testing Bull Put Spread strategy evaluation");

        return marketIntelligenceService.generateIntelligence("NIFTY50")
                .flatMap(intelligence -> {
                    log.debug("Generated market intelligence for Bull Put Spread evaluation: {}", intelligence.summary());
                    return bullPutSpreadStrategy.evaluate(intelligence);
                })
                .<ResponseEntity<?>>map(result -> {
                    log.info("Bull Put Spread evaluation completed: isExecutable={}, skipReason={}",
                            result.getIsExecutable(), result.getSkipReason());
                    return ResponseEntity.ok((Object) result);
                })
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error evaluating Bull Put Spread: {}", e.getMessage(), e);
                    Map<String, Object> error = new HashMap<>();
                    error.put("error", "Error evaluating Bull Put Spread");
                    error.put("message", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body((Object) error));
                });
    }

    /**
     * Debug endpoint to test Bear Call Spread strategy evaluation without persisting to database.
     * <p>
     * This endpoint generates market intelligence for NIFTY50 and calls the evaluate()
     * method of BearCallSpreadStrategy to verify the strategy logic without executing any trades.
     * <p>
     * Example: {@code GET /admin/test/bear-call-spread-evaluate}
     *
     * @return Mono emitting ResponseEntity with StrategyResult as JSON
     */
    @Operation(summary = "Test Bear Call Spread strategy evaluation (debug endpoint, no DB save)")
    @GetMapping(value = "/test/bear-call-spread-evaluate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> testBearCallSpreadEvaluation() {

        log.info("Testing Bear Call Spread strategy evaluation");

        return marketIntelligenceService.generateIntelligence("NIFTY50")
                .flatMap(intelligence -> {
                    log.debug("Generated market intelligence for Bear Call Spread evaluation: {}", intelligence.summary());
                    return bearCallSpreadStrategy.evaluate(intelligence);
                })
                .<ResponseEntity<?>>map(result -> {
                    log.debug("Bear Call Spread evaluation completed: isExecutable={}, skipReason={}",
                            result.getIsExecutable(), result.getSkipReason());
                    return ResponseEntity.ok((Object) result);
                })
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error evaluating Bear Call Spread: {}", e.getMessage(), e);
                    Map<String, Object> error = new HashMap<>();
                    error.put("error", "Error evaluating Bear Call Spread");
                    error.put("message", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body((Object) error));
                });
    }

    @Operation(summary = "Test Expiry Day strategy evaluation (debug endpoint, no DB save)")
    @GetMapping(value = "/test/expiry-day-evaluate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> testExpiryDayEvaluation() {

        log.info("Testing Expiry Day strategy evaluation");

        return marketIntelligenceService.generateIntelligence("NIFTY50")
                .flatMap(intelligence -> {
                    log.debug("Generated market intelligence for Expiry Day evaluation: {}", intelligence.summary());
                    return expiryDayStrategy.evaluate(intelligence);
                })
                .<ResponseEntity<?>>map(strategyResult -> {
                    log.info("Expiry Day evaluation completed: isExecutable={}, skipReason={}",
                            strategyResult.getIsExecutable(), strategyResult.getSkipReason());
                    return ResponseEntity.ok((Object) strategyResult);
                })
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error evaluating Expiry Day strategy: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error evaluating Expiry Day strategy");
                    errorResponse.put("message", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((Object) errorResponse));
                });
    }
}
