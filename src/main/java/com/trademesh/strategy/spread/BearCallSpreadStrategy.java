package com.trademesh.strategy.spread;

import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligence;
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
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionStrike;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
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

        // Get data from MarketIntelligence directly
        double spotPrice = intelligence.getSpotPrice();
        OptionChain chain = intelligence.getOptionChain();

        if (chain == null) {
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("Option chain unavailable")
                    .build());
        }

        // DTE check
        int dte = (int) ChronoUnit.DAYS.between(LocalDate.now(), chain.getExpiryDate());

        int dteMin = cfg.getExtraInt("dte-min", 3);
        int dteMax = cfg.getExtraInt("dte-max", 15);
        if (dte < dteMin || dte > dteMax) {
            return Mono.just(StrategyResult.builder()
                    .isExecutable(false)
                    .skipReason("DTE " + dte + " outside range [" + dteMin + ", " + dteMax + "]")
                    .build());
        }

        // Strike selection
        double tte = dte / 365.0;
        double targetDelta = cfg.getExtraDouble("target-delta", 0.30);
        int wingWidth = cfg.getExtraInt("wing-width", 100);
        int lotSize = 75;
        int lots = cfg.getMaxLots();

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

        // P&L matrix
        BigDecimal maxProfit = netCredit.multiply(BigDecimal.valueOf(lotSize * lots));
        BigDecimal maxLoss = BigDecimal.valueOf(wingWidth - netCredit.doubleValue())
                .multiply(BigDecimal.valueOf(lotSize * lots));
        BigDecimal breakeven = shortCallStrikePrice.add(netCredit);

        // Build trade legs
        List<TradeLeg> legs = new ArrayList<>();

        // SELL short call
        legs.add(TradeLeg.builder()
                .instrument(buildInstrumentName("NIFTY50", chain.getExpiryDate(), shortCallStrikePrice, OptionType.CE))
                .action(Action.SELL)
                .optionType(OptionType.CE)
                .strikePrice(shortCallStrikePrice.doubleValue())
                .expiryDate(chain.getExpiryDate())
                .quantity(lots)
                .limitPrice(shortCallLtp.doubleValue())
                .legType(LegType.ENTRY)
                .build());

        // BUY long call
        legs.add(TradeLeg.builder()
                .instrument(buildInstrumentName("NIFTY50", chain.getExpiryDate(), longCallStrikePrice, OptionType.CE))
                .action(Action.BUY)
                .optionType(OptionType.CE)
                .strikePrice(longCallStrikePrice.doubleValue())
                .expiryDate(chain.getExpiryDate())
                .quantity(lots)
                .limitPrice(longCallLtp.doubleValue())
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

         return Mono.just(StrategyResult.builder()
                 .signal(com.trademesh.strategy.TradeSignal.builder()
                         .symbol("NIFTY50")
                         .strategyName(getName())
                         .signalType(com.trademesh.strategy.enums.SignalType.ENTER)
                         .legs(legs)
                         .expectedCredit(netCredit.doubleValue())
                         .maxLoss(maxLoss.doubleValue())
                         .reason("Bear Call Spread: " + shortCallStrikePrice + "CE / " +
                                 longCallStrikePrice + "CE | Credit: " + netCredit)
                         .confidence(Confidence.MEDIUM)
                         .metadata(metadata)
                         .build())
                .isExecutable(true)
                .build());
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
