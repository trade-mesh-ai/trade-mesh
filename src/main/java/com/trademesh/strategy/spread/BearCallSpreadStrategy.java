package com.trademesh.strategy.spread;

import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.*;
import com.trademesh.intelligence.iv.ImpliedVolatilityCalculator;
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
 * Bear Call Spread Strategy Implementation.
 * Sells OTM call spread in bearish markets to collect premium.
 * Profits when market stays below short call strike.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BearCallSpreadStrategy implements TradingStrategy {

    private final StrategiesConfig strategiesConfig;
    private final MarketMetricsService marketMetricsService;
    private final NiftyDailyPriceService niftyDailyPriceService;
    private final LotSizeService lotSizeService;
    private final MarketCalendarService marketCalendarService;

    @Override
    public String getName() {
        return "BEAR_CALL_SPREAD";
    }

    @Override
    public boolean isApplicable(MarketCondition condition) {
        return condition == MarketCondition.TRENDING_BEARISH;
    }

    /**
     * Retrieves this strategy's configuration from the unified strategies config.
     */
    private StrategyProperties config() {
        return strategiesConfig.getStrategy("BEAR_CALL_SPREAD");
    }

    /**
     * Calculates confidence score based on market metrics and trend strength.
     * Scores IVR, IV/HV ratio, ATR%, PCR, and trend percentage to determine entry confidence (5 factors, max 100 points).
     * VIX is handled separately via hard entry gate (max-vix) and sizing multiplier, not confidence score.
     */
    int calculateConfidenceScore(MarketMetrics metrics, BigDecimal trendPct) {
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

        // PCR scoring (Bear Call specific: rewards high PCR)
        if (metrics.getPcr() != null) {
            double pcr = metrics.getPcr().doubleValue();
            if (pcr > 1.2) score += 15;
            else if (pcr >= 1.0) score += 8;

            log.debug("PCR scoring (Bear Call): pcr={}, contribution={}", pcr,
                    pcr > 1.2 ? 15 : (pcr >= 1.0 ? 8 : 0));
        }

        // Trend scoring (Bear Call specific: rewards negative trend)
        if (trendPct != null) {
            double trend = trendPct.doubleValue();
            if (trend < -1.0) score += 15;
            else if (trend <= 0.0) score += 8;

            log.debug("Trend scoring (Bear Call): trendPct={}, contribution={}", trend,
                    trend < -1.0 ? 15 : (trend <= 0.0 ? 8 : 0));
        }

        log.debug("Bear Call Spread confidence score: IVR={}, IV/HV={}, PCR={}, Trend={}, total={}",
                metrics.getIvr(), metrics.getIvHvRatio(),
                metrics.getPcr(), trendPct, score);

        return score;
    }

    @Override
    public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
        StrategyProperties cfg = config();

        if (!cfg.isEnabled()) {
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("BEAR_CALL_SPREAD disabled")
                    .build());
        }

        // VIX gate
        double currentVix = intelligence.getVixData() != null ?
                intelligence.getVixData().getCurrentVix() : 15.0;
        double maxVix = cfg.getMaxVix();
        if (currentVix > maxVix) {
            log.warn("VIX {} exceeds max {} — skipping Bear Call Spread", currentVix, maxVix);
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("VIX too high: " + currentVix)
                    .build());
        }

        // Entry time gate
        LocalTime currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime latestTime = LocalTime.parse(cfg.getLatestEntryTime(),
                DateTimeFormatter.ofPattern("HH:mm"));
        if (currentTime.isAfter(latestTime)) {
            log.info("Too late to enter Bear Call Spread (current: {}, limit: {})",
                    currentTime.format(DateTimeFormatter.ofPattern("HH:mm")), cfg.getLatestEntryTime());
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("Too late to enter (current: " + currentTime.format(DateTimeFormatter.ofPattern("HH:mm")) +
                            ", limit: " + cfg.getLatestEntryTime() + ")")
                    .build());
        }

        // Fetch market metrics and continue with strike selection
        return marketMetricsService.calculateMetrics(intelligence.getSymbol())
                .flatMap(metrics -> {
                    // ATR percentile hard gate
                    if (metrics.getAtrPercentile() != null &&
                            metrics.getAtrPercentile().doubleValue() >
                                    cfg.getExtraDouble("atr-percentile-max", 90.0)) {
                        log.warn("ATR percentile exceeds max — skipping Bear Call Spread");
                        return Mono.just(StrategyResult.builder()
                                .isExecutable(false)
                                .skipReason("ATR percentile too high")
                                .build());
                    }

                    // Get DTE for trend lookback calculation
                    OptionChain chain = intelligence.getOptionChain();
                    if (chain == null) {
                        return Mono.just(StrategyResult.builder()
                                .isExecutable(false)
                                .skipReason("Option chain unavailable")
                                .build());
                    }

                    int dte = marketCalendarService.countTradingDays(LocalDate.now(), chain.getExpiryDate());
                    int dteMin = cfg.getExtraInt("dte-min", 1);
                    int trendLookback = Math.max(dteMin, 3);

                    // Fetch trend percentage and metrics in parallel
                    return niftyDailyPriceService.calculateTrendPct(trendLookback)
                            .defaultIfEmpty(BigDecimal.ZERO)
                            .flatMap(trendPct -> {
                                // Confidence score with trend factor
                                int score = calculateConfidenceScore(metrics, trendPct);
                                int minScore = cfg.getExtraInt("min-score", 60);
                                log.info("Bear Call Spread confidence score: {}/{}", score, minScore);

                                if (score < minScore) {
                                    return Mono.just(StrategyResult.builder()
                                            .isExecutable(false)
                                            .skipReason("Confidence score " + score + " below minimum " + minScore)
                                            .build());
                                }

                                // Continue with strike selection
                                return continueWithStrikeSelection(intelligence, metrics, score, cfg, currentVix, dte, chain);
                            });
                });
    }

    /**
     * Continues with strike selection after confidence score validation.
     */
    private Mono<StrategyResult> continueWithStrikeSelection(
            MarketIntelligence intelligence, MarketMetrics metrics, int score,
            StrategyProperties cfg, double currentVix, int dte, OptionChain chain) {
        return Mono.defer(() -> {
            // Get data from MarketIntelligence directly
            double spotPrice = intelligence.getSpotPrice();

            // DTE check (trading days, not calendar days)
            int dteMin = cfg.getExtraInt("dte-min", 1);
            int dteMax = cfg.getExtraInt("dte-max", 4);
            if (dte < dteMin || dte > dteMax) {
                return Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason("DTE " + dte + " outside range [" + dteMin + ", " + dteMax + "]")
                        .build());
            }

            // Strike selection
            // Strike-selection DTE (calendar days — matches /365.0
            // calendar-year denominator, same convention as
            // IronCondorStrategy.selectStrikesAndBuildSignal)
            int calendarDte = (int) ChronoUnit.DAYS.between(LocalDate.now(), chain.getExpiryDate());
            double tte = calendarDte / 365.0;
            double targetDelta = cfg.getExtraDouble("target-delta", 0.30);
            int wingWidth = cfg.getExtraInt("wing-width", 100);
            int lotSize = lotSizeService.getLotSize("NIFTY50");

            // Find short CALL strike (above spot, closest OTM with delta <= targetDelta)
            OptionStrike shortCallStrike = chain.getStrikes().stream()
                    .filter(s -> s.getStrikePrice().doubleValue() > spotPrice)
                    .sorted((a, b) -> a.getStrikePrice().compareTo(b.getStrikePrice()))
                    .filter(s -> {
                        double delta = ImpliedVolatilityCalculator.delta(
                                spotPrice, s.getStrikePrice().doubleValue(), tte,
                                s.getCallIV() != null && s.getCallIV().doubleValue() > 0 ?
                                        s.getCallIV().doubleValue() : 15.0, "CE");
                        return delta <= targetDelta;
                    })
                    .findFirst()
                    .orElse(null);

            if (shortCallStrike == null) {
                log.warn("Unable to find short call strike with delta <= {}", targetDelta);
                return Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason("No valid call strike found")
                        .build());
            }

            BigDecimal shortCallStrikePrice = shortCallStrike.getStrikePrice();
            BigDecimal longCallStrikePrice = shortCallStrikePrice.add(BigDecimal.valueOf(wingWidth));

            // Get LTPs
            BigDecimal shortCallLtp = shortCallStrike.getCallLTP() != null ?
                    shortCallStrike.getCallLTP() : BigDecimal.ZERO;

            OptionStrike longCallStrikeData = chain.getStrikes().stream()
                    .filter(s -> s.getStrikePrice().compareTo(longCallStrikePrice) == 0)
                    .findFirst()
                    .orElse(null);
            BigDecimal longCallLtp = longCallStrikeData != null && longCallStrikeData.getCallLTP() != null ?
                    longCallStrikeData.getCallLTP() : BigDecimal.ZERO;

            // Calculate net credit
            BigDecimal netCredit = shortCallLtp.subtract(longCallLtp);

            // Validate minimum credit
            double minCreditPct = cfg.getExtraDouble("min-credit-pct", 0.10);
            double minCredit = wingWidth * minCreditPct;
            if (netCredit.doubleValue() < minCredit) {
                log.warn("Net credit {} is below minimum {}", netCredit, minCredit);
                return Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason("Net credit below minimum")
                        .build());
            }

            // NEW: Compute ATR multipliers (PHASE 3 ADDITION)
            BigDecimal atrPercentileBD = metrics.getAtrPercentile();
            double atrMultiplier = (atrPercentileBD != null)
                ? 0.85 + (atrPercentileBD.doubleValue() / 100.0) * 0.50
                : 1.0;
            double targetMultiplier = (atrPercentileBD != null)
                ? 0.90 + (atrPercentileBD.doubleValue() / 100.0) * 0.45
                : 1.0;

            // NEW: Compute ATR-adjusted risk snapshot fields (PHASE 3 ADDITION)
            // NOTE: lotSize is already declared at line 228, do NOT redeclare
            BigDecimal stopDistancePoints = netCredit
                .multiply(BigDecimal.valueOf(cfg.getStopLossPercent() / 100.0))
                .multiply(BigDecimal.valueOf(atrMultiplier));
            BigDecimal targetThreshold = netCredit
                .multiply(BigDecimal.valueOf(cfg.getTargetProfitPercent() / 100.0))
                .multiply(BigDecimal.valueOf(targetMultiplier));
            BigDecimal plannedRiskPerLot = stopDistancePoints.multiply(BigDecimal.valueOf(lotSize));

            // Compute maxLossPerLot (risk of ONE lot only, no dependency on total lots)
            BigDecimal maxLossPerLot = BigDecimal.valueOf(wingWidth - netCredit.doubleValue())
                    .multiply(BigDecimal.valueOf(lotSize));

            // Capital-based position sizing with VIX and confidence scaling: compute lots from risk budget
            // Capped by cfg.getMaxLots() as hard ceiling
            int lots = Math.min(
                    lotSizeService.getPositionSize(plannedRiskPerLot, currentVix, score, cfg.getExtraInt("min-score", 60)),
                    cfg.getMaxLots()
            );

            // P&L matrix (recomputed with actual lots)
            BigDecimal maxProfit = netCredit.multiply(BigDecimal.valueOf(lotSize * lots));
            BigDecimal maxLoss = maxLossPerLot.multiply(BigDecimal.valueOf(lots));
            BigDecimal breakeven = shortCallStrikePrice.add(netCredit);

            // Build trade legs
            List<TradeLeg> legs = new ArrayList<>();

            // SELL short call
            legs.add(TradeLeg.builder()
                    .instrument(buildInstrumentName("NIFTY50", chain.getExpiryDate(), shortCallStrikePrice, OptionType.CE))
                    .action(Action.SELL)
                    .optionType(OptionType.CE)
                    .strikePrice(shortCallStrikePrice)
                    .expiryDate(chain.getExpiryDate())
                    .quantity(lots)
                    .limitPrice(shortCallLtp)
                    .legType(LegType.ENTRY)
                    .build());

            // BUY long call
            legs.add(TradeLeg.builder()
                    .instrument(buildInstrumentName("NIFTY50", chain.getExpiryDate(), longCallStrikePrice, OptionType.CE))
                    .action(Action.BUY)
                    .optionType(OptionType.CE)
                    .strikePrice(longCallStrikePrice)
                    .expiryDate(chain.getExpiryDate())
                    .quantity(lots)
                    .limitPrice(longCallLtp)
                    .legType(LegType.ENTRY)
                    .build());

            // Build metadata map
            java.util.Map<String, String> metadata = new java.util.HashMap<>();
            metadata.put("maxProfit", maxProfit.toString());
            metadata.put("maxLoss", maxLoss.toString());
            metadata.put("breakeven", breakeven.toString());
            metadata.put("netCredit", netCredit.toString());
            metadata.put("shortCallStrike", shortCallStrikePrice.toString());
            metadata.put("longCallStrike", longCallStrikePrice.toString());
            metadata.put("shortCallLtp", shortCallLtp.toString());
            metadata.put("longCallLtp", longCallLtp.toString());
            metadata.put("dte", String.valueOf(dte));
            metadata.put("currentVix", String.valueOf(currentVix));
            metadata.put("confidenceScore", String.valueOf(score));

            Confidence confidence = score >= 60 ? Confidence.HIGH
                    : score >= 43 ? Confidence.MEDIUM
                      : Confidence.LOW;

            return Mono.just(StrategyResult.builder()
                    .signal(com.trademesh.strategy.TradeSignal.builder()
                            .symbol("NIFTY50")
                            .strategyName(getName())
                            .signalType(com.trademesh.strategy.enums.SignalType.ENTER)
                            .legs(legs)
                            .expectedCredit(netCredit.doubleValue())
                            .maxLoss(maxLoss.doubleValue())
                            .reason("Bear Call Spread: " + shortCallStrikePrice + "CE / " +
                                    longCallStrikePrice + "CE | Credit: " + netCredit + " | Score: " + score)
                            .confidence(confidence)
                            .metadata(metadata)
                            .stopDistancePoints(stopDistancePoints)
                            .targetThreshold(targetThreshold)
                            .plannedRiskPerLot(plannedRiskPerLot)
                            .atrPercentile(metrics.getAtrPercentile() != null ? metrics.getAtrPercentile().intValue() : null)
                            .confidenceScore(score)
                            .build())
                    .isExecutable(true)
                    .build());
        });
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
                .applicableConditions(java.util.List.of(MarketCondition.TRENDING_BEARISH))
                .isEnabled(props.isEnabled())
                .build();
    }
}
