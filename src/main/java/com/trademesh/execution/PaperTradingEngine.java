package com.trademesh.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.TradeSignal;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.zerodha.options.OptionChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Paper Trading Engine for simulating trades without real money.
 * <p>
 * Manages the complete lifecycle of paper trades:
 * - Executing trades from strategy signals
 * - Marking positions to market (updating P&L)
 * - Closing trades with stop loss/target triggers
 * - Sending Telegram alerts for all events
 * <p>
 * Uses Redis for fetching current option prices and PostgreSQL for persistence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingEngine {

    // Lot sizes from application.yml
    private static final Map<String, Integer> LOT_SIZES = Map.of(
            "NIFTY50", 75,
            "NIFTYBANK", 30
    );
    private final PaperTradeRepository paperTradeRepository;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final TelegramNotificationBot telegramNotificationBot;
    private final ObjectMapper objectMapper;
    private final StrategiesConfig strategiesConfig;

    /**
     * Executes a trade from a strategy signal.
     * <p>
     * Process:
     * 1. Check if signal is executable
     * 2. Check if an OPEN trade already exists for this strategy today
     * 3. Create PaperTrade entity from signal
     * 4. Save to PostgreSQL
     * 5. Send Telegram alert
     *
     * @param result the strategy result containing the signal
     * @return Mono emitting the created PaperTrade
     */
    public Mono<PaperTrade> executeTrade(StrategyResult result) {
        log.info("Executing paper trade from strategy result");

        // Check if signal is executable
        if (!result.getIsExecutable()) {
            log.debug("Signal not executable, skipping: {}", result.getSkipReason());
            return Mono.empty();
        }

        TradeSignal signal = result.getSignal();
        if (signal == null) {
            log.warn("No signal found in executable result");
            return Mono.empty();
        }

        // Check if an OPEN trade already exists for this strategy today
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return paperTradeRepository.existsByStrategyNameAndTradeDateAndStatus(
                signal.getStrategyName(),
                today,
                TradeStatus.OPEN)
                .flatMap(hasOpenTrade -> {
                    if (hasOpenTrade) {
                        log.info("Open trade already exists for {} today, skipping entry", signal.getStrategyName());
                        return Mono.empty();
                    }

                    // Create PaperTrade entity using factory method
                    LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
                    PaperTrade trade = PaperTrade.createNew();
                    trade.setTradeDate(today);
                    trade.setSymbol(signal.getSymbol());
                    trade.setStrategyName(signal.getStrategyName());
                    trade.setStatus(TradeStatus.OPEN);
                    trade.setLegs(signal.getLegs());
                    trade.setEntryCredit(signal.getExpectedCredit());
                    trade.setCurrentValue(signal.getExpectedCredit());
                    trade.setUnrealizedPnl(0.0);
                    trade.setEntryTime(now);
                    trade.setCreatedAt(now);
                    trade.setUpdatedAt(now);

                    // Save to PostgreSQL
                    return paperTradeRepository.save(trade)
                            .flatMap(savedTrade -> {
                                // Send Telegram alert
                                String alert = formatEntryAlert(savedTrade, signal);
                                return telegramNotificationBot.sendAlert(createAlert(savedTrade, alert))
                                        .then(Mono.just(savedTrade));
                            })
                            .doOnSuccess(savedTrade -> log.info("Paper trade executed: {} for {}", savedTrade.getId(), savedTrade.getSymbol()))
                            .doOnError(error -> log.error("Error executing paper trade", error));
                });
    }

    /**
     * Marks a paper trade to market (updates current value and P&L).
     * <p>
     * Process:
     * 1. Fetch current option prices from Redis
     * 2. Calculate current value of position
     * 3. Update unrealizedPnl
     * 4. Check strategy-specific exit conditions
     * 5. Save updated trade or close if exit triggered
     *
     * @param trade the paper trade to mark to market
     * @return Mono emitting the updated PaperTrade
     */
    public Mono<PaperTrade> markToMarket(PaperTrade trade) {
        if (trade.getStatus() != TradeStatus.OPEN) {
            log.debug("Trade {} is not open, skipping mark-to-market", trade.getId());
            return Mono.just(trade);
        }

        log.debug("Marking to market for trade: {}", trade.getId());

        // Strategy-specific mark-to-market logic
        if ("IRON_CONDOR".equals(trade.getStrategyName())) {
            return markToMarketIronCondor(trade);
        }

        if ("BULL_PUT_SPREAD".equals(trade.getStrategyName())) {
            return markToMarketSpread(trade);
        }

        if ("BEAR_CALL_SPREAD".equals(trade.getStrategyName())) {
            return markToMarketSpread(trade);
        }

        // Default mark-to-market for other strategies
        return fetchCurrentPrices(trade)
                .flatMap(currentValue -> {
                    trade.setCurrentValue(currentValue);
                    trade.setUnrealizedPnl(trade.getEntryCredit() - currentValue);
                    trade.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

                    // Check stop loss: if currentValue > entryCredit × 2
                    if (currentValue > trade.getEntryCredit() * 2) {
                        log.info("Stop loss triggered for trade {}: currentValue={}, threshold={}",
                                trade.getId(), currentValue, trade.getEntryCredit() * 2);
                        return closeTrade(trade, "STOP_LOSS");
                    }

                    // Check target: if currentValue < entryCredit × 0.5
                    if (currentValue < trade.getEntryCredit() * 0.5) {
                        log.info("Target hit for trade {}: currentValue={}, threshold={}",
                                trade.getId(), currentValue, trade.getEntryCredit() * 0.5);
                        return closeTrade(trade, "TARGET_HIT");
                    }

                    // Save updated trade
                    return paperTradeRepository.save(trade);
                })
                .doOnError(error -> log.error("Error marking to market for trade: {}", trade.getId(), error));
    }

    /**
     * Iron Condor specific mark-to-market logic.
     * Calculates P&L per leg and checks Iron Condor exit conditions.
     *
     * @param trade the Iron Condor paper trade
     * @return Mono emitting the updated or closed PaperTrade
     */
    private Mono<PaperTrade> markToMarketIronCondor(PaperTrade trade) {
        return calculateIronCondorPnl(trade)
                .flatMap(pnl -> {
                    trade.setUnrealizedPnl(pnl.doubleValue());
                    trade.setCurrentValue(trade.getEntryCredit() - pnl.doubleValue());
                    trade.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

                    com.trademesh.strategy.config.StrategyProperties cfg = strategiesConfig.getStrategy("IRON_CONDOR");

                    if (shouldExitIronCondor(trade, pnl, cfg)) {
                        return closeIronCondorTrade(trade);
                    }

                    return paperTradeRepository.save(trade);
                })
                .doOnError(error -> log.error("Error in Iron Condor mark-to-market for trade: {}", trade.getId(), error));
    }

    /**
     * Calculates Iron Condor P&L by analyzing all 4 legs.
     * SELL legs profit when premium decays (entry > current).
     * BUY legs profit when premium appreciates (current > entry).
     *
     * @param trade the Iron Condor paper trade
     * @return Mono emitting the unrealized P&L in points
     */
    private Mono<BigDecimal> calculateIronCondorPnl(PaperTrade trade) {
        try {
            List<TradeLeg> legs = trade.getLegs();
            if (legs == null || legs.isEmpty()) {
                log.warn("No legs found for Iron Condor trade {}", trade.getId());
                return Mono.just(BigDecimal.ZERO);
            }

            String redisKey = "optionchain:" + trade.getSymbol();
            Integer lotSize = LOT_SIZES.getOrDefault(trade.getSymbol(), 75);

            return redisTemplate.opsForValue().get(redisKey)
                    .flatMap(chainJson -> {
                        try {
                            OptionChain chainData = objectMapper.readValue(chainJson, OptionChain.class);
                            BigDecimal totalPnl = BigDecimal.ZERO;

                            for (TradeLeg leg : legs) {
                                double currentLtp = extractLTP(chainData, leg);
                                double entryPrice = leg.getLimitPrice();
                                BigDecimal legPnl;

                                if (leg.getAction() == Action.SELL) {
                                    // SELL legs: profit when premium decays
                                    legPnl = BigDecimal.valueOf((entryPrice - currentLtp) * leg.getQuantity() * lotSize);
                                } else {
                                    // BUY legs: profit when premium appreciates
                                    legPnl = BigDecimal.valueOf((currentLtp - entryPrice) * leg.getQuantity() * lotSize);
                                }

                                totalPnl = totalPnl.add(legPnl);
                                log.debug("Iron Condor leg P&L: {} {} @ {} (entry: {}, current: {}, pnl: {})",
                                        leg.getAction(), leg.getOptionType(), leg.getStrikePrice(),
                                        entryPrice, currentLtp, legPnl);
                            }

                            log.debug("Iron Condor total unrealized P&L for trade {}: {}", trade.getId(), totalPnl);
                            return Mono.just(totalPnl);
                        } catch (Exception e) {
                            log.warn("Error deserializing option chain for Iron Condor P&L, using entry price fallback: {}", e.getMessage());
                            return Mono.just(BigDecimal.ZERO);
                        }
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("Redis key missing for Iron Condor P&L: {}, using entry price fallback", redisKey);
                        return Mono.just(BigDecimal.ZERO);
                    }));
        } catch (Exception e) {
            log.error("Error calculating Iron Condor P&L for trade: {}", trade.getId(), e);
            return Mono.just(BigDecimal.ZERO);
        }
    }

    /**
     * Checks if Iron Condor position should be exited based on multiple conditions.
     * Exit priority: leg breach → stop loss → profit target → force close time.
     *
     * @param trade         the Iron Condor paper trade
     * @param unrealizedPnl the current unrealized P&L
     * @param cfg           the Iron Condor strategy configuration
     * @return true if any exit condition is met, false otherwise
     */
    private boolean shouldExitIronCondor(PaperTrade trade, BigDecimal unrealizedPnl,
                                         com.trademesh.strategy.config.StrategyProperties cfg) {
        // TODO: VIX panic check (requires VixMonitorService injection)
        // if (vixMonitor.getCurrentVix() > cfg.getExtraDouble("vix-panic-threshold", 25.0)) {
        //     log.warn("VIX panic detected for Iron Condor trade {}", trade.getId());
        //     trade.setExitReason("VIX_PANIC");
        //     return true;
        // }

        // Check 1: Leg breach (spot crosses short strike)
        List<TradeLeg> legs = trade.getLegs();
        if (legs != null && !legs.isEmpty()) {
            // Find short strikes (SELL legs)
            Double shortCallStrike = null;
            Double shortPutStrike = null;

            for (TradeLeg leg : legs) {
                if (leg.getAction() == Action.SELL) {
                    if (leg.getOptionType() == OptionType.CE) {
                        shortCallStrike = leg.getStrikePrice();
                    } else if (leg.getOptionType() == OptionType.PE) {
                        shortPutStrike = leg.getStrikePrice();
                    }
                }
            }

            // Get current spot price from Redis (simplified: use first strike as reference)
            // In production, fetch actual spot from market data
            // For now, we'll skip spot check and rely on other conditions
        }

        // Check 2: Stop loss
        BigDecimal entryCredit = BigDecimal.valueOf(trade.getEntryCredit());
        double slPct = cfg.getStopLossPercent() / 100.0;
        BigDecimal stopLossThreshold = entryCredit.multiply(BigDecimal.valueOf(slPct)).negate();

        if (unrealizedPnl.compareTo(stopLossThreshold) < 0) {
            log.warn("Stop loss triggered for Iron Condor trade {}: pnl={}, threshold={}",
                    trade.getId(), unrealizedPnl, stopLossThreshold);
            trade.setExitReason("STOPLOSS_HIT");
            return true;
        }

        // Check 3: Profit target
        double targetPct = cfg.getTargetProfitPercent() / 100.0;
        BigDecimal profitTarget = entryCredit.multiply(BigDecimal.valueOf(targetPct));

        if (unrealizedPnl.compareTo(profitTarget) >= 0) {
            log.info("Profit target hit for Iron Condor trade {}: pnl={}, target={}",
                    trade.getId(), unrealizedPnl, profitTarget);
            trade.setExitReason("TARGET_HIT");
            return true;
        }

        // Check 4: Force close time
        String forceCloseTimeStr = cfg.getExtraDouble("force-close-time", 0) > 0 ?
                "15:15" : null;
        if (forceCloseTimeStr != null) {
            try {
                LocalTime forceCloseTime = LocalTime.parse(forceCloseTimeStr, DateTimeFormatter.ofPattern("HH:mm"));
                LocalTime currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
                if (currentTime.isAfter(forceCloseTime) || currentTime.equals(forceCloseTime)) {
                    log.info("Force close time reached for Iron Condor trade {}: current={}, forceClose={}",
                            trade.getId(), currentTime, forceCloseTime);
                    trade.setExitReason("FORCE_CLOSE_EOD");
                    return true;
                }
            } catch (Exception e) {
                log.warn("Error parsing force close time: {}", e.getMessage());
            }
        }

        return false;
    }

    /**
     * Closes an Iron Condor trade with Telegram alert.
     *
     * @param trade the Iron Condor paper trade to close
     * @return Mono emitting the closed PaperTrade
     */
    private Mono<PaperTrade> closeIronCondorTrade(PaperTrade trade) {
        trade.setStatus(TradeStatus.CLOSED);
        trade.setExitTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        trade.setRealizedPnl(trade.getUnrealizedPnl());
        trade.setUnrealizedPnl(0.0);
        trade.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

        return paperTradeRepository.save(trade)
                .flatMap(closedTrade -> {
                    String alert = formatIronCondorExitAlert(closedTrade);
                    return telegramNotificationBot.sendAlert(createAlert(closedTrade, alert))
                            .then(Mono.just(closedTrade));
                })
                .doOnSuccess(closedTrade -> log.info("Iron Condor trade closed: {} with P&L: {}, reason: {}",
                        closedTrade.getId(), closedTrade.getRealizedPnl(), closedTrade.getExitReason()))
                .doOnError(error -> log.error("Error closing Iron Condor trade: {}", trade.getId(), error));
    }

    /**
     * Formats Iron Condor exit alert for Telegram.
     *
     * @param trade the closed Iron Condor trade
     * @return formatted alert message
     */
    private String formatIronCondorExitAlert(PaperTrade trade) {
        Integer lotSize = LOT_SIZES.getOrDefault(trade.getSymbol(), 75);
        Double pnlAmount = trade.getRealizedPnl() * lotSize;

        long durationMinutes = java.time.temporal.ChronoUnit.MINUTES.between(trade.getEntryTime(), trade.getExitTime());
        String duration = formatDuration(durationMinutes);

        String emoji = trade.getRealizedPnl() >= 0 ? "✅" : "❌";

        return String.format(
                "%s <b>Iron Condor CLOSED</b>\n\n" +
                        "Symbol: <b>%s</b>\n" +
                        "Exit Reason: <b>%s</b>\n" +
                        "Realized P&L: <b>%.2f pts</b> (₹ <b>%.2f</b>)\n" +
                        "Entry Credit: <b>%.2f pts</b>\n" +
                        "Duration: <code>%s</code>\n" +
                        "Entry Time: <code>%s</code>\n" +
                        "Exit Time: <code>%s</code>",
                emoji,
                trade.getSymbol(),
                trade.getExitReason(),
                trade.getRealizedPnl(),
                pnlAmount,
                trade.getEntryCredit(),
                duration,
                trade.getEntryTime(),
                trade.getExitTime()
        );
    }

    /**
     * Closes a paper trade and realizes P&L.
     * <p>
     * Process:
     * 1. Fetch final prices from Redis
     * 2. Calculate realized P&L
     * 3. Update status to CLOSED
     * 4. Set exit time and reason
     * 5. Save to PostgreSQL
     * 6. Send Telegram alert
     *
     * @param trade  the paper trade to close
     * @param reason the reason for closing (e.g., "STOP_LOSS", "TARGET_HIT", "MANUAL_EXIT")
     * @return Mono emitting the closed PaperTrade
     */
    public Mono<PaperTrade> closeTrade(PaperTrade trade, String reason) {
        log.info("Closing paper trade: {} with reason: {}", trade.getId(), reason);

        // Fetch final prices and calculate realized P&L
        return fetchCurrentPrices(trade)
                .flatMap(finalValue -> {
                    trade.setCurrentValue(finalValue);
                    trade.setRealizedPnl(trade.getEntryCredit() - finalValue);
                    trade.setStatus(TradeStatus.CLOSED);
                    trade.setExitTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
                    trade.setExitReason(reason);
                    trade.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

                    // Save to PostgreSQL
                    return paperTradeRepository.save(trade);
                })
                .flatMap(closedTrade -> {
                    // Send Telegram alert
                    String alert = formatExitAlert(closedTrade);
                    return telegramNotificationBot.sendAlert(createAlert(closedTrade, alert))
                            .then(Mono.just(closedTrade));
                })
                .doOnSuccess(closedTrade -> log.info("Paper trade closed: {} with P&L: {}",
                        closedTrade.getId(), closedTrade.getRealizedPnl()))
                .doOnError(error -> log.error("Error closing paper trade: {}", trade.getId(), error));
    }

    /**
     * Fetches current option prices from Redis for all legs of a trade.
     * Reads the option chain data from Redis using key optionchain:{symbol},
     * then loops through all legs to extract LTPs and sum them.
     *
     * @param trade the paper trade
     * @return Mono emitting the current value in points
     */
    private Mono<Double> fetchCurrentPrices(PaperTrade trade) {
        try {
            List<TradeLeg> legs = trade.getLegs();
            if (legs == null) {
                legs = List.of();
            }
            final List<TradeLeg> finalLegs = legs;
            log.debug("Fetching current prices for {} legs", finalLegs.size());

            if (finalLegs.isEmpty()) {
                return Mono.just(trade.getEntryCredit());
            }

            // Single Redis call to fetch option chain data
            String redisKey = "optionchain:" + trade.getSymbol();
            final Double entryCredit = trade.getEntryCredit();
            final Double currentValue = trade.getCurrentValue();
            return redisTemplate.opsForValue().get(redisKey)
                    .flatMap(chainJson -> {
                        try {
                            // Deserialize option chain once
                            OptionChain chainData = objectMapper.readValue(chainJson, OptionChain.class);
                            log.debug("Retrieved option chain for {} with {} strikes", trade.getSymbol(), chainData.getStrikes().size());

                            // Loop through legs and sum all LTPs
                            double totalCurrentValue = 0.0;
                            for (TradeLeg leg : finalLegs) {
                                double ltp = extractLTP(chainData, leg);
                                totalCurrentValue += ltp;
                            }

                            log.debug("Calculated total current value for trade {}: {}", trade.getId(), totalCurrentValue);
                            return Mono.just(totalCurrentValue);
                        } catch (Exception e) {
                            log.error("Error deserializing option chain for {}: {}", trade.getSymbol(), e.getMessage());
                            return Mono.just(currentValue != null ? currentValue : entryCredit);
                        }
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("Redis key missing: {}, falling back to current value", redisKey);
                        return Mono.just(trade.getCurrentValue() != null ? trade.getCurrentValue() : trade.getEntryCredit());
                    }));
        } catch (Exception e) {
            log.error("Error fetching current prices for trade: {}", trade.getId(), e);
            return Mono.just(trade.getCurrentValue() != null ? trade.getCurrentValue() : trade.getEntryCredit());
        }
    }

    /**
     * Extracts the LTP (Last Traded Price) for a trade leg from the option chain data.
     * Finds the matching strike by comparing strike prices (int comparison),
     * then returns the appropriate LTP based on option type (CE or PE).
     *
     * @param chainData the option chain data containing all strikes
     * @param leg       the trade leg to extract LTP for
     * @return the LTP value, or the leg's limit price if strike not found
     */
    private double extractLTP(OptionChain chainData, TradeLeg leg) {
        if (chainData == null || chainData.getStrikes() == null || chainData.getStrikes().isEmpty()) {
            log.warn("Option chain data is empty, using limit price for leg with strike {}", leg.getStrikePrice());
            return leg.getLimitPrice();
        }

        int legStrikeInt = leg.getStrikePrice().intValue();

        // Find matching strike in chain data
        for (var strike : chainData.getStrikes()) {
            if (strike.getStrikePrice().intValue() == legStrikeInt) {
                // Extract appropriate LTP based on option type
                if (leg.getOptionType() == OptionType.CE) {
                    if (strike.getCallLTP() == null) {
                        log.warn("Call LTP is null for strike {}, using limit price", legStrikeInt);
                        return leg.getLimitPrice();
                    }
                    double callLTP = strike.getCallLTP().doubleValue();
                    log.debug("Found call LTP {} for strike {}", callLTP, legStrikeInt);
                    return callLTP;
                } else if (leg.getOptionType() == OptionType.PE) {
                    if (strike.getPutLTP() == null) {
                        log.warn("Put LTP is null for strike {}, using limit price", legStrikeInt);
                        return leg.getLimitPrice();
                    }
                    double putLTP = strike.getPutLTP().doubleValue();
                    log.debug("Found put LTP {} for strike {}", putLTP, legStrikeInt);
                    return putLTP;
                }
            }
        }

        // Strike not found in chain data
        log.warn("Strike {} not found in option chain for {}, using limit price", legStrikeInt, chainData.getSymbol());
        return leg.getLimitPrice();
    }


    /**
     * Formats entry alert message for Telegram.
     * For Iron Condor, includes rich P&L matrix and market context.
     *
     * @param trade  the paper trade
     * @param signal the trade signal
     * @return formatted alert message
     */
    private String formatEntryAlert(PaperTrade trade, TradeSignal signal) {
        try {
            // Iron Condor specific rich alert
            if ("IRON_CONDOR".equals(trade.getStrategyName())) {
                return formatIronCondorEntryAlert(trade, signal);
            }

            // Default alert for other strategies
            List<TradeLeg> legs = trade.getLegs();
            if (legs == null) {
                legs = List.of();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📄 <b>Paper Trade ENTERED</b>\n\n");
            sb.append(String.format("Strategy: <b>%s</b>\n", trade.getStrategyName()));
            sb.append(String.format("Symbol: <b>%s</b>\n", trade.getSymbol()));
            sb.append(String.format("Entry Time: <code>%s</code>\n\n", trade.getEntryTime()));

            // Add leg details
            for (TradeLeg leg : legs) {
                sb.append(String.format("• %s %s @ %.0f (%s)\n",
                        leg.getAction(),
                        leg.getOptionType(),
                        leg.getStrikePrice(),
                        leg.getQuantity() + "L"
                ));
            }

            sb.append(String.format("\nCredit Collected: <b>%.2f pts</b>\n", trade.getEntryCredit()));
            sb.append(String.format("Max Loss: <b>%.2f pts</b>\n", signal.getMaxLoss()));
            sb.append(String.format("Confidence: <b>%s</b>\n", signal.getConfidence()));

            return sb.toString();
        } catch (Exception e) {
            log.error("Error formatting entry alert", e);
            return String.format("📄 Paper Trade ENTERED\nStrategy: %s\nSymbol: %s\nCredit: %.2f pts",
                    trade.getStrategyName(), trade.getSymbol(), trade.getEntryCredit());
        }
    }

    /**
     * Formats Iron Condor entry alert with full P&L matrix and market context.
     *
     * @param trade  the Iron Condor paper trade
     * @param signal the Iron Condor trade signal
     * @return formatted alert message
     */
    private String formatIronCondorEntryAlert(PaperTrade trade, TradeSignal signal) {
        try {
            Map<String, String> metadata = signal.getMetadata();
            if (metadata == null || metadata.isEmpty()) {
                log.warn("No metadata found for Iron Condor signal, using basic alert");
                return formatBasicIronCondorAlert(trade, signal);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🟢 <b>Iron Condor ENTERED</b>\n");
            sb.append(String.format("Symbol: <b>%s</b>\n", trade.getSymbol()));
            sb.append(String.format("Expiry: <b>%s</b>\n", metadata.getOrDefault("expiryDate", "N/A")));
            sb.append(String.format("Confidence Score: <b>%s/100</b>\n\n", metadata.getOrDefault("confidenceScore", "N/A")));

            // Strikes section
            sb.append("📊 <b>Strikes:</b>\n");
            sb.append(String.format("SELL %sCE @ %s\n",
                    metadata.getOrDefault("shortCallStrike", "N/A"),
                    metadata.getOrDefault("shortCallLtp", "N/A")));
            sb.append(String.format("SELL %sPE @ %s\n",
                    metadata.getOrDefault("shortPutStrike", "N/A"),
                    metadata.getOrDefault("shortPutLtp", "N/A")));
            sb.append(String.format("BUY  %sCE @ %s\n",
                    metadata.getOrDefault("longCallStrike", "N/A"),
                    metadata.getOrDefault("longCallLtp", "N/A")));
            sb.append(String.format("BUY  %sPE @ %s\n\n",
                    metadata.getOrDefault("longPutStrike", "N/A"),
                    metadata.getOrDefault("longPutLtp", "N/A")));

            // P&L Matrix section
            sb.append("💰 <b>P&L Matrix:</b>\n");
            sb.append(String.format("Net Credit: <b>%s pts</b>\n",
                    metadata.getOrDefault("netCredit", "N/A")));
            sb.append(String.format("Max Profit: <b>₹%s</b>\n",
                    metadata.getOrDefault("maxProfit", "N/A")));
            sb.append(String.format("Max Loss: <b>₹%s</b>\n",
                    metadata.getOrDefault("maxLoss", "N/A")));
            sb.append(String.format("Upper BE: <b>%s</b>\n",
                    metadata.getOrDefault("upperBreakeven", "N/A")));
            sb.append(String.format("Lower BE: <b>%s</b>\n",
                    metadata.getOrDefault("lowerBreakeven", "N/A")));
            sb.append(String.format("R:R Ratio: <b>%s</b>\n\n",
                    metadata.getOrDefault("riskRewardRatio", "N/A")));

            // Exit Levels section
            sb.append("🎯 <b>Exit Levels:</b>\n");
            sb.append(String.format("Target: <b>₹%s</b>\n",
                    metadata.getOrDefault("adjustedProfitTarget", "N/A")));
            sb.append(String.format("SL: <b>₹%s</b>\n\n",
                    metadata.getOrDefault("adjustedStopLoss", "N/A")));

            // Market Context section
            sb.append("📈 <b>Market Context:</b>\n");
            sb.append(String.format("ATR%%: <b>%s%%</b> | ",
                    metadata.getOrDefault("atrPct", "N/A")));
            sb.append(String.format("IV/HV: <b>%s</b> | ",
                    metadata.getOrDefault("ivHvRatio", "N/A")));
            sb.append(String.format("IVR: <b>%s</b> | ",
                    metadata.getOrDefault("ivr", "N/A")));
            sb.append(String.format("DTE: <b>%s</b>",
                    metadata.getOrDefault("dte", "N/A")));

            return sb.toString();
        } catch (Exception e) {
            log.error("Error formatting Iron Condor entry alert", e);
            return formatBasicIronCondorAlert(trade, signal);
        }
    }

    /**
     * Formats basic Iron Condor alert when metadata is unavailable.
     *
     * @param trade  the Iron Condor paper trade
     * @param signal the Iron Condor trade signal
     * @return formatted alert message
     */
    private String formatBasicIronCondorAlert(PaperTrade trade, TradeSignal signal) {
        List<TradeLeg> legs = trade.getLegs();
        if (legs == null) {
            legs = List.of();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🟢 <b>Iron Condor ENTERED</b>\n\n");
        sb.append(String.format("Symbol: <b>%s</b>\n", trade.getSymbol()));
        sb.append(String.format("Entry Time: <code>%s</code>\n\n", trade.getEntryTime()));

        // Add leg details
        for (TradeLeg leg : legs) {
            sb.append(String.format("• %s %s @ %.0f (%s)\n",
                    leg.getAction(),
                    leg.getOptionType(),
                    leg.getStrikePrice(),
                    leg.getQuantity() + "L"
            ));
        }

        sb.append(String.format("\nNet Credit: <b>%.2f pts</b>\n", trade.getEntryCredit()));
        sb.append(String.format("Max Loss: <b>%.2f pts</b>\n", signal.getMaxLoss()));

        return sb.toString();
    }

    /**
     * Formats exit alert message for Telegram.
     *
     * @param trade the closed paper trade
     * @return formatted alert message
     */
    private String formatExitAlert(PaperTrade trade) {
        Integer lotSize = LOT_SIZES.getOrDefault(trade.getSymbol(), 1);
        Double pnlAmount = trade.getRealizedPnl() * lotSize;

        long durationMinutes = java.time.temporal.ChronoUnit.MINUTES.between(trade.getEntryTime(), trade.getExitTime());
        String duration = formatDuration(durationMinutes);

        String emoji = trade.getRealizedPnl() >= 0 ? "✅" : "❌";

        return String.format(
                "%s <b>Paper Trade CLOSED</b>\n\n" +
                        "Strategy: <b>%s</b>\n" +
                        "Symbol: <b>%s</b>\n" +
                        "Exit Reason: <b>%s</b>\n\n" +
                        "P&L: <b>%.2f pts</b> (₹ <b>%.2f</b>)\n" +
                        "Duration: <code>%s</code>\n" +
                        "Exit Time: <code>%s</code>",
                emoji,
                trade.getStrategyName(),
                trade.getSymbol(),
                trade.getExitReason(),
                trade.getRealizedPnl(),
                pnlAmount,
                duration,
                trade.getExitTime()
        );
    }

    /**
     * Formats duration in human-readable format.
     *
     * @param minutes the duration in minutes
     * @return formatted duration string
     */
    private String formatDuration(long minutes) {
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        long mins = minutes % 60;
        return String.format("%dh %dm", hours, mins);
    }

    /**
     * Bull Put Spread and Bear Call Spread specific mark-to-market logic.
     * Calculates P&L for 2-leg spreads and checks exit conditions.
     *
     * @param trade the spread paper trade
     * @return Mono emitting the updated or closed PaperTrade
     */
    private Mono<PaperTrade> markToMarketSpread(PaperTrade trade) {
        return calculateSpreadPnl(trade)
                .flatMap(pnl -> {
                    trade.setUnrealizedPnl(pnl.doubleValue());
                    trade.setCurrentValue(trade.getEntryCredit() - pnl.doubleValue());
                    trade.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

                    String strategyName = trade.getStrategyName();
                    com.trademesh.strategy.config.StrategyProperties cfg = strategiesConfig.getStrategy(strategyName);

                    if (shouldExitSpread(trade, pnl, cfg)) {
                        return closeSpreadTrade(trade);
                    }

                    return paperTradeRepository.save(trade);
                })
                .doOnError(error -> log.error("Error in spread mark-to-market for trade: {}", trade.getId(), error));
    }

    /**
     * Calculates spread P&L by analyzing both legs.
     * SELL legs profit when premium decays (entry > current).
     * BUY legs profit when premium appreciates (current > entry).
     *
     * @param trade the spread paper trade
     * @return Mono emitting the unrealized P&L in points
     */
    private Mono<BigDecimal> calculateSpreadPnl(PaperTrade trade) {
        try {
            List<TradeLeg> legs = trade.getLegs();
            if (legs == null || legs.isEmpty()) {
                log.warn("No legs found for spread trade {}", trade.getId());
                return Mono.just(BigDecimal.ZERO);
            }

            String redisKey = "optionchain:" + trade.getSymbol();
            Integer lotSize = LOT_SIZES.getOrDefault(trade.getSymbol(), 75);

            return redisTemplate.opsForValue().get(redisKey)
                    .flatMap(chainJson -> {
                        try {
                            OptionChain chainData = objectMapper.readValue(chainJson, OptionChain.class);
                            BigDecimal totalPnl = BigDecimal.ZERO;

                            for (TradeLeg leg : legs) {
                                double currentLtp = extractLTP(chainData, leg);
                                double entryPrice = leg.getLimitPrice();
                                BigDecimal legPnl;

                                if (leg.getAction() == Action.SELL) {
                                    // SELL legs: profit when premium decays
                                    legPnl = BigDecimal.valueOf((entryPrice - currentLtp) * leg.getQuantity() * lotSize);
                                } else {
                                    // BUY legs: profit when premium appreciates
                                    legPnl = BigDecimal.valueOf((currentLtp - entryPrice) * leg.getQuantity() * lotSize);
                                }

                                totalPnl = totalPnl.add(legPnl);
                                log.debug("Spread leg P&L: {} {} @ {} (entry: {}, current: {}, pnl: {})",
                                        leg.getAction(), leg.getOptionType(), leg.getStrikePrice(),
                                        entryPrice, currentLtp, legPnl);
                            }

                            log.debug("Spread total unrealized P&L for trade {}: {}", trade.getId(), totalPnl);
                            return Mono.just(totalPnl);
                        } catch (Exception e) {
                            log.warn("Error deserializing option chain for spread P&L, using entry price fallback: {}", e.getMessage());
                            return Mono.just(BigDecimal.ZERO);
                        }
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("Redis key missing for spread P&L: {}, using entry price fallback", redisKey);
                        return Mono.just(BigDecimal.ZERO);
                    }));
        } catch (Exception e) {
            log.error("Error calculating spread P&L for trade: {}", trade.getId(), e);
            return Mono.just(BigDecimal.ZERO);
        }
    }

    /**
     * Checks if spread position should be exited based on multiple conditions.
     * Exit priority: stop loss → profit target → force close time.
     *
     * @param trade         the spread paper trade
     * @param unrealizedPnl the current unrealized P&L
     * @param cfg           the strategy configuration
     * @return true if any exit condition is met, false otherwise
     */
    private boolean shouldExitSpread(PaperTrade trade, BigDecimal unrealizedPnl,
                                     com.trademesh.strategy.config.StrategyProperties cfg) {
        // Check 1: Profit target
        BigDecimal entryCredit = BigDecimal.valueOf(trade.getEntryCredit());
        double targetPct = cfg.getTargetProfitPercent() / 100.0;
        BigDecimal profitTarget = entryCredit.multiply(BigDecimal.valueOf(targetPct));

        if (unrealizedPnl.compareTo(profitTarget) >= 0) {
            log.info("Profit target hit for spread trade {}: pnl={}, target={}",
                    trade.getId(), unrealizedPnl, profitTarget);
            trade.setExitReason("TARGET_HIT");
            return true;
        }

        // Check 2: Stop loss (based on wing width and max loss)
        int wingWidth = cfg.getExtraInt("wing-width", 100);
        Integer lotSize = LOT_SIZES.getOrDefault(trade.getSymbol(), 75);
        BigDecimal maxLoss = BigDecimal.valueOf(wingWidth * lotSize);
        double slPct = cfg.getStopLossPercent() / 100.0;
        BigDecimal stopLossThreshold = maxLoss.multiply(BigDecimal.valueOf(slPct)).negate();

        if (unrealizedPnl.compareTo(stopLossThreshold) <= 0) {
            log.warn("Stop loss triggered for spread trade {}: pnl={}, threshold={}",
                    trade.getId(), unrealizedPnl, stopLossThreshold);
            trade.setExitReason("STOPLOSS_HIT");
            return true;
        }

        // Check 3: Force close time
        try {
            LocalTime forceCloseTime = LocalTime.parse("15:15", DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime currentTime = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            if (currentTime.isAfter(forceCloseTime) || currentTime.equals(forceCloseTime)) {
                log.info("Force close time reached for spread trade {}: current={}, forceClose={}",
                        trade.getId(), currentTime, forceCloseTime);
                trade.setExitReason("FORCE_CLOSE_EOD");
                return true;
            }
        } catch (Exception e) {
            log.warn("Error parsing force close time: {}", e.getMessage());
        }

        return false;
    }

    /**
     * Closes a spread trade with Telegram alert.
     *
     * @param trade the spread paper trade to close
     * @return Mono emitting the closed PaperTrade
     */
    private Mono<PaperTrade> closeSpreadTrade(PaperTrade trade) {
        trade.setStatus(TradeStatus.CLOSED);
        trade.setExitTime(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        trade.setRealizedPnl(trade.getUnrealizedPnl());
        trade.setUnrealizedPnl(0.0);
        trade.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));

        return paperTradeRepository.save(trade)
                .flatMap(closedTrade -> {
                    String alert = formatExitAlert(closedTrade);
                    return telegramNotificationBot.sendAlert(createAlert(closedTrade, alert))
                            .then(Mono.just(closedTrade));
                })
                .doOnSuccess(closedTrade -> log.info("Spread trade closed: {} with P&L: {}, reason: {}",
                        closedTrade.getId(), closedTrade.getRealizedPnl(), closedTrade.getExitReason()))
                .doOnError(error -> log.error("Error closing spread trade: {}", trade.getId(), error));
    }

    /**
     * Creates an Alert object for the AlertService.
     *
     * @param trade   the paper trade
     * @param message the alert message
     * @return Alert object
     */
    private com.trademesh.alert.Alert createAlert(PaperTrade trade, String message) {
        return com.trademesh.alert.Alert.builder()
                .type(com.trademesh.alert.AlertType.PAPER_TRADE)
                .tradingSymbol(trade.getSymbol())
                .message(message)
                .triggeredAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .build();
    }
}
