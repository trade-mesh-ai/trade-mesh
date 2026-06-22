package com.trademesh.strategy.expiryday;

import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.strategy.*;
import com.trademesh.strategy.enums.*;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Expiry Day Strangle Strategy Implementation.
 *
 * <p>Trades ONLY on Thursday expiry day with zero overnight risk.
 * Sells both call and put options at OI walls for maximum theta decay.
 *
 * <p>Strategy Logic:
 * <ul>
 *   <li>Step 1: Safety checks (VIX, OI walls, entry time)</li>
 *   <li>Step 2: Strike selection from OI analysis</li>
 *   <li>Step 3: Build trade legs and calculate credit</li>
 *   <li>Step 4: Return StrategyResult with signal</li>
 * </ul>
 *
 * <p>Configuration loaded from {@code application.yml} under {@code strategy.expiry-day}.
 */
@Slf4j
@Component
public class ExpiryDayStrategy implements TradingStrategy {

    private final ExpiryDayConfig config;

    public ExpiryDayStrategy(ExpiryDayConfig config) {
        this.config = config;
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

            // ─── Step 2: Strike Selection ──────────────────────────────────────────────
            StrikeSelection strikeSelection = selectStrikes(intelligence);
            if (strikeSelection == null) {
                String reason = "Unable to select valid strikes from OI analysis";
                log.info("Expiry Day strategy skipped: {}", reason);
                return Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason(reason)
                        .build());
            }

            // ─── Step 3: Build Trade Legs ─────────────────────────────────────────────
            TradeLegsResult legsResult = buildTradeLegs(intelligence, strikeSelection);
            if (legsResult == null || legsResult.getTotalCredit() < config.getMinCredit()) {
                String reason = "Premium too low — not worth risk";
                log.info("Expiry Day strategy skipped: {}", reason);
                return Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason(reason)
                        .build());
            }

            // ─── Step 4: Build and Return StrategyResult ───────────────────────────────
            TradeSignal signal = buildTradeSignal(intelligence, strikeSelection, legsResult);

            log.info("Expiry Day Strangle signal generated: {} | Credit: {} points",
                    signal.getReason(), legsResult.getTotalCredit());

            return Mono.just(StrategyResult.builder()
                    .signal(signal)
                    .isExecutable(true)
                    .build());

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
                .maxLotsPerTrade(config.getMaxLots())
                .stopLossPercent(config.getStopLossPercent())
                .targetProfitPercent(config.getTargetProfitPercent())
                .maxVix(config.getMaxVix())
                .isEnabled(true)
                .build();
    }

    /**
     * Performs safety checks before generating a signal.
     *
     * @param intelligence market intelligence data
     * @return skip reason if check fails, null if all checks pass
     */
    private String performSafetyChecks(MarketIntelligence intelligence) {
        // Check 1: VIX threshold
        if (intelligence.getVixData() != null && intelligence.getVixData().getCurrentVix() != null) {
            if (intelligence.getVixData().getCurrentVix() > config.getMaxVix()) {
                return String.format("VIX too high for expiry day (%.1f > %.1f)",
                        intelligence.getVixData().getCurrentVix(), config.getMaxVix());
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
        LocalTime latestTime = LocalTime.parse(config.getLatestEntryTime(),
                DateTimeFormatter.ofPattern("HH:mm"));
        if (currentTime.isAfter(latestTime)) {
            return String.format("Too late to enter expiry day trade (current: %s, limit: %s)",
                    currentTime.format(DateTimeFormatter.ofPattern("HH:mm")), config.getLatestEntryTime());
        }

        return null; // All checks passed
    }

    /**
     * Selects call and put strikes based on OI analysis.
     *
     * @param intelligence market intelligence data
     * @return StrikeSelection with validated strikes, or null if invalid
     */
    private StrikeSelection selectStrikes(MarketIntelligence intelligence) {
        BigDecimal spotPrice = intelligence.getSpotPrice() != null
                ? new BigDecimal(intelligence.getSpotPrice())
                : null;

        if (spotPrice == null) {
            log.warn("Spot price not available in market intelligence");
            return null;
        }

        BigDecimal callStrike = intelligence.getOiAnalysis().getMaxCallOIStrike();
        BigDecimal putStrike = intelligence.getOiAnalysis().getMaxPutOIStrike();

        // Verify minimum distance from spot
        BigDecimal minDistance = new BigDecimal(config.getMinDistanceFromSpot());
        BigDecimal wideningAmount = new BigDecimal(50);

        // Call strike must be > spot + minDistance
        if (callStrike.compareTo(spotPrice.add(minDistance)) <= 0) {
            callStrike = callStrike.add(wideningAmount);
            log.debug("Call strike widened to {} due to proximity to spot", callStrike);
        }

        // Put strike must be < spot - minDistance
        if (putStrike.compareTo(spotPrice.subtract(minDistance)) >= 0) {
            putStrike = putStrike.subtract(wideningAmount);
            log.debug("Put strike widened to {} due to proximity to spot", putStrike);
        }

        return new StrikeSelection(callStrike, putStrike);
    }

    /**
     * Builds trade legs from selected strikes and option chain data.
     *
     * @param intelligence    market intelligence data
     * @param strikeSelection selected strikes
     * @return TradeLegsResult with legs and total credit, or null if unable to build
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

        // Build legs
        TradeLeg callLeg = TradeLeg.builder()
                .instrument(buildInstrumentName(intelligence.getSymbol(), expiryDate,
                        strikeSelection.getCallStrike(), OptionType.CE))
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(strikeSelection.getCallStrike().doubleValue())
                .expiryDate(expiryDate)
                .quantity(config.getMaxLots())
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
                .quantity(config.getMaxLots())
                .limitPrice(putLTP.doubleValue())
                .legType(LegType.ENTRY)
                .build();

        // Calculate total credit
        Double totalCredit = callLTP.add(putLTP).doubleValue();

        return new TradeLegsResult(callLeg, putLeg, totalCredit);
    }

    /**
     * Builds a complete TradeSignal from strategy evaluation.
     *
     * @param intelligence    market intelligence data
     * @param strikeSelection selected strikes
     * @param legsResult      trade legs and credit
     * @return TradeSignal ready for execution
     */
    private TradeSignal buildTradeSignal(MarketIntelligence intelligence,
                                         StrikeSelection strikeSelection,
                                         TradeLegsResult legsResult) {
        String reason = String.format(
                "Expiry day strangle: Sell %dCE + %dPE. Expected credit: %.1f points. OI support: %d. OI resistance: %d",
                strikeSelection.getCallStrike().intValue(),
                strikeSelection.getPutStrike().intValue(),
                legsResult.getTotalCredit(),
                strikeSelection.getPutStrike().intValue(),
                strikeSelection.getCallStrike().intValue()
        );

        // Calculate max loss for strangle
        BigDecimal strikeDifference = strikeSelection.getCallStrike()
                .subtract(strikeSelection.getPutStrike());
        Double maxLoss = strikeDifference.doubleValue() * 100 - legsResult.getTotalCredit();

        return TradeSignal.builder()
                .signalId(UUID.randomUUID())
                .symbol(intelligence.getSymbol())
                .strategyName(getName())
                .signalType(SignalType.ENTER)
                .legs(java.util.List.of(legsResult.getCallLeg(), legsResult.getPutLeg()))
                .expectedCredit(legsResult.getTotalCredit())
                .maxLoss(maxLoss)
                .confidence(Confidence.HIGH)
                .reason(reason)
                .build();
    }

    /**
     * Builds instrument name in Zerodha format.
     * Format: SYMBOL + EXPIRY(DDMMMYY) + STRIKE + OPTION_TYPE
     * Example: NIFTY26JUN22300CE
     */
    private String buildInstrumentName(String symbol, LocalDate expiryDate,
                                       BigDecimal strike, OptionType optionType) {
        String expiryStr = expiryDate.format(DateTimeFormatter.ofPattern("ddMMMyy")).toUpperCase();
        return String.format("%s%s%d%s", symbol, expiryStr, strike.intValue(), optionType.name());
    }

    /**
     * Helper class to hold selected strikes.
     */
    private static class StrikeSelection {
        private final BigDecimal callStrike;
        private final BigDecimal putStrike;

        StrikeSelection(BigDecimal callStrike, BigDecimal putStrike) {
            this.callStrike = callStrike;
            this.putStrike = putStrike;
        }

        BigDecimal getCallStrike() {
            return callStrike;
        }

        BigDecimal getPutStrike() {
            return putStrike;
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
