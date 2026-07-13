package com.trademesh.trading;

import com.trademesh.domain.NiftyDailyPrice;
import com.trademesh.domain.NiftyDailyPriceRepository;
import com.trademesh.intelligence.AtrPercentilesResponse;
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
    private final NiftyDailyPriceRepository niftyDailyPriceRepository;

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

    /**
     * TEMPORARY DIAGNOSTIC ENDPOINT — One-time ATR% percentile analysis.
     * <p>
     * Computes ATR% percentile distribution from all historical NIFTY50 daily price data.
     * This endpoint will be removed once the analysis is complete.
     * <p>
     * Calculation steps:
     * 1. Fetch all nifty_daily_prices ordered by trade_date ascending
     * 2. Compute True Range for each day (from day 2 onward):
     *    TR = max(high - low, abs(high - prevClose), abs(low - prevClose))
     * 3. Compute 14-day rolling ATR for each day (starting from day 15):
     *    ATR14 = average of TR values from days [N-13, N]
     * 4. Compute ATR% for each day: (ATR14 / close) * 100
     * 5. Collect all ATR% values, sort, and compute percentiles
     * <p>
     * Example: {@code GET /admin/diagnostic/atr-percentiles}
     *
     * @return Mono emitting ResponseEntity with AtrPercentilesResponse containing
     *         percentile distribution (p10, p25, p40, p50, p60, p75, p90) plus min/max/count
     */
    @Operation(summary = "TEMPORARY: Compute ATR% percentile distribution (diagnostic endpoint)")
    @GetMapping(value = "/diagnostic/atr-percentiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> computeAtrPercentiles() {

        log.info("TEMPORARY DIAGNOSTIC: Computing ATR% percentile distribution from historical data");

        return niftyDailyPriceRepository.findAll()
                .collectList()
                .map(prices -> {
                    log.debug("Fetched {} price records for ATR% analysis", prices.size());

                    if (prices.size() < 15) {
                        log.warn("Insufficient data: need at least 15 records, got {}", prices.size());
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("error", "Insufficient data");
                        errorResponse.put("message", "Need at least 15 price records to compute ATR14");
                        errorResponse.put("available", prices.size());
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body((Object) errorResponse);
                    }

                    // Compute True Range for each day (from index 1 onward)
                    double[] trValues = new double[prices.size() - 1];
                    for (int i = 1; i < prices.size(); i++) {
                        NiftyDailyPrice curr = prices.get(i);
                        NiftyDailyPrice prev = prices.get(i - 1);

                        java.math.BigDecimal high = curr.getHigh();
                        java.math.BigDecimal low = curr.getLow();
                        java.math.BigDecimal prevClose = prev.getClose();

                        // TR = max(high - low, abs(high - prevClose), abs(low - prevClose))
                        double tr1 = high.subtract(low).doubleValue();
                        double tr2 = high.subtract(prevClose).abs().doubleValue();
                        double tr3 = low.subtract(prevClose).abs().doubleValue();

                        trValues[i - 1] = Math.max(tr1, Math.max(tr2, tr3));
                    }

                    // Compute ATR% for each day (starting from day 15, index 14)
                    java.util.List<java.math.BigDecimal> atrPercentiles = new java.util.ArrayList<>();

                    for (int dayIndex = 14; dayIndex < prices.size(); dayIndex++) {
                        // Collect TR values for the 14-day window [dayIndex-13, dayIndex]
                        double atrSum = 0;
                        for (int i = dayIndex - 13; i <= dayIndex; i++) {
                            atrSum += trValues[i - 1];  // trValues[i-1] contains TR for day i
                        }
                        double atr14 = atrSum / 14.0;

                        // Compute ATR% = (ATR14 / close) * 100
                        java.math.BigDecimal close = prices.get(dayIndex).getClose();
                        if (close.signum() > 0) {
                            java.math.BigDecimal atrPct = java.math.BigDecimal.valueOf(atr14)
                                    .divide(close, 6, java.math.RoundingMode.HALF_UP)
                                    .multiply(java.math.BigDecimal.valueOf(100))
                                    .setScale(4, java.math.RoundingMode.HALF_UP);
                            atrPercentiles.add(atrPct);
                        }
                    }

                    log.debug("Computed {} ATR% values", atrPercentiles.size());

                    if (atrPercentiles.isEmpty()) {
                        log.warn("No valid ATR% values computed");
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("error", "No valid ATR% values");
                        errorResponse.put("message", "Could not compute ATR% for any day");
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((Object) errorResponse);
                    }

                    // Sort ATR% values for percentile calculation
                    java.util.Collections.sort(atrPercentiles);

                    // Calculate percentiles
                    java.math.BigDecimal min = atrPercentiles.get(0);
                    java.math.BigDecimal max = atrPercentiles.get(atrPercentiles.size() - 1);
                    java.math.BigDecimal p10 = percentile(atrPercentiles, 10);
                    java.math.BigDecimal p25 = percentile(atrPercentiles, 25);
                    java.math.BigDecimal p40 = percentile(atrPercentiles, 40);
                    java.math.BigDecimal p50 = percentile(atrPercentiles, 50);
                    java.math.BigDecimal p60 = percentile(atrPercentiles, 60);
                    java.math.BigDecimal p75 = percentile(atrPercentiles, 75);
                    java.math.BigDecimal p90 = percentile(atrPercentiles, 90);

                    AtrPercentilesResponse response = AtrPercentilesResponse.builder()
                            .count(atrPercentiles.size())
                            .min(min)
                            .p10(p10)
                            .p25(p25)
                            .p40(p40)
                            .p50(p50)
                            .p60(p60)
                            .p75(p75)
                            .p90(p90)
                            .max(max)
                            .build();

                    log.info("ATR% percentile analysis complete: count={}, min={}, p50={}, max={}",
                            response.getCount(), response.getMin(), response.getP50(), response.getMax());

                    return ResponseEntity.ok((Object) response);
                })
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error computing ATR% percentiles: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error computing ATR% percentiles");
                    errorResponse.put("message", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((Object) errorResponse));
                });
    }

    /**
     * Helper method to calculate percentile from a sorted list of BigDecimal values.
     * Uses linear interpolation for non-integer percentile positions.
     *
     * @param sortedValues sorted list of BigDecimal values (must be sorted ascending)
     * @param percentile   percentile to calculate (0-100)
     * @return BigDecimal value at the given percentile
     */
    private java.math.BigDecimal percentile(java.util.List<java.math.BigDecimal> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return java.math.BigDecimal.ZERO;
        }

        double index = (percentile / 100.0) * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper) {
            return sortedValues.get(lower);
        }

        // Linear interpolation between lower and upper values
        java.math.BigDecimal lowerValue = sortedValues.get(lower);
        java.math.BigDecimal upperValue = sortedValues.get(upper);
        double fraction = index - lower;

        java.math.BigDecimal result = lowerValue.multiply(java.math.BigDecimal.valueOf(1 - fraction))
                .add(upperValue.multiply(java.math.BigDecimal.valueOf(fraction)))
                .setScale(4, java.math.RoundingMode.HALF_UP);

        return result;
    }
}
