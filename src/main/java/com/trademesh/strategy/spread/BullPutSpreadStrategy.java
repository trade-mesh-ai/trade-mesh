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
 * Bull Put Spread Strategy Implementation.
 * Sells OTM put spread in bullish markets to collect premium.
 * Profits when market stays above short put strike.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BullPutSpreadStrategy implements TradingStrategy {

    private final StrategiesConfig strategiesConfig;
    private final MarketMetricsService marketMetricsService;
    private final NiftyDailyPriceService niftyDailyPriceService;
    private final LotSizeService lotSizeService;
    private final MarketCalendarService marketCalendarService;

    @Override
    public String getName() {
        return "BULL_PUT_SPREAD";
    }

    @Override
    public boolean isApplicable(MarketCondition condition) {
        return condition == MarketCondition.TRENDING_BULLISH;
    }

    /**
     * Retrieves this strategy's configuration from the unified strategies config.
     */
    private StrategyProperties config() {
        return strategiesConfig.getStrategy("BULL_PUT_SPREAD");
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

        // ATR% scoring (spread-specific bands: delta-ratio scaled)
        if (metrics.getAtrPct() != null) {
            double atrPct = metrics.getAtrPct().doubleValue();
            if (atrPct < 0.36) score += 15;
            else if (atrPct < 0.48) score += 20;
            else if (atrPct < 0.60) score += 10;
        }

        // PCR scoring (Bull Put specific: rewards low PCR)
        if (metrics.getPcr() != null) {
            double pcr = metrics.getPcr().doubleValue();
            if (pcr < 0.8) score += 15;
            else if (pcr < 1.0) score += 8;

            log.debug("PCR scoring (Bull Put): pcr={}, contribution={}", pcr,
                    pcr < 0.8 ? 15 : (pcr < 1.0 ? 8 : 0));
        }

        // Trend scoring (Bull Put specific: rewards positive trend)
        if (trendPct != null) {
            double trend = trendPct.doubleValue();
            if (trend > 1.0) score += 15;
            else if (trend >= 0.0) score += 8;

            log.debug("Trend scoring (Bull Put): trendPct={}, contribution={}", trend,
                    trend > 1.0 ? 15 : (trend >= 0.0 ? 8 : 0));
        }

        log.debug("Bull Put Spread confidence score: IVR={}, IV/HV={}, ATR%={}, PCR={}, Trend={}, total={}",
                metrics.getIvr(), metrics.getIvHvRatio(), metrics.getAtrPct(),
                metrics.getPcr(), trendPct, score);

        return score;
    }

    @Override
    public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
        StrategyProperties cfg = config();

        if (!cfg.isEnabled()) {
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("BULL_PUT_SPREAD disabled")
                    .build());
        }

        // VIX gate
        double currentVix = intelligence.getVixData() != null ?
                intelligence.getVixData().getCurrentVix() : 15.0;
        double maxVix = cfg.getMaxVix();
        if (currentVix > maxVix) {
            log.warn("VIX {} exceeds max {} — skipping Bull Put Spread", currentVix, maxVix);
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
            log.info("Too late to enter Bull Put Spread (current: {}, limit: {})",
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
                                log.info("Bull Put Spread confidence score: {}/{}", score, minScore);

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

            // Find short PUT strike (below spot, closest OTM with delta <= targetDelta)
            OptionStrike shortPutStrike = chain.getStrikes().stream()
                    .filter(s -> s.getStrikePrice().doubleValue() < spotPrice)
                    .sorted((a, b) -> b.getStrikePrice().compareTo(a.getStrikePrice()))
                    .filter(s -> {
                        double delta = ImpliedVolatilityCalculator.delta(
                                spotPrice, s.getStrikePrice().doubleValue(), tte,
                                s.getPutIV() != null && s.getPutIV().doubleValue() > 0 ?
                                        s.getPutIV().doubleValue() : 15.0, "PE");
                        return Math.abs(delta) <= targetDelta;
                    })
                    .findFirst()
                    .orElse(null);

            if (shortPutStrike == null) {
                log.warn("Unable to find short put strike with delta <= {}", targetDelta);
                return Mono.just(StrategyResult.builder()
                        .isExecutable(false)
                        .skipReason("No valid put strike found")
                        .build());
            }

            BigDecimal shortPutStrikePrice = shortPutStrike.getStrikePrice();
            BigDecimal longPutStrikePrice = shortPutStrikePrice.subtract(BigDecimal.valueOf(wingWidth));

            // Get LTPs
            BigDecimal shortPutLtp = shortPutStrike.getPutLTP() != null ?
                    shortPutStrike.getPutLTP() : BigDecimal.ZERO;

            OptionStrike longPutStrikeData = chain.getStrikes().stream()
                    .filter(s -> s.getStrikePrice().compareTo(longPutStrikePrice) == 0)
                    .findFirst()
                    .orElse(null);
            BigDecimal longPutLtp = longPutStrikeData != null && longPutStrikeData.getPutLTP() != null ?
                    longPutStrikeData.getPutLTP() : BigDecimal.ZERO;

            // Calculate net credit
            BigDecimal netCredit = shortPutLtp.subtract(longPutLtp);

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

            // Compute maxLossPerLot (risk of ONE lot only, no dependency on total lots)
            BigDecimal maxLossPerLot = BigDecimal.valueOf(wingWidth - netCredit.doubleValue())
                    .multiply(BigDecimal.valueOf(lotSize));

            // Capital-based position sizing with VIX and confidence scaling: compute lots from risk budget
            // Capped by cfg.getMaxLots() as hard ceiling
            int lots = Math.min(
                    lotSizeService.getPositionSize(maxLossPerLot, currentVix, score, cfg.getExtraInt("min-score", 60)),
                    cfg.getMaxLots()
            );

            // P&L matrix (recomputed with actual lots)
            BigDecimal maxProfit = netCredit.multiply(BigDecimal.valueOf(lotSize * lots));
            BigDecimal maxLoss = maxLossPerLot.multiply(BigDecimal.valueOf(lots));
            BigDecimal breakeven = shortPutStrikePrice.subtract(netCredit);

            // Phase 1: Compute risk snapshot fields (unadjusted, no multiplier)
            BigDecimal stopDistancePoints = netCredit.multiply(BigDecimal.valueOf(cfg.getStopLossPercent() / 100.0));
            BigDecimal targetThreshold = netCredit.multiply(BigDecimal.valueOf(cfg.getTargetProfitPercent() / 100.0));
            BigDecimal plannedRiskPerLot = stopDistancePoints.multiply(BigDecimal.valueOf(lotSize));

            // Build trade legs
            List<TradeLeg> legs = new ArrayList<>();

            // SELL short put
            legs.add(TradeLeg.builder()
                    .instrument(buildInstrumentName("NIFTY50", chain.getExpiryDate(), shortPutStrikePrice, OptionType.PE))
                    .action(Action.SELL)
                    .optionType(OptionType.PE)
                    .strikePrice(shortPutStrikePrice)
                    .expiryDate(chain.getExpiryDate())
                    .quantity(lots)
                    .limitPrice(shortPutLtp)
                    .legType(LegType.ENTRY)
                    .build());

            // BUY long put
            legs.add(TradeLeg.builder()
                    .instrument(buildInstrumentName("NIFTY50", chain.getExpiryDate(), longPutStrikePrice, OptionType.PE))
                    .action(Action.BUY)
                    .optionType(OptionType.PE)
                    .strikePrice(longPutStrikePrice)
                    .expiryDate(chain.getExpiryDate())
                    .quantity(lots)
                    .limitPrice(longPutLtp)
                    .legType(LegType.ENTRY)
                    .build());

            // Build metadata map
            java.util.Map<String, String> metadata = new java.util.HashMap<>();
            metadata.put("maxProfit", maxProfit.toString());
            metadata.put("maxLoss", maxLoss.toString());
            metadata.put("breakeven", breakeven.toString());
            metadata.put("netCredit", netCredit.toString());
            metadata.put("shortPutStrike", shortPutStrikePrice.toString());
            metadata.put("longPutStrike", longPutStrikePrice.toString());
            metadata.put("shortPutLtp", shortPutLtp.toString());
            metadata.put("longPutLtp", longPutLtp.toString());
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
                            .reason("Bull Put Spread: " + shortPutStrikePrice + "PE / " +
                                    longPutStrikePrice + "PE | Credit: " + netCredit + " | Score: " + score)
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
                .applicableConditions(java.util.List.of(MarketCondition.TRENDING_BULLISH))
                .isEnabled(props.isEnabled())
                .build();
    }
}
