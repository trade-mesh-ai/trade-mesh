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
     * TEMPORARY DIAGNOSTIC ENDPOINT — Three-way ATR percentile analysis.
     * <p>
     * Computes three separate ATR percentile calculations to isolate the cause of
     * the atrPercentile discrepancy between the diagnostic endpoint and production.
     * This endpoint will be removed once the analysis is complete.
     * <p>
     * Three calculations:
     * 1. fullHistorySmoothedVsSmoothed: Full history, ATR14 vs ATR14 (baseline)
     * - Ranks each day's ATR14 against other days' ATR14 values
     * 2. fullHistorySmoothedVsRaw: Full history, ATR14 vs raw TR (isolates comparison method)
     * - Compares today's smoothed ATR14 against distribution of raw TR values
     * 3. last60DaysSmoothedVsRaw: 60-day window, ATR14 vs raw TR (replicates production)
     * - Exactly replicates MarketMetricsService.calculateATRPercentile()
     * <p>
     * Example: {@code GET /admin/diagnostic/atr-percentiles}
     *
     * @return Mono emitting ResponseEntity with AtrPercentilesResponse containing
     * all three percentile distributions for comparison
     */
    @Operation(summary = "TEMPORARY: Compute three ATR percentile distributions (diagnostic endpoint)")
    @GetMapping(value = "/diagnostic/atr-percentiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> computeAtrPercentiles() {

        log.info("TEMPORARY DIAGNOSTIC: Computing three ATR percentile distributions for hypothesis testing");

        return niftyDailyPriceRepository.findAll()
                .collectList()
                .map(prices -> {
                    log.debug("Fetched {} price records for ATR percentile analysis", prices.size());

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

                    // ===== CALCULATION 1: Full History, Smoothed vs Smoothed (BASELINE) =====
                    log.debug("Computing calculation 1: full history, smoothed-vs-smoothed");
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

                    log.debug("Computed {} ATR% values for calculation 1", atrPercentiles.size());

                    if (atrPercentiles.isEmpty()) {
                        log.warn("No valid ATR% values computed");
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("error", "No valid ATR% values");
                        errorResponse.put("message", "Could not compute ATR% for any day");
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body((Object) errorResponse);
                    }

                    // Sort ATR% values for percentile calculation
                    java.util.Collections.sort(atrPercentiles);

                    // Calculate percentiles for calculation 1
                    java.math.BigDecimal calc1_min = atrPercentiles.get(0);
                    java.math.BigDecimal calc1_max = atrPercentiles.get(atrPercentiles.size() - 1);
                    java.math.BigDecimal calc1_p10 = percentile(atrPercentiles, 10);
                    java.math.BigDecimal calc1_p25 = percentile(atrPercentiles, 25);
                    java.math.BigDecimal calc1_p40 = percentile(atrPercentiles, 40);
                    java.math.BigDecimal calc1_p50 = percentile(atrPercentiles, 50);
                    java.math.BigDecimal calc1_p60 = percentile(atrPercentiles, 60);
                    java.math.BigDecimal calc1_p75 = percentile(atrPercentiles, 75);
                    java.math.BigDecimal calc1_p90 = percentile(atrPercentiles, 90);

                    log.info("Calculation 1 (full history, smoothed-vs-smoothed) complete: count={}, p50={}", atrPercentiles.size(), calc1_p50);

                    // ===== CALCULATION 2: Full History, Smoothed vs Raw (ISOLATES COMPARISON METHOD) =====
                    log.debug("Computing calculation 2: full history, smoothed-vs-raw");
                    java.util.List<java.math.BigDecimal> calc2_trList = new java.util.ArrayList<>();
                    for (double tr : trValues) {
                        calc2_trList.add(java.math.BigDecimal.valueOf(tr));
                    }
                    java.util.Collections.sort(calc2_trList);

                    // Get today's ATR14 (last day in the full history)
                    int lastDayIndex = prices.size() - 1;
                    double todayAtrSum = 0;
                    for (int i = lastDayIndex - 13; i <= lastDayIndex; i++) {
                        todayAtrSum += trValues[i - 1];
                    }
                    double todayAtr14 = todayAtrSum / 14.0;

                    // Count how many raw TR values are less than today's ATR14
                    int countLessThanATR = 0;
                    for (double tr : trValues) {
                        if (tr < todayAtr14) {
                            countLessThanATR++;
                        }
                    }
                    double calc2_percentile = (double) countLessThanATR / trValues.length * 100;

                    log.debug("Calculation 2: today's ATR14={}, count of TR < ATR14={}, percentile={}", todayAtr14, countLessThanATR, calc2_percentile);

                    // Calculate percentiles for calculation 2 (from raw TR distribution)
                    java.math.BigDecimal calc2_min = calc2_trList.get(0);
                    java.math.BigDecimal calc2_max = calc2_trList.get(calc2_trList.size() - 1);
                    java.math.BigDecimal calc2_p10 = percentile(calc2_trList, 10);
                    java.math.BigDecimal calc2_p25 = percentile(calc2_trList, 25);
                    java.math.BigDecimal calc2_p40 = percentile(calc2_trList, 40);
                    java.math.BigDecimal calc2_p50 = percentile(calc2_trList, 50);
                    java.math.BigDecimal calc2_p60 = percentile(calc2_trList, 60);
                    java.math.BigDecimal calc2_p75 = percentile(calc2_trList, 75);
                    java.math.BigDecimal calc2_p90 = percentile(calc2_trList, 90);

                    log.info("Calculation 2 (full history, smoothed-vs-raw) complete: count={}, percentile={}", calc2_trList.size(), calc2_percentile);

                    // ===== CALCULATION 3: Last 60 Days, Smoothed vs Raw (REPLICATES PRODUCTION) =====
                    log.debug("Computing calculation 3: last 60 days, smoothed-vs-raw (replicates production)");
                    int startIndex = Math.max(0, prices.size() - 60);
                    java.util.List<NiftyDailyPrice> last60 = prices.subList(startIndex, prices.size());

                    if (last60.size() < 15) {
                        log.warn("Insufficient data for 60-day calculation: only {} records available", last60.size());
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("error", "Insufficient data for 60-day calculation");
                        errorResponse.put("message", "Need at least 15 price records");
                        errorResponse.put("available", last60.size());
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body((Object) errorResponse);
                    }

                    // Compute True Range for last 60 days
                    double[] calc3_trValues = new double[last60.size() - 1];
                    for (int i = 1; i < last60.size(); i++) {
                        NiftyDailyPrice curr = last60.get(i);
                        NiftyDailyPrice prev = last60.get(i - 1);

                        java.math.BigDecimal high = curr.getHigh();
                        java.math.BigDecimal low = curr.getLow();
                        java.math.BigDecimal prevClose = prev.getClose();

                        double tr1 = high.subtract(low).doubleValue();
                        double tr2 = high.subtract(prevClose).abs().doubleValue();
                        double tr3 = low.subtract(prevClose).abs().doubleValue();

                        calc3_trValues[i - 1] = Math.max(tr1, Math.max(tr2, tr3));
                    }

                    // Get today's ATR14 from last 60 days
                    int calc3_lastDayIndex = last60.size() - 1;
                    double calc3_todayAtrSum = 0;
                    for (int i = calc3_lastDayIndex - 13; i <= calc3_lastDayIndex; i++) {
                        calc3_todayAtrSum += calc3_trValues[i - 1];
                    }
                    double calc3_todayAtr14 = calc3_todayAtrSum / 14.0;

                    // Count how many raw TR values are less than today's ATR14
                    int calc3_countLessThanATR = 0;
                    for (double tr : calc3_trValues) {
                        if (tr < calc3_todayAtr14) {
                            calc3_countLessThanATR++;
                        }
                    }
                    double calc3_percentile = (double) calc3_countLessThanATR / calc3_trValues.length * 100;

                    log.debug("Calculation 3: today's ATR14={}, count of TR < ATR14={}, percentile={}", calc3_todayAtr14, calc3_countLessThanATR, calc3_percentile);

                    // Calculate percentiles for calculation 3 (from raw TR distribution)
                    java.util.List<java.math.BigDecimal> calc3_trList = new java.util.ArrayList<>();
                    for (double tr : calc3_trValues) {
                        calc3_trList.add(java.math.BigDecimal.valueOf(tr));
                    }
                    java.util.Collections.sort(calc3_trList);

                    java.math.BigDecimal calc3_min = calc3_trList.get(0);
                    java.math.BigDecimal calc3_max = calc3_trList.get(calc3_trList.size() - 1);
                    java.math.BigDecimal calc3_p10 = percentile(calc3_trList, 10);
                    java.math.BigDecimal calc3_p25 = percentile(calc3_trList, 25);
                    java.math.BigDecimal calc3_p40 = percentile(calc3_trList, 40);
                    java.math.BigDecimal calc3_p50 = percentile(calc3_trList, 50);
                    java.math.BigDecimal calc3_p60 = percentile(calc3_trList, 60);
                    java.math.BigDecimal calc3_p75 = percentile(calc3_trList, 75);
                    java.math.BigDecimal calc3_p90 = percentile(calc3_trList, 90);

                    log.info("Calculation 3 (last 60 days, smoothed-vs-raw) complete: count={}, percentile={}", calc3_trList.size(), calc3_percentile);

                    // ===== BUILD RESPONSE WITH ALL THREE CALCULATIONS =====
                    AtrPercentilesResponse response = AtrPercentilesResponse.builder()
                            // Calculation 1: Full History, Smoothed vs Smoothed
                            .fullHistorySmoothedVsSmoothed_count(atrPercentiles.size())
                            .fullHistorySmoothedVsSmoothed_min(calc1_min)
                            .fullHistorySmoothedVsSmoothed_p10(calc1_p10)
                            .fullHistorySmoothedVsSmoothed_p25(calc1_p25)
                            .fullHistorySmoothedVsSmoothed_p40(calc1_p40)
                            .fullHistorySmoothedVsSmoothed_p50(calc1_p50)
                            .fullHistorySmoothedVsSmoothed_p60(calc1_p60)
                            .fullHistorySmoothedVsSmoothed_p75(calc1_p75)
                            .fullHistorySmoothedVsSmoothed_p90(calc1_p90)
                            .fullHistorySmoothedVsSmoothed_max(calc1_max)
                            // Calculation 2: Full History, Smoothed vs Raw
                            .fullHistorySmoothedVsRaw_count(calc2_trList.size())
                            .fullHistorySmoothedVsRaw_min(calc2_min)
                            .fullHistorySmoothedVsRaw_p10(calc2_p10)
                            .fullHistorySmoothedVsRaw_p25(calc2_p25)
                            .fullHistorySmoothedVsRaw_p40(calc2_p40)
                            .fullHistorySmoothedVsRaw_p50(calc2_p50)
                            .fullHistorySmoothedVsRaw_p60(calc2_p60)
                            .fullHistorySmoothedVsRaw_p75(calc2_p75)
                            .fullHistorySmoothedVsRaw_p90(calc2_p90)
                            .fullHistorySmoothedVsRaw_max(calc2_max)
                            // Calculation 3: Last 60 Days, Smoothed vs Raw
                            .last60DaysSmoothedVsRaw_count(calc3_trList.size())
                            .last60DaysSmoothedVsRaw_min(calc3_min)
                            .last60DaysSmoothedVsRaw_p10(calc3_p10)
                            .last60DaysSmoothedVsRaw_p25(calc3_p25)
                            .last60DaysSmoothedVsRaw_p40(calc3_p40)
                            .last60DaysSmoothedVsRaw_p50(calc3_p50)
                            .last60DaysSmoothedVsRaw_p60(calc3_p60)
                            .last60DaysSmoothedVsRaw_p75(calc3_p75)
                            .last60DaysSmoothedVsRaw_p90(calc3_p90)
                            .last60DaysSmoothedVsRaw_max(calc3_max)
                            .build();

                    log.info("All three ATR percentile calculations complete. Calc1 p50={}, Calc2 percentile={}, Calc3 percentile={}",
                            calc1_p50, calc2_percentile, calc3_percentile);

                    return ResponseEntity.ok((Object) response);
                })
                .<ResponseEntity<?>>onErrorResume(e -> {
                    log.error("Error computing ATR percentiles: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error computing ATR percentiles");
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
