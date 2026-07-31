package com.trademesh.strategy.expiryday;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketMetrics;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.intelligence.iv.ImpliedVolatilityCalculator;
import com.trademesh.strategy.*;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.enums.*;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Expiry Day Strangle Strategy Implementation.
 * Trades ONLY on Thursday expiry day with zero overnight risk.
 * Sells both call and put options at OI walls for maximum theta decay.
 * <p>
 * Strategy Logic:
 * <p>
 * Step 1: Safety checks (VIX, OI walls, entry time)
 * Step 2: Strike selection from OI analysis
 * Step 3: Build trade legs and calculate credit
 * Step 4: Return StrategyResult with signal
 * <p>
 * Configuration loaded from {@code application.yml} under {@code strategy.expiry-day}.
 */
@Slf4j
@Component
public class ExpiryDayStrategy implements TradingStrategy {

    private final StrategiesConfig strategiesConfig;
    private final MarketMetricsService marketMetricsService;
    private final LotSizeService lotSizeService;

    public ExpiryDayStrategy(StrategiesConfig strategiesConfig, MarketMetricsService marketMetricsService,
                             LotSizeService lotSizeService) {
        this.strategiesConfig = strategiesConfig;
        this.marketMetricsService = marketMetricsService;
        this.lotSizeService = lotSizeService;
    }

    /**
     * Rounds a value up to the nearest strike step.
     *
     * @param value the value to round
     * @param step  the strike step (e.g., 50 for NIFTY)
     * @return the rounded-up value
     */
    private static BigDecimal roundUpToStep(BigDecimal value, int step) {
        return BigDecimal.valueOf(Math.ceil(value.doubleValue() / step) * step);
    }

    /**
     * Rounds a value down to the nearest strike step.
     *
     * @param value the value to round
     * @param step  the strike step (e.g., 50 for NIFTY)
     * @return the rounded-down value
     */
    private static BigDecimal roundDownToStep(BigDecimal value, int step) {
        return BigDecimal.valueOf(Math.floor(value.doubleValue() / step) * step);
    }

    /**
     * Retrieves this strategy's configuration from the unified strategies config.
     *
     * @return the StrategyProperties for EXPIRY_DAY_STRANGLE
     */
    private com.trademesh.strategy.config.StrategyProperties strategyConfig() {
        return strategiesConfig.getStrategy("EXPIRY_DAY_STRANGLE");
    }

    @Override
    public String getName() {
        return "EXPIRY_DAY_STRANGLE";
    }

    @Override
    public boolean isApplicable(MarketCondition condition) {
        return condition == MarketCondition.EXPIRY_DAY;
    }

    @Override
    public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
        log.debug("Evaluating Expiry Day Strangle strategy for {}", intelligence.getSymbol());

        try {
            // ─── Step 1: Safety Checks ────────────────────────────────────────────────
            String safetyCheckReason = performSafetyChecks(intelligence);
            if (safetyCheckReason != null) {
                log.info("Expiry Day strategy skipped: {}", safetyCheckReason);
                return Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason(safetyCheckReason)
                        .build());
            }

            // ─── Step 2: Fetch Market Metrics (ATR) ────────────────────────────────────
            return marketMetricsService.calculateMetrics(intelligence.getSymbol())
                    .flatMap(metrics -> {
                        // ─── Step 3: Strike Selection ──────────────────────────────────────────────
                        StrikeSelection strikeSelection = selectStrikes(intelligence, metrics);
                        if (strikeSelection == null) {
                            String reason = "Unable to select valid strikes from OI analysis";
                            log.info("Expiry Day strategy skipped: {}", reason);
                            return Mono.just(StrategyResult.builder()
                                    .isExecutable(false)
                                    .skipReason(reason)
                                    .build());
                        }

                        // ─── Step 4: Build Trade Legs ─────────────────────────────────────────────
                        TradeLegsResult legsResult = buildTradeLegs(intelligence, strikeSelection, metrics);
                        if (legsResult == null || legsResult.getTotalCredit() < strategyConfig().getMinCredit()) {
                            String reason = "Premium too low — not worth risk";
                            log.info("Expiry Day strategy skipped: {}", reason);
                            return Mono.just(StrategyResult.builder()
                                    .isExecutable(false)
                                    .skipReason(reason)
                                    .build());
                        }

                        // ─── Step 5: Build and Return StrategyResult ───────────────────────────────
                        TradeSignal signal = buildTradeSignal(intelligence, strikeSelection, legsResult);

                        log.info("Expiry Day Strangle signal generated: {} | Credit: {} points",
                                signal.getReason(), legsResult.getTotalCredit());

                        return Mono.just(StrategyResult.builder()
                                .signal(signal)
                                .isExecutable(true)
                                .build());
                    })
                    .onErrorResume(e -> {
                        log.error("Error evaluating Expiry Day strategy", e);
                        return Mono.just(StrategyResult.builder()
                                .isExecutable(false)
                                .skipReason("Strategy evaluation error: " + e.getMessage())
                                .build());
                    });

        } catch (Exception e) {
            log.error("Error evaluating Expiry Day strategy", e);
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("Strategy evaluation error: " + e.getMessage())
                    .build());
        }
    }

    @Override
    public StrategyConfig getConfig() {
        return StrategyConfig.builder()
                .strategyName(getName())
                .maxLotsPerTrade(strategyConfig().getMaxLots())
                .stopLossPercent(strategyConfig().getStopLossPercent())
                .targetProfitPercent(strategyConfig().getTargetProfitPercent())
                .maxVix(strategyConfig().getMaxVix())
                .minVix(strategyConfig().getMinVix())
                .allowedDays(strategyConfig().getAllowedDays().stream()
                        .map(DayOfWeek::valueOf)
                        .collect(Collectors.toList()))
                .applicableConditions(java.util.List.of(MarketCondition.EXPIRY_DAY))
                .isEnabled(strategyConfig().isEnabled())
                .build();
    }

    /**
     * Performs safety checks before generating a signal.
     * Returns skip reason if check fails, null if all checks pass.
     *
     * @param intelligence market intelligence data
     * @return skip reason or null
     */
    private String performSafetyChecks(MarketIntelligence intelligence) {
        // Check 1: VIX threshold
        if (intelligence.getVixData() != null && intelligence.getVixData().getCurrentVix() != null) {
            if (intelligence.getVixData().getCurrentVix() > strategyConfig().getMaxVix()) {
                return String.format("VIX too high for expiry day (%.1f > %.1f)",
                        intelligence.getVixData().getCurrentVix(), strategyConfig().getMaxVix());
            }
        }

        // Check 2: OI walls must be clear
        if (intelligence.getOiAnalysis() == null ||
                intelligence.getOiAnalysis().getMaxCallOIStrike() == null ||
                intelligence.getOiAnalysis().getMaxPutOIStrike() == null) {
            return "OI walls not clear";
        }

        // Check 3: Entry time limit
        LocalTime currentTime = LocalTime.now();
        LocalTime latestTime = LocalTime.parse(strategyConfig().getLatestEntryTime(),
                DateTimeFormatter.ofPattern("HH:mm"));
        if (currentTime.isAfter(latestTime)) {
            return String.format("Too late to enter expiry day trade (current: %s, limit: %s)",
                    currentTime.format(DateTimeFormatter.ofPattern("HH:mm")), strategyConfig().getLatestEntryTime());
        }

        return null; // All checks passed
    }

    /**
     * Selects call and put strikes based on OI analysis and ATR-scaled safety distance.
     * Returns StrikeSelection with validated strikes, or null if invalid.
     *
     * @param intelligence market intelligence data
     * @param metrics      market metrics including ATR14
     * @return StrikeSelection or null
     */
    private StrikeSelection selectStrikes(MarketIntelligence intelligence, MarketMetrics metrics) {
        BigDecimal spotPrice = intelligence.getSpotPrice() != null
                ? new BigDecimal(intelligence.getSpotPrice())
                : null;

        if (spotPrice == null) {
            log.warn("Spot price not available in market intelligence");
            return null;
        }

        // Store original OI walls before any correction
        BigDecimal oiCallWall = intelligence.getOiAnalysis().getMaxCallOIStrike();
        BigDecimal oiPutWall = intelligence.getOiAnalysis().getMaxPutOIStrike();

        BigDecimal callStrike = oiCallWall;
        BigDecimal putStrike = oiPutWall;

        // This check is missing entirely:
        if (callStrike.compareTo(spotPrice) <= 0 || putStrike.compareTo(spotPrice) >= 0) {
            log.warn("OI walls structurally invalid relative to spot — " +
                    "callStrike={}, putStrike={}, spot={}", callStrike, putStrike, spotPrice);
            return null;
        }

        // --- ATR-scaled minimum distance (fail fast if unavailable) ---
        double atr14 = metrics.getAtr14() != null ? metrics.getAtr14().doubleValue() : 0.0;
        double atrMultiplier = strategyConfig().getExtraDouble("min-distance-atr-multiplier", 0.4);

        if (atr14 <= 0) {
            log.warn("ATR14 unavailable, cannot calculate safety distance — skipping");
            return null;
        }

        BigDecimal minDistance = BigDecimal.valueOf(atr14 * atrMultiplier);
        int strikeStep = 50; // NIFTY strike step

        // --- Compute ATR-scaled boundary strikes (round to valid strike step) ---
        BigDecimal boundaryCall = roundUpToStep(spotPrice.add(minDistance), strikeStep);
        BigDecimal boundaryPut = roundDownToStep(spotPrice.subtract(minDistance), strikeStep);

        // --- Apply principled fallback (Option C) — no arbitrary widening ---
        boolean callFallbackUsed = false;
        boolean putFallbackUsed = false;

        if (callStrike.compareTo(boundaryCall) < 0) {
            log.info("Call OI wall {} inside ATR-scaled safety boundary {} " +
                            "(ATR14={}, multiplier={}) — falling back to boundary strike",
                    callStrike, boundaryCall, atr14, atrMultiplier);
            callStrike = boundaryCall;
            callFallbackUsed = true;
        }

        if (putStrike.compareTo(boundaryPut) > 0) {
            log.info("Put OI wall {} inside ATR-scaled safety boundary {} " +
                            "(ATR14={}, multiplier={}) — falling back to boundary strike",
                    putStrike, boundaryPut, atr14, atrMultiplier);
            putStrike = boundaryPut;
            putFallbackUsed = true;
        }

        // --- Final invariant assertion (regression guard, not expected to fire) ---
        if (callStrike.compareTo(boundaryCall) < 0 || putStrike.compareTo(boundaryPut) > 0) {
            log.error("Strike selection invariant violated after correction — " +
                    "this should never happen. callStrike={}, boundaryCall={}, " +
                    "putStrike={}, boundaryPut={}", callStrike, boundaryCall, putStrike, boundaryPut);
            return null;
        }

        return new StrikeSelection(callStrike, putStrike, oiCallWall, oiPutWall,
                callFallbackUsed, putFallbackUsed, atr14, atrMultiplier);
    }

    /**
     * Selects hedge strikes for delta-hedged strangle (Phase 1 shadow logging only).
     * Scans strikes above shortCallStrike and below shortPutStrike to find candidates
     * where both delta <= hedge-target-delta AND distance >= hedge-min-distance-points.
     * <p>
     * For puts, applies Math.abs() to delta before comparison (delta() returns negative
     * for PE, matching IronCondorStrategy's existing convention).
     *
     * @param chain              option chain with all strikes
     * @param shortCallStrike    short call strike (entry strike)
     * @param shortPutStrike     short put strike (entry strike)
     * @param spotPrice          current spot price
     * @param atmIv              ATM implied volatility (%)
     * @param tte                time to expiry in years
     * @param cfg                strategy configuration
     * @return HedgeStrikeSelection with nullable hedge strikes and metrics
     */
    private HedgeStrikeSelection selectHedgeStrikes(
            OptionChain chain, BigDecimal shortCallStrike, BigDecimal shortPutStrike,
            BigDecimal spotPrice, double atmIv, double tte, com.trademesh.strategy.config.StrategyProperties cfg) {

        double hedgeTargetDelta = cfg.getExtraDouble("hedge-target-delta", 0.05);
        double hedgeMinDistance = cfg.getExtraDouble("hedge-min-distance-points", 150.0);

        // ─── CALL SIDE: Find hedge strike ABOVE shortCallStrike ───────────────────────
        BigDecimal callHedgeStrike = null;
        Double callHedgeDelta = null;
        Double callHedgeDistance = null;

        var callCandidates = chain.getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(shortCallStrike) > 0)
                .sorted((a, b) -> a.getStrikePrice().compareTo(b.getStrikePrice()))
                .toList();

        for (OptionStrike candidate : callCandidates) {
            double delta = ImpliedVolatilityCalculator.delta(
                    spotPrice.doubleValue(), candidate.getStrikePrice().doubleValue(),
                    tte, atmIv, "CE");
            double distance = candidate.getStrikePrice().subtract(shortCallStrike).doubleValue();

            if (delta <= hedgeTargetDelta && distance >= hedgeMinDistance) {
                callHedgeStrike = candidate.getStrikePrice();
                callHedgeDelta = delta;
                callHedgeDistance = distance;
                break;
            }
        }

        if (callHedgeStrike == null && !callCandidates.isEmpty()) {
            log.warn("Hedge shadow [{}]: No call hedge strike found satisfying both " +
                    "delta <= {} AND distance >= {}. Candidates scanned: {}",
                    chain.getSymbol(), hedgeTargetDelta, hedgeMinDistance, callCandidates.size());
        }

        // ─── PUT SIDE: Find hedge strike BELOW shortPutStrike ────────────────────────
        BigDecimal putHedgeStrike = null;
        Double putHedgeDelta = null;
        Double putHedgeDistance = null;

        var putCandidates = chain.getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(shortPutStrike) < 0)
                .sorted((a, b) -> b.getStrikePrice().compareTo(a.getStrikePrice()))
                .toList();

        for (OptionStrike candidate : putCandidates) {
            double delta = ImpliedVolatilityCalculator.delta(
                    spotPrice.doubleValue(), candidate.getStrikePrice().doubleValue(),
                    tte, atmIv, "PE");
            // Apply Math.abs() for put delta comparison (delta() returns negative for PE)
            double absDelta = Math.abs(delta);
            double distance = shortPutStrike.subtract(candidate.getStrikePrice()).doubleValue();

            if (absDelta <= hedgeTargetDelta && distance >= hedgeMinDistance) {
                putHedgeStrike = candidate.getStrikePrice();
                putHedgeDelta = delta;
                putHedgeDistance = distance;
                break;
            }
        }

        if (putHedgeStrike == null && !putCandidates.isEmpty()) {
            log.warn("Hedge shadow [{}]: No put hedge strike found satisfying both " +
                    "delta <= {} AND distance >= {}. Candidates scanned: {}",
                    chain.getSymbol(), hedgeTargetDelta, hedgeMinDistance, putCandidates.size());
        }

        return new HedgeStrikeSelection(callHedgeStrike, putHedgeStrike,
                callHedgeDelta, putHedgeDelta, callHedgeDistance, putHedgeDistance);
    }

    /**
     * Builds trade legs from selected strikes and option chain data.
     * Returns TradeLegsResult with legs and total credit, or null if unable to build.
     * <p>
     * Capital-based position sizing: computes maxLossPerLot from totalCredit and
     * stop-loss-percent, then derives lot count via LotSizeService before building legs.
     * <p>
     * Phase 1: Includes shadow logging of hedge strike selection (observation only,
     * no impact on returned TradeLegsResult).
     *
     * @param intelligence    market intelligence data
     * @param strikeSelection selected strikes
     * @param metrics         market metrics for hedge shadow logging
     * @return TradeLegsResult or null
     */
    private TradeLegsResult buildTradeLegs(MarketIntelligence intelligence,
                                           StrikeSelection strikeSelection,
                                           MarketMetrics metrics) {
        if (intelligence.getOptionChain() == null || intelligence.getOptionChain().getStrikes() == null) {
            log.warn("Option chain data not available");
            return null;
        }

        LocalDate expiryDate = intelligence.getOptionChain().getExpiryDate();
        if (expiryDate == null) {
            log.warn("Expiry date not available in option chain");
            return null;
        }

        // Find option strikes for call and put
        OptionStrike callOptionStrike = intelligence.getOptionChain().getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(strikeSelection.getCallStrike()) == 0)
                .findFirst()
                .orElse(null);

        OptionStrike putOptionStrike = intelligence.getOptionChain().getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(strikeSelection.getPutStrike()) == 0)
                .findFirst()
                .orElse(null);

        if (callOptionStrike == null || putOptionStrike == null) {
            log.warn("Unable to find option strikes in chain for call: {} or put: {}",
                    strikeSelection.getCallStrike(), strikeSelection.getPutStrike());
            return null;
        }

        // Extract LTP values
        BigDecimal callLTP = callOptionStrike.getCallLTP();
        BigDecimal putLTP = putOptionStrike.getPutLTP();

        if (callLTP == null || putLTP == null) {
            log.warn("LTP not available for call or put option");
            return null;
        }

        // ─── COMPUTE TOTALCREDIT (used for both naked and hedged sizing) ──────────────
        BigDecimal totalCredit = callLTP.add(putLTP);
        int lotSize = lotSizeService.getLotSize(intelligence.getSymbol());

        // ─── PHASE 2: HEDGE STRIKE SELECTION AND SIZING (MUST HAPPEN FIRST) ─────────
        // ─── FAIL-CLOSED: Metrics required for hedged position ───────────────────────
        if (metrics == null || metrics.getCurrentAtmIv() == null) {
            log.info("Metrics unavailable (metrics: {}, atmIv: {}) — hedged position requires IV data, returning null",
                    metrics != null, metrics != null ? metrics.getCurrentAtmIv() : "N/A");
            return null;
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime marketClose = LocalDateTime.of(now.toLocalDate(), LocalTime.of(15, 30));

        // If market close has already passed today, fail closed
        if (!now.isBefore(marketClose)) {
            log.info("Market close has passed for the day — hedged position requires intraday IV, returning null");
            return null;
        }

        long minutesRemaining = java.time.temporal.ChronoUnit.MINUTES.between(now, marketClose);
        double tte = minutesRemaining / (365.0 * 24 * 60);  // year-fraction
        double atmIv = metrics.getCurrentAtmIv().doubleValue();

        // Call selectHedgeStrikes with real values (use same spot price source as strike selection)
        BigDecimal spotPrice = intelligence.getSpotPrice() != null
                ? new BigDecimal(intelligence.getSpotPrice())
                : null;

        if (spotPrice == null) {
            log.warn("Spot price not available for hedge strike selection");
            return null;
        }

        HedgeStrikeSelection hedgeSelection = selectHedgeStrikes(
                intelligence.getOptionChain(),
                strikeSelection.getCallStrike(),
                strikeSelection.getPutStrike(),
                spotPrice,
                atmIv,
                tte,
                strategyConfig());

        // ─── FAIL-CLOSED: If either hedge strike is null, return null ───────────────────
        if (hedgeSelection.getCallHedgeStrike() == null || hedgeSelection.getPutHedgeStrike() == null) {
            log.info("Hedge strike selection failed (call: {}, put: {}) — returning null",
                    hedgeSelection.getCallHedgeStrike(), hedgeSelection.getPutHedgeStrike());
            return null;
        }

        // Fetch hedge strike LTPs (reuse exact pattern from Phase 1 shadow logging)
        BigDecimal callHedgeLtp = intelligence.getOptionChain().getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(hedgeSelection.getCallHedgeStrike()) == 0)
                .map(OptionStrike::getCallLTP)
                .findFirst()
                .orElse(null);

        BigDecimal putHedgeLtp = intelligence.getOptionChain().getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(hedgeSelection.getPutHedgeStrike()) == 0)
                .map(OptionStrike::getPutLTP)
                .findFirst()
                .orElse(null);

        if (callHedgeLtp == null || putHedgeLtp == null) {
            log.warn("Unable to fetch hedge LTPs (call: {}, put: {}) — returning null",
                    callHedgeLtp, putHedgeLtp);
            return null;
        }

        // ─── COMPUTE HEDGED POSITION SIZING (SINGLE LOT COUNT FOR ALL 4 LEGS) ────────
        // netCredit = totalCredit − callHedgeLtp − putHedgeLtp
        BigDecimal netCredit = totalCredit.subtract(callHedgeLtp).subtract(putHedgeLtp);

        // maxLossPerLot = max(callHedgeStrike − shortCallStrike, shortPutStrike − putHedgeStrike) × lotSize − netCredit × lotSize
        BigDecimal callSideDistance = hedgeSelection.getCallHedgeStrike().subtract(strikeSelection.getCallStrike());
        BigDecimal putSideDistance = strikeSelection.getPutStrike().subtract(hedgeSelection.getPutHedgeStrike());
        BigDecimal maxDistance = callSideDistance.compareTo(putSideDistance) > 0 ? callSideDistance : putSideDistance;

        BigDecimal maxLossPerLotHedged = maxDistance.multiply(BigDecimal.valueOf(lotSize))
                .subtract(netCredit.multiply(BigDecimal.valueOf(lotSize)));

        // Compute lots using capital-based sizing — this is the SINGLE lot count for all 4 legs
        int computedLots = lotSizeService.getPositionSize(maxLossPerLotHedged);
        int lots = Math.min(computedLots, strategyConfig().getMaxLots());

        log.debug("ExpiryDayStrangle hedged position sizing: netCredit={}, maxDistance={}, " +
                "maxLossPerLotHedged={}, computedLots={}, finalLots={}",
                netCredit, maxDistance, maxLossPerLotHedged, computedLots, lots);

        // ─── BUILD ALL 4 LEGS WITH SINGLE LOT COUNT ──────────────────────────────────
        TradeLeg callLeg = TradeLeg.builder()
                .instrument(buildInstrumentName(intelligence.getSymbol(), expiryDate,
                        strikeSelection.getCallStrike(), OptionType.CE))
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(strikeSelection.getCallStrike())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(callLTP)
                .legType(LegType.ENTRY)
                .build();

        TradeLeg putLeg = TradeLeg.builder()
                .instrument(buildInstrumentName(intelligence.getSymbol(), expiryDate,
                        strikeSelection.getPutStrike(), OptionType.PE))
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(strikeSelection.getPutStrike())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(putLTP)
                .legType(LegType.ENTRY)
                .build();

        TradeLeg callHedgeLeg = TradeLeg.builder()
                .instrument(buildInstrumentName(intelligence.getSymbol(), expiryDate,
                        hedgeSelection.getCallHedgeStrike(), OptionType.CE))
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(hedgeSelection.getCallHedgeStrike())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(callHedgeLtp)
                .legType(LegType.ENTRY)
                .build();

        TradeLeg putHedgeLeg = TradeLeg.builder()
                .instrument(buildInstrumentName(intelligence.getSymbol(), expiryDate,
                        hedgeSelection.getPutHedgeStrike(), OptionType.PE))
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(hedgeSelection.getPutHedgeStrike())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(putHedgeLtp)
                .legType(LegType.ENTRY)
                .build();

        Double totalDebit = callHedgeLtp.add(putHedgeLtp).doubleValue();

        log.info("Expiry Day Strangle trade legs built: callStrike={} ltp={}, putStrike={} ltp={}, " +
                "callHedgeStrike={} ltp={}, putHedgeStrike={} ltp={}, " +
                "netCredit={}, totalDebit={}, lots={}",
                strikeSelection.getCallStrike(), callLTP,
                strikeSelection.getPutStrike(), putLTP,
                hedgeSelection.getCallHedgeStrike(), callHedgeLtp,
                hedgeSelection.getPutHedgeStrike(), putHedgeLtp,
                netCredit, totalDebit, lots);

        return new TradeLegsResult(callLeg, putLeg, totalCredit.doubleValue(),
                callHedgeLeg, putHedgeLeg, totalDebit);
    }

    /**
     * Builds a complete TradeSignal from strategy evaluation.
     * Returns TradeSignal ready for execution.
     *
     * @param intelligence    market intelligence data
     * @param strikeSelection selected strikes
     * @param legsResult      trade legs and credit
     * @return TradeSignal ready for execution
     */
     private TradeSignal buildTradeSignal(MarketIntelligence intelligence,
                                          StrikeSelection strikeSelection,
                                          TradeLegsResult legsResult) {
         String reason = buildReasonString(strikeSelection, legsResult);
 
         // Calculate stop loss amount for strangle
         Double stopLossAmount = legsResult.getTotalCredit() * strategyConfig().getStopLossPercent() / 100;
 
         // Determine confidence based on credit received
         Confidence confidence = legsResult.getTotalCredit() >= strategyConfig().getMinCredit() * 2
                 ? Confidence.HIGH
                 : Confidence.MEDIUM;
 
         // Phase 1: Compute risk snapshot fields using netCredit (hedged net premium)
         BigDecimal totalCredit = BigDecimal.valueOf(legsResult.getTotalCredit());
         BigDecimal totalDebit = BigDecimal.valueOf(legsResult.getTotalDebit() != null ? legsResult.getTotalDebit() : 0.0);
         BigDecimal netCredit = totalCredit.subtract(totalDebit);
         int lotSize = lotSizeService.getLotSize(intelligence.getSymbol());
         BigDecimal stopDistancePoints = netCredit.multiply(BigDecimal.valueOf(strategyConfig().getStopLossPercent() / 100.0));
         BigDecimal targetThreshold = netCredit.multiply(BigDecimal.valueOf(strategyConfig().getTargetProfitPercent() / 100.0));
         BigDecimal plannedRiskPerLot = stopDistancePoints.multiply(BigDecimal.valueOf(lotSize));

        return TradeSignal.builder()
                .signalId(UUID.randomUUID())
                .symbol(intelligence.getSymbol())
                .strategyName(getName())
                .signalType(SignalType.ENTER)
                .legs(java.util.List.of(legsResult.getCallLeg(), legsResult.getPutLeg(),
                        legsResult.getCallHedgeLeg(), legsResult.getPutHedgeLeg()))
                .expectedCredit(legsResult.getTotalCredit())
                .maxLoss(stopLossAmount)
                .confidence(confidence)
                .reason(reason)
                .stopDistancePoints(stopDistancePoints)
                .targetThreshold(targetThreshold)
                .plannedRiskPerLot(plannedRiskPerLot)
                .atrPercentile(null)
                .confidenceScore(null)
                .build();
    }

    /**
     * Builds the reason string for the trade signal, accurately reflecting
     * whether strikes were selected from OI walls or ATR boundaries.
     *
     * @param strikeSelection selected strikes with fallback tracking
     * @param legsResult      trade legs and credit
     * @return formatted reason string for Telegram alert
     */
    private String buildReasonString(StrikeSelection strikeSelection, TradeLegsResult legsResult) {
        int callStrike = strikeSelection.getCallStrike().intValue();
        int putStrike = strikeSelection.getPutStrike().intValue();
        double credit = legsResult.getTotalCredit();
        boolean callFallback = strikeSelection.isCallFallbackUsed();
        boolean putFallback = strikeSelection.isPutFallbackUsed();

        if (!callFallback && !putFallback) {
            // Neither fallback used — both from OI walls
            return String.format(
                    "Expiry day strangle: Sell %dCE + %dPE. Credit: %.1f pts. OI resistance: %d. OI support: %d",
                    callStrike, putStrike, credit, callStrike, putStrike
            );
        } else if (callFallback && !putFallback) {
            // Call fallback used, put from OI wall
            int oiCall = strikeSelection.getOiCallWall().intValue();
            double atr14 = strikeSelection.getAtr14();
            double mult = strikeSelection.getAtrMultiplier();
            return String.format(
                    "Expiry day strangle: Sell %dCE + %dPE. Credit: %.1f pts. " +
                            "CE: ATR boundary %d (OI wall: %d, ATR14: %.2f, mult: %.2f). OI support: %d",
                    callStrike, putStrike, credit, callStrike, oiCall, atr14, mult, putStrike
            );
        } else if (!callFallback && putFallback) {
            // Put fallback used, call from OI wall
            int oiPut = strikeSelection.getOiPutWall().intValue();
            double atr14 = strikeSelection.getAtr14();
            double mult = strikeSelection.getAtrMultiplier();
            return String.format(
                    "Expiry day strangle: Sell %dCE + %dPE. Credit: %.1f pts. " +
                            "OI resistance: %d. PE: ATR boundary %d (OI wall: %d, ATR14: %.2f, mult: %.2f)",
                    callStrike, putStrike, credit, callStrike, putStrike, oiPut, atr14, mult
            );
        } else {
            // Both fallbacks used
            int oiCall = strikeSelection.getOiCallWall().intValue();
            int oiPut = strikeSelection.getOiPutWall().intValue();
            double atr14 = strikeSelection.getAtr14();
            double mult = strikeSelection.getAtrMultiplier();
            return String.format(
                    "Expiry day strangle: Sell %dCE + %dPE. Credit: %.1f pts. " +
                            "Both strikes at ATR boundary (OI walls: %dCE / %dPE, ATR14: %.2f, mult: %.2f)",
                    callStrike, putStrike, credit, oiCall, oiPut, atr14, mult
            );
        }
    }

    /**
     * Builds instrument name in Zerodha format.
     * Format: SYMBOL + EXPIRY(DDMMMYY) + STRIKE + OPTION_TYPE
     * Example: NIFTY26JUN22300CE
     */
    private String buildInstrumentName(String symbol, LocalDate expiryDate,
                                       BigDecimal strike, OptionType optionType) {
        String kiteSymbol = toKiteSymbol(symbol);
        String expiryStr = expiryDate.format(DateTimeFormatter.ofPattern("ddMMMyy")).toUpperCase();
        return String.format("%s%s%d%s", kiteSymbol, expiryStr, strike.intValue(), optionType.name());
    }

    /**
     * Converts symbol to Kite format.
     * NIFTYBANK → BANKNIFTY
     * NIFTY50 → NIFTY
     * Others → unchanged
     *
     * @param symbol the symbol to convert
     * @return the Kite-formatted symbol
     */
    private String toKiteSymbol(String symbol) {
        return switch (symbol) {
            case "NIFTYBANK" -> "BANKNIFTY";
            case "NIFTY50" -> "NIFTY";
            default -> symbol;
        };
    }

    /**
     * Helper class to hold selected strikes and fallback tracking.
     * Tracks both the final selected strikes and the original OI walls,
     * plus whether ATR boundary fallback was used for each leg.
     */
    static class StrikeSelection {
        private final BigDecimal callStrike;      // final selected strike
        private final BigDecimal putStrike;       // final selected strike
        private final BigDecimal oiCallWall;      // original maxCallOIStrike
        private final BigDecimal oiPutWall;       // original maxPutOIStrike
        private final boolean callFallbackUsed;   // true if ATR boundary was used
        private final boolean putFallbackUsed;    // true if ATR boundary was used
        private final double atr14;               // ATR14 value used for boundary calculation
        private final double atrMultiplier;       // multiplier used for boundary calculation

        StrikeSelection(BigDecimal callStrike, BigDecimal putStrike,
                        BigDecimal oiCallWall, BigDecimal oiPutWall,
                        boolean callFallbackUsed, boolean putFallbackUsed,
                        double atr14, double atrMultiplier) {
            this.callStrike = callStrike;
            this.putStrike = putStrike;
            this.oiCallWall = oiCallWall;
            this.oiPutWall = oiPutWall;
            this.callFallbackUsed = callFallbackUsed;
            this.putFallbackUsed = putFallbackUsed;
            this.atr14 = atr14;
            this.atrMultiplier = atrMultiplier;
        }

        BigDecimal getCallStrike() {
            return callStrike;
        }

        BigDecimal getPutStrike() {
            return putStrike;
        }

        BigDecimal getOiCallWall() {
            return oiCallWall;
        }

        BigDecimal getOiPutWall() {
            return oiPutWall;
        }

        boolean isCallFallbackUsed() {
            return callFallbackUsed;
        }

        boolean isPutFallbackUsed() {
            return putFallbackUsed;
        }

        double getAtr14() {
            return atr14;
        }

        double getAtrMultiplier() {
            return atrMultiplier;
        }
    }

    /**
     * Helper class to hold hedge strike selection results.
     * Tracks selected hedge strikes and their delta/distance metrics.
     */
    static class HedgeStrikeSelection {
        private final BigDecimal callHedgeStrike;      // nullable
        private final BigDecimal putHedgeStrike;       // nullable
        private final Double callHedgeDelta;           // nullable
        private final Double putHedgeDelta;            // nullable
        private final Double callHedgeDistance;        // nullable
        private final Double putHedgeDistance;         // nullable

        HedgeStrikeSelection(BigDecimal callHedgeStrike, BigDecimal putHedgeStrike,
                             Double callHedgeDelta, Double putHedgeDelta,
                             Double callHedgeDistance, Double putHedgeDistance) {
            this.callHedgeStrike = callHedgeStrike;
            this.putHedgeStrike = putHedgeStrike;
            this.callHedgeDelta = callHedgeDelta;
            this.putHedgeDelta = putHedgeDelta;
            this.callHedgeDistance = callHedgeDistance;
            this.putHedgeDistance = putHedgeDistance;
        }

        BigDecimal getCallHedgeStrike() {
            return callHedgeStrike;
        }

        BigDecimal getPutHedgeStrike() {
            return putHedgeStrike;
        }

        Double getCallHedgeDelta() {
            return callHedgeDelta;
        }

        Double getPutHedgeDelta() {
            return putHedgeDelta;
        }

        Double getCallHedgeDistance() {
            return callHedgeDistance;
        }

        Double getPutHedgeDistance() {
            return putHedgeDistance;
        }
    }

    /**
     * Helper class to hold trade legs and credit information.
     * Phase 2: Extended to include hedge legs and total debit cost.
     */
    static class TradeLegsResult {
        private final TradeLeg callLeg;
        private final TradeLeg putLeg;
        private final Double totalCredit;
        private final TradeLeg callHedgeLeg;
        private final TradeLeg putHedgeLeg;
        private final Double totalDebit;

        // Original 3-field constructor (for backward compatibility if needed)
        TradeLegsResult(TradeLeg callLeg, TradeLeg putLeg, Double totalCredit) {
            this.callLeg = callLeg;
            this.putLeg = putLeg;
            this.totalCredit = totalCredit;
            this.callHedgeLeg = null;
            this.putHedgeLeg = null;
            this.totalDebit = null;
        }

        // New 6-field constructor (Phase 2: with hedge legs)
        TradeLegsResult(TradeLeg callLeg, TradeLeg putLeg, Double totalCredit,
                        TradeLeg callHedgeLeg, TradeLeg putHedgeLeg, Double totalDebit) {
            this.callLeg = callLeg;
            this.putLeg = putLeg;
            this.totalCredit = totalCredit;
            this.callHedgeLeg = callHedgeLeg;
            this.putHedgeLeg = putHedgeLeg;
            this.totalDebit = totalDebit;
        }

        TradeLeg getCallLeg() {
            return callLeg;
        }

        TradeLeg getPutLeg() {
            return putLeg;
        }

        Double getTotalCredit() {
            return totalCredit;
        }

        TradeLeg getCallHedgeLeg() {
            return callHedgeLeg;
        }

        TradeLeg getPutHedgeLeg() {
            return putHedgeLeg;
        }

        Double getTotalDebit() {
            return totalDebit;
        }
    }
}
