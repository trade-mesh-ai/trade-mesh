package com.trademesh.strategy.ironcondor;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
import com.trademesh.intelligence.MarketMetrics;
import com.trademesh.intelligence.MarketMetricsService;
import com.trademesh.intelligence.iv.ImpliedVolatilityCalculator;
import com.trademesh.intelligence.vix.VixData;
import com.trademesh.intelligence.vix.VixMonitorService;
import com.trademesh.strategy.StrategyConfig;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.TradingStrategy;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.config.StrategyProperties;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.Confidence;
import com.trademesh.strategy.enums.LegType;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.trading.MarketCalendarService;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Iron Condor Strategy Implementation.
 * Sells both call and put spreads in sideways markets for theta decay.
 * Uses delta-based strike selection and dynamic exit level adjustment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IronCondorStrategy implements TradingStrategy {

    private final StrategiesConfig strategiesConfig;
    private final MarketMetricsService marketMetricsService;
    private final OptionChainService optionChainService;
    private final VixMonitorService vixMonitorService;
    private final LotSizeService lotSizeService;
    private final MarketCalendarService marketCalendarService;

    @Override
    public String getName() {
        return "IRON_CONDOR";
    }

    @Override
    public boolean isApplicable(MarketCondition condition) {
        return condition == MarketCondition.SIDEWAYS;
    }

    /**
     * Retrieves this strategy's configuration from the unified strategies config.
     */
    private StrategyProperties config() {
        return strategiesConfig.getStrategy("IRON_CONDOR");
    }

    /**
     * Calculates confidence score based on market metrics and VIX data.
     * Scores IVR, IV/HV ratio, ATR%, VIX, and PCR to determine entry confidence.
     */
    private int calculateConfidenceScore(MarketMetrics metrics, VixData vixData) {
        int score = 0;

        // IVR scoring
        if (metrics.getIvr() != null) {
            double ivr = metrics.getIvr().doubleValue();
            if (ivr >= 20 && ivr < 50) score += 20;
            else if (ivr >= 50 && ivr < 65) score += 30;
            else if (ivr >= 65 && ivr <= 80) score += 15;
        }

        // IV/HV ratio scoring
        if (metrics.getIvHvRatio() != null) {
            double ratio = metrics.getIvHvRatio().doubleValue();
            if (ratio >= 1.3) score += 20;
            else if (ratio >= 1.1) score += 10;
        }

        // ATR% scoring
        if (metrics.getAtrPct() != null) {
            double atrPct = metrics.getAtrPct().doubleValue();
            if (atrPct < 0.6) score += 15;
            else if (atrPct < 0.8) score += 20;
            else if (atrPct < 1.0) score += 10;
            else score -= 20; // penalty for high ATR
        }

        // VIX scoring
        if (vixData != null) {
            double vix = vixData.getCurrentVix();
            if (vix >= 12 && vix < 16) score += 15;      // ideal
            else if (vix >= 16 && vix <= 20) score += 5;  // acceptable
            else if (vix > 20) score -= 50;               // hard penalty
            // vix < 12 → 0 pts (too calm, low premium)
        } else {
            log.debug("VIX unavailable for scoring");
        }

        // PCR scoring
        if (metrics.getPcr() != null) {
            double pcr = metrics.getPcr().doubleValue();
            if (pcr >= 0.8 && pcr <= 1.2) score += 15;  // balanced sentiment
            else score += 0;                              // directional — no pts

            log.debug("PCR scoring: pcr={}, contribution={}", pcr, (pcr >= 0.8 && pcr <= 1.2) ? 15 : 0);
        }

        log.debug("Iron Condor confidence score: IVR={}, IV/HV={}, ATR%={}, VIX={}, PCR={}, total={}",
                metrics.getIvr(), metrics.getIvHvRatio(), metrics.getAtrPct(),
                vixData != null ? vixData.getCurrentVix() : "N/A",
                metrics.getPcr(), score);

        return score;
    }

    /**
     * Calculates adjusted exit levels based on ATR% volatility.
     * Returns [adjustedTargetPct, adjustedSlPct] as percentages of credit.
     */
    private double[] calculateAdjustedExitLevels(double atrPct, StrategyProperties cfg) {
        double baseProfitPct = cfg.getTargetProfitPercent() / 100.0;
        double baseSlPct = cfg.getStopLossPercent() / 100.0;
        double calmThreshold = cfg.getExtraDouble("atr-calm-threshold", 0.7);
        double highThreshold = cfg.getExtraDouble("atr-high-threshold", 1.0);

        if (atrPct < calmThreshold) {
            // Calm market: tighter targets
            double calmTargetMult = cfg.getExtraDouble("calm-target-multiplier", 0.9);
            double calmSlMult = cfg.getExtraDouble("calm-sl-multiplier", 0.8);
            return new double[]{baseProfitPct * calmTargetMult, baseSlPct * calmSlMult};
        } else if (atrPct > highThreshold) {
            // High volatility: wider targets
            double highTargetMult = cfg.getExtraDouble("high-target-multiplier", 1.1);
            double highSlMult = cfg.getExtraDouble("high-sl-multiplier", 1.2);
            return new double[]{baseProfitPct * highTargetMult, baseSlPct * highSlMult};
        } else {
            // Normal: base percentages
            return new double[]{baseProfitPct, baseSlPct};
        }
    }

    /**
     * Selects strikes and builds the Iron Condor signal.
     * Uses delta-based strike selection for OTM options.
     */
    private Mono<IronCondorSignal> selectStrikesAndBuildSignal(
            OptionChain chain, MarketMetrics metrics,
            int confidenceScore, StrategyProperties cfg,
            double currentVix) {

        double spotPrice = chain.getSpotPrice().doubleValue();
        LocalDate expiryDate = chain.getExpiryDate();
        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(), expiryDate);

        if (dte <= 0) {
            log.warn("DTE is zero or negative, skipping Iron Condor");
            return Mono.empty();
        }

         double tte = dte / 365.0;
         double atmIv = metrics.getCurrentAtmIv() != null ?
                 metrics.getCurrentAtmIv().doubleValue() : 15.0;
         double targetDelta = cfg.getExtraDouble("target-delta", 0.18);
         int wingWidth = cfg.getExtraInt("wing-width", 200);
         int lotSize = lotSizeService.getLotSize("NIFTY50");

         // Find short CALL strike (first strike with delta <= targetDelta, above spot)
        OptionStrike callStrike = chain.getStrikes().stream()
                .filter(s -> s.getStrikePrice().doubleValue() > spotPrice)
                .sorted((a, b) -> a.getStrikePrice().compareTo(b.getStrikePrice()))
                .filter(s -> {
                    double delta = ImpliedVolatilityCalculator.delta(
                            spotPrice, s.getStrikePrice().doubleValue(), tte, atmIv, "CE");
                    return delta <= targetDelta;
                })
                .findFirst()
                .orElse(null);

        if (callStrike == null) {
            log.warn("Unable to find short call strike with delta <= {}", targetDelta);
            return Mono.empty();
        }

        // Find short PUT strike (first strike with delta <= targetDelta, below spot)
        OptionStrike putStrike = chain.getStrikes().stream()
                .filter(s -> s.getStrikePrice().doubleValue() < spotPrice)
                .sorted((a, b) -> b.getStrikePrice().compareTo(a.getStrikePrice()))
                .filter(s -> {
                    double delta = ImpliedVolatilityCalculator.delta(
                            spotPrice, s.getStrikePrice().doubleValue(), tte, atmIv, "PE");
                    return Math.abs(delta) <= targetDelta;
                })
                .findFirst()
                .orElse(null);

        if (putStrike == null) {
            log.warn("Unable to find short put strike with delta <= {}", targetDelta);
            return Mono.empty();
        }

        BigDecimal shortCallStrike = callStrike.getStrikePrice();
        BigDecimal shortPutStrike = putStrike.getStrikePrice();
        BigDecimal longCallStrike = shortCallStrike.add(BigDecimal.valueOf(wingWidth));
        BigDecimal longPutStrike = shortPutStrike.subtract(BigDecimal.valueOf(wingWidth));

        // Get LTPs from chain
        BigDecimal shortCallLtp = callStrike.getCallLTP() != null ? callStrike.getCallLTP() : BigDecimal.ZERO;
        BigDecimal shortPutLtp = putStrike.getPutLTP() != null ? putStrike.getPutLTP() : BigDecimal.ZERO;

        OptionStrike longCallStrikeData = chain.getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(longCallStrike) == 0)
                .findFirst()
                .orElse(null);
        BigDecimal longCallLtp = longCallStrikeData != null && longCallStrikeData.getCallLTP() != null ?
                longCallStrikeData.getCallLTP() : BigDecimal.ZERO;

        OptionStrike longPutStrikeData = chain.getStrikes().stream()
                .filter(s -> s.getStrikePrice().compareTo(longPutStrike) == 0)
                .findFirst()
                .orElse(null);
        BigDecimal longPutLtp = longPutStrikeData != null && longPutStrikeData.getPutLTP() != null ?
                longPutStrikeData.getPutLTP() : BigDecimal.ZERO;

        // Calculate net credit
        BigDecimal netCredit = shortCallLtp.add(shortPutLtp).subtract(longCallLtp).subtract(longPutLtp);

        // Validate minimum credit
        double minCreditPct = cfg.getExtraDouble("min-credit-pct", 0.15);
        double minCredit = wingWidth * minCreditPct;
        if (netCredit.doubleValue() < minCredit) {
            log.warn("Net credit {} is below minimum {}", netCredit, minCredit);
            return Mono.empty();
        }

        // Compute maxLossPerLot (risk of ONE lot only, no dependency on total lots)
        BigDecimal maxLossPerLot = BigDecimal.valueOf(wingWidth - netCredit.doubleValue())
                .multiply(BigDecimal.valueOf(lotSize));

         // Capital-based position sizing with VIX scaling: compute lots from risk budget
         // Capped by cfg.getMaxLots() as hard ceiling
         int lots = Math.min(
                 lotSizeService.getPositionSize(maxLossPerLot, currentVix),
                 cfg.getMaxLots()
         );

        // P&L matrix (recomputed with actual lots)
        BigDecimal maxProfit = netCredit.multiply(BigDecimal.valueOf(lotSize * lots));
        BigDecimal maxLoss = maxLossPerLot.multiply(BigDecimal.valueOf(lots));
        BigDecimal upperBreakeven = shortCallStrike.add(netCredit);
        BigDecimal lowerBreakeven = shortPutStrike.subtract(netCredit);
        BigDecimal riskRewardRatio = maxLoss.signum() > 0 ?
                maxProfit.divide(maxLoss, 4, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // Adjusted exit levels
        double atrPct = metrics.getAtrPct() != null ?
                metrics.getAtrPct().doubleValue() : 0.8;
        double[] exitLevels = calculateAdjustedExitLevels(atrPct, cfg);
        BigDecimal adjustedProfitTarget = netCredit.multiply(BigDecimal.valueOf(exitLevels[0]));
        BigDecimal adjustedStopLoss = netCredit.multiply(BigDecimal.valueOf(exitLevels[1]));

        // Build trade legs
        List<TradeLeg> legs = new ArrayList<>();

        // SELL short call
        legs.add(TradeLeg.builder()
                .instrument(buildInstrumentName("NIFTY50", expiryDate, shortCallStrike, OptionType.CE))
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(shortCallStrike.doubleValue())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(shortCallLtp.doubleValue())
                .legType(LegType.ENTRY)
                .build());

        // SELL short put
        legs.add(TradeLeg.builder()
                .instrument(buildInstrumentName("NIFTY50", expiryDate, shortPutStrike, OptionType.PE))
                .action(Action.SELL)
                .optionType(OptionType.PE)
                .strikePrice(shortPutStrike.doubleValue())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(shortPutLtp.doubleValue())
                .legType(LegType.ENTRY)
                .build());

        // BUY long call
        legs.add(TradeLeg.builder()
                .instrument(buildInstrumentName("NIFTY50", expiryDate, longCallStrike, OptionType.CE))
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(longCallStrike.doubleValue())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(longCallLtp.doubleValue())
                .legType(LegType.ENTRY)
                .build());

        // BUY long put
        legs.add(TradeLeg.builder()
                .instrument(buildInstrumentName("NIFTY50", expiryDate, longPutStrike, OptionType.PE))
                .action(Action.BUY)
                .optionType(OptionType.PE)
                .strikePrice(longPutStrike.doubleValue())
                .expiryDate(expiryDate)
                .quantity(lots)
                .limitPrice(longPutLtp.doubleValue())
                .legType(LegType.ENTRY)
                .build());

        // Build and return signal
        IronCondorSignal signal = IronCondorSignal.builder()
                .shortCallStrike(shortCallStrike)
                .shortPutStrike(shortPutStrike)
                .longCallStrike(longCallStrike)
                .longPutStrike(longPutStrike)
                .expiryDate(expiryDate.format(DateTimeFormatter.ISO_DATE))
                .shortCallLtp(shortCallLtp)
                .shortPutLtp(shortPutLtp)
                .longCallLtp(longCallLtp)
                .longPutLtp(longPutLtp)
                .netCredit(netCredit)
                .maxProfit(maxProfit)
                .maxLoss(maxLoss)
                .upperBreakeven(upperBreakeven)
                .lowerBreakeven(lowerBreakeven)
                .riskRewardRatio(riskRewardRatio)
                .confidenceScore(confidenceScore)
                .ivr(metrics.getIvr())
                .ivHvRatio(metrics.getIvHvRatio())
                .atrPct(metrics.getAtrPct())
                .currentAtmIv(metrics.getCurrentAtmIv())
                .dte(dte)
                .adjustedProfitTarget(adjustedProfitTarget)
                .adjustedStopLoss(adjustedStopLoss)
                .legs(legs)
                .build();

        return Mono.just(signal);
    }

    /**
     * Builds instrument name in Zerodha format.
     * Format: SYMBOL + EXPIRY(DDMMMYY) + STRIKE + OPTION_TYPE
     */
    private String buildInstrumentName(String symbol, LocalDate expiryDate,
                                       BigDecimal strike, OptionType optionType) {
        String expiryStr = expiryDate.format(DateTimeFormatter.ofPattern("ddMMMyy")).toUpperCase();
        return String.format("%s%s%d%s", symbol, expiryStr, strike.intValue(), optionType.name());
    }

    @Override
    public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
        StrategyProperties cfg = config();

        if (!cfg.isEnabled()) {
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("IRON_CONDOR disabled")
                    .build());
        }

        // Entry time gate
        LocalTime currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime latestTime = LocalTime.parse(cfg.getLatestEntryTime(),
                DateTimeFormatter.ofPattern("HH:mm"));
        if (currentTime.isAfter(latestTime)) {
            log.info("Too late to enter Iron Condor (current: {}, limit: {})",
                    currentTime.format(DateTimeFormatter.ofPattern("HH:mm")), cfg.getLatestEntryTime());
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("Too late to enter (current: " + currentTime.format(DateTimeFormatter.ofPattern("HH:mm")) +
                            ", limit: " + cfg.getLatestEntryTime() + ")")
                    .build());
        }

        return vixMonitorService.fetchCurrentVix()
                .defaultIfEmpty(VixData.builder().currentVix(15.0).build())
                // default 15.0 if VIX unavailable — conservative assumption
                .flatMap(vixData -> {

                    // VIX hard gate
                    double currentVix = vixData.getCurrentVix();
                    double maxVix = cfg.getMaxVix();
                    if (currentVix > maxVix) {
                        log.warn("VIX {} exceeds max {} — skipping Iron Condor", currentVix, maxVix);
                        return Mono.just(StrategyResult.builder()
                                .isExecutable(false)
                                .skipReason("VIX too high: " + currentVix)
                                .build());
                    }

                    // Continue with existing pipeline
                    return marketMetricsService.calculateMetrics("NIFTY50")
                            .flatMap(metrics -> {
                                // ATR% hard gate
                                if (metrics.getAtrPct() != null &&
                                        metrics.getAtrPct().doubleValue() > cfg.getExtraDouble("atr-pct-max", 1.0)) {
                                    log.warn("ATR% exceeds max — skipping Iron Condor");
                                    return Mono.just(StrategyResult.builder()
                                            .isExecutable(false)
                                            .skipReason("ATR% too high")
                                            .build());
                                }

                                // Confidence score — now pass vixData
                                int score = calculateConfidenceScore(metrics, vixData);
                                int minScore = cfg.getExtraInt("min-score", 60);
                                log.info("Iron Condor confidence score: {}/{}", score, minScore);

                                if (score < minScore) {
                                    return Mono.just(StrategyResult.builder()
                                            .isExecutable(false)
                                            .skipReason("Confidence score " + score + " below minimum " + minScore)
                                            .build());
                                }

                                // Strike selection
                                return optionChainService.fetchOptionChain("NIFTY50")
                                        .flatMap(chain -> {
                                            int dte = marketCalendarService.countTradingDays(
                                                    LocalDate.now(), chain.getExpiryDate());

                                            int dteMin = cfg.getExtraInt("dte-min", 1);
                                            int dteMax = cfg.getExtraInt("dte-max", 4);

                                            if (dte < dteMin || dte > dteMax) {
                                                return Mono.just(StrategyResult.builder()
                                                        .isExecutable(false)
                                                        .skipReason("DTE " + dte + " outside range [" + dteMin + ", " + dteMax + "]")
                                                        .build());
                                            }

                                            return selectStrikesAndBuildSignal(chain, metrics, score, cfg, currentVix)
                                                    .map(signal -> {
                                                        // Build metadata map with P&L matrix and market context
                                                        java.util.Map<String, String> metadata = new java.util.HashMap<>();
                                                        metadata.put("maxProfit", signal.getMaxProfit().toString());
                                                        metadata.put("maxLoss", signal.getMaxLoss().toString());
                                                        metadata.put("upperBreakeven", signal.getUpperBreakeven().toString());
                                                        metadata.put("lowerBreakeven", signal.getLowerBreakeven().toString());
                                                        metadata.put("riskRewardRatio", signal.getRiskRewardRatio().toString());
                                                        metadata.put("confidenceScore", String.valueOf(signal.getConfidenceScore()));
                                                        metadata.put("atrPct", signal.getAtrPct() != null ? signal.getAtrPct().toString() : "0");
                                                        metadata.put("ivHvRatio", signal.getIvHvRatio() != null ? signal.getIvHvRatio().toString() : "0");
                                                        metadata.put("ivr", signal.getIvr() != null ? signal.getIvr().toString() : "0");
                                                        metadata.put("adjustedProfitTarget", signal.getAdjustedProfitTarget().toString());
                                                        metadata.put("adjustedStopLoss", signal.getAdjustedStopLoss().toString());
                                                        metadata.put("shortCallStrike", signal.getShortCallStrike().toString());
                                                        metadata.put("shortPutStrike", signal.getShortPutStrike().toString());
                                                        metadata.put("longCallStrike", signal.getLongCallStrike().toString());
                                                        metadata.put("longPutStrike", signal.getLongPutStrike().toString());
                                                        metadata.put("shortCallLtp", signal.getShortCallLtp().toString());
                                                        metadata.put("shortPutLtp", signal.getShortPutLtp().toString());
                                                        metadata.put("longCallLtp", signal.getLongCallLtp().toString());
                                                        metadata.put("longPutLtp", signal.getLongPutLtp().toString());
                                                        metadata.put("netCredit", signal.getNetCredit().toString());
                                                        metadata.put("dte", String.valueOf(signal.getDte()));
                                                        metadata.put("currentAtmIv", signal.getCurrentAtmIv() != null ? signal.getCurrentAtmIv().toString() : "0");

                                                        Confidence confidence = score >= 70 ? Confidence.HIGH
                                                                : score >= 50 ? Confidence.MEDIUM
                                                                  : Confidence.LOW;
                                                        return StrategyResult.builder()
                                                                .signal(com.trademesh.strategy.TradeSignal.builder()
                                                                        .symbol("NIFTY50")
                                                                        .strategyName(getName())
                                                                        .signalType(com.trademesh.strategy.enums.SignalType.ENTER)
                                                                        .legs(signal.getLegs())
                                                                        .expectedCredit(signal.getNetCredit().doubleValue())
                                                                        .maxLoss(signal.getMaxLoss().doubleValue())
                                                                        .reason("Iron Condor: " + signal.getShortCallStrike() + "CE / " +
                                                                                signal.getShortPutStrike() + "PE | Credit: " +
                                                                                signal.getNetCredit() + " | Score: " + score)
                                                                        .confidence(confidence)
                                                                        .metadata(metadata)
                                                                        .build())
                                                                .isExecutable(true)
                                                                .build();
                                                    })
                                                    .defaultIfEmpty(StrategyResult.builder()
                                                            .isExecutable(false)
                                                            .skipReason("Strike selection failed")
                                                            .build());
                                        })
                                        .defaultIfEmpty(StrategyResult.builder()
                                                .isExecutable(false)
                                                .skipReason("Option chain unavailable")
                                                .build());
                            })
                            .defaultIfEmpty(StrategyResult.builder()
                                    .isExecutable(false)
                                    .skipReason("Market metrics unavailable")
                                    .build());
                })
                .onErrorResume(e -> {
                    log.error("Iron Condor evaluation error: {}", e.getMessage(), e);
                    return Mono.just(StrategyResult.builder()
                            .isExecutable(false)
                            .skipReason("Error: " + e.getMessage())
                            .build());
                });
    }


    @Override
    public StrategyConfig getConfig() {
        StrategyProperties props = config();
        return StrategyConfig.builder()
                .strategyName(getName())
                .maxLotsPerTrade(props.getMaxLots())
                .stopLossPercent(props.getStopLossPercent())
                .targetProfitPercent(props.getTargetProfitPercent())
                .maxVix(props.getMaxVix())
                .minVix(props.getMinVix())
                .allowedDays(props.getAllowedDays().stream()
                        .map(java.time.DayOfWeek::valueOf)
                        .collect(Collectors.toList()))
                .applicableConditions(java.util.List.of(MarketCondition.SIDEWAYS))
                .isEnabled(props.isEnabled())
                .build();
    }
}
