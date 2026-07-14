package com.trademesh.strategy.expiryday;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketMetrics;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.strategy.*;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.enums.*;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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
                        TradeLegsResult legsResult = buildTradeLegs(intelligence, strikeSelection);
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
     * Builds trade legs from selected strikes and option chain data.
     * Returns TradeLegsResult with legs and total credit, or null if unable to build.
     * <p>
     * Capital-based position sizing: computes maxLossPerLot from totalCredit and
     * stop-loss-percent, then derives lot count via LotSizeService before building legs.
     *
     * @param intelligence    market intelligence data
     * @param strikeSelection selected strikes
     * @return TradeLegsResult or null
     */
    private TradeLegsResult buildTradeLegs(MarketIntelligence intelligence,
                                           StrikeSelection strikeSelection) {
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

        // ─── CAPITAL-BASED POSITION SIZING ───────────────────────────────────────────
        // Compute totalCredit FIRST (before building legs)
        BigDecimal totalCredit = callLTP.add(putLTP);

        // Compute maxLossPerLot in rupees: totalCredit × (stopLossPercent / 100) × lotSize
        double stopLossPercent = strategyConfig().getStopLossPercent();
        int lotSize = lotSizeService.getLotSize(intelligence.getSymbol());
        BigDecimal maxLossPerLot = totalCredit
                .multiply(BigDecimal.valueOf(stopLossPercent / 100.0))
                .multiply(BigDecimal.valueOf(lotSize));

        // Compute lots using capital-based sizing (1-arg overload, no VIX/confidence scaling)
        int computedLots = lotSizeService.getPositionSize(maxLossPerLot);
        int lots = Math.min(computedLots, strategyConfig().getMaxLots());

        log.debug("ExpiryDayStrangle position sizing: totalCredit={}, stopLossPercent={}, " +
                        "lotSize={}, maxLossPerLot={}, computedLots={}, finalLots={}",
                totalCredit, stopLossPercent, lotSize, maxLossPerLot,
                computedLots, lots);

        // ─── BUILD LEGS WITH COMPUTED LOT COUNT ──────────────────────────────────────
        // Build legs
        TradeLeg callLeg = TradeLeg.builder()
                .instrument(buildInstrumentName(intelligence.getSymbol(), expiryDate,
                        strikeSelection.getCallStrike(), OptionType.CE))
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(strikeSelection.getCallStrike().doubleValue())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(callLTP.doubleValue())
                .legType(LegType.ENTRY)
                .build();

        TradeLeg putLeg = TradeLeg.builder()
                .instrument(buildInstrumentName(intelligence.getSymbol(), expiryDate,
                        strikeSelection.getPutStrike(), OptionType.PE))
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(strikeSelection.getPutStrike().doubleValue())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(putLTP.doubleValue())
                .legType(LegType.ENTRY)
                .build();

        return new TradeLegsResult(callLeg, putLeg, totalCredit.doubleValue());
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

        return TradeSignal.builder()
                .signalId(UUID.randomUUID())
                .symbol(intelligence.getSymbol())
                .strategyName(getName())
                .signalType(SignalType.ENTER)
                .legs(java.util.List.of(legsResult.getCallLeg(), legsResult.getPutLeg()))
                .expectedCredit(legsResult.getTotalCredit())
                .maxLoss(stopLossAmount)
                .confidence(confidence)
                .reason(reason)
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
    private static class StrikeSelection {
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
     * Helper class to hold trade legs and credit information.
     */
    private static class TradeLegsResult {
        private final TradeLeg callLeg;
        private final TradeLeg putLeg;
        private final Double totalCredit;

        TradeLegsResult(TradeLeg callLeg, TradeLeg putLeg, Double totalCredit) {
            this.callLeg = callLeg;
            this.putLeg = putLeg;
            this.totalCredit = totalCredit;
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
    }
}
