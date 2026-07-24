package com.trademesh.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.config.LotSizeService;
import com.trademesh.intelligence.vix.VixMonitorService;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.TradeSignal;
import com.trademesh.strategy.config.StrategiesConfig;
import com.trademesh.strategy.enums.Action;
import com.trademesh.strategy.enums.OptionType;
import com.trademesh.trading.MarketCalendarService;
import com.trademesh.zerodha.options.OptionChain;
import com.trademesh.zerodha.options.OptionChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            "NIFTY50", 65,
            "NIFTYBANK", 30
    );
    private final PaperTradeRepository paperTradeRepository;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final TelegramNotificationBot telegramNotificationBot;
    private final ObjectMapper objectMapper;
    private final StrategiesConfig strategiesConfig;
    private final LotSizeService lotSizeService;
    private final VixMonitorService vixMonitorService;
    private final MarketCalendarService marketCalendarService;
    private final OptionChainService optionChainService;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Kolkata"));

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

        // Check if an OPEN trade already exists for this strategy (regardless of date)
        // This prevents duplicate entries across multiple days once positions can survive overnight
        return paperTradeRepository.existsByStrategyNameAndStatus(
                        signal.getStrategyName(),
                        TradeStatus.OPEN)
                .flatMap(hasOpenTrade -> {
                    if (hasOpenTrade) {
                        log.info("Open trade already exists for {}, skipping entry", signal.getStrategyName());
                        return Mono.empty();
                    }

                    // Create PaperTrade entity using factory method
                    LocalDateTime now = LocalDateTime.now(this.clock);
                    LocalDate today = LocalDate.now(this.clock);
                    PaperTrade trade = PaperTrade.createNew();
                    trade.setTradeDate(today);
                    trade.setSymbol(signal.getSymbol());
                    trade.setStrategyName(signal.getStrategyName());
                    trade.setStatus(TradeStatus.OPEN);
                    trade.setLegs(signal.getLegs());
                    trade.setEntryCredit(BigDecimal.valueOf(signal.getExpectedCredit()));
                    trade.setCurrentValue(BigDecimal.valueOf(signal.getExpectedCredit()));
                    trade.setUnrealizedPnl(BigDecimal.ZERO);
                    trade.setMaxLoss(signal.getMaxLoss() != null ? BigDecimal.valueOf(signal.getMaxLoss()) : null);
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
     * Determines if a mark-to-market exit trigger (STOP_LOSS or TARGET_HIT) is met.
     * Pure function with no side effects.
     *
     * @param currentValueBD the current value in BigDecimal
     * @param entryCreditBD  the entry credit in BigDecimal
     * @return Optional.of(STOP_LOSS) if currentValue > entryCredit × 2,
     * Optional.of(TARGET_HIT) if currentValue < entryCredit × 0.5,
     * Optional.empty() otherwise
     */
    Optional<MarkToMarketExitTrigger> determineMarkToMarketExitTrigger(
            BigDecimal currentValueBD, BigDecimal entryCreditBD) {
        if (currentValueBD.compareTo(entryCreditBD.multiply(BigDecimal.valueOf(2))) > 0) {
            return Optional.of(MarkToMarketExitTrigger.STOP_LOSS);
        }
        if (currentValueBD.compareTo(entryCreditBD.multiply(BigDecimal.valueOf(0.5))) < 0) {
            return Optional.of(MarkToMarketExitTrigger.TARGET_HIT);
        }
        return Optional.empty();
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
                    trade.setUnrealizedPnl(trade.getEntryCredit().subtract(currentValue));
                    trade.setUpdatedAt(LocalDateTime.now(this.clock));

                    // Check for STOP_LOSS or TARGET_HIT using extracted decision method
                    Optional<MarkToMarketExitTrigger> trigger =
                            determineMarkToMarketExitTrigger(currentValue, trade.getEntryCredit());
                    if (trigger.isPresent()) {
                        String reason = trigger.get().name(); // "STOP_LOSS" or "TARGET_HIT"
                        log.info("{} triggered for trade {}: currentValue={}, entryCredit={}",
                                reason, trade.getId(), currentValue, trade.getEntryCredit());
                        return closeTrade(trade, reason);
                    }
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
        return Mono.zip(
                        calculateIronCondorPnl(trade),
                        vixMonitorService.fetchCurrentVix()
                                .map(vixData -> vixData.getCurrentVix())
                                .defaultIfEmpty(-1.0)
                )
                .flatMap(tuple -> {
                    BigDecimal pnl = tuple.getT1();
                    double currentVix = tuple.getT2();

                    trade.setUnrealizedPnl(pnl);
                    // Derive lot count from trade legs (all legs share same quantity)
                    int lots = (trade.getLegs() != null && !trade.getLegs().isEmpty())
                            ? trade.getLegs().get(0).getQuantity()
                            : 1;
                    // Scale entryCredit by lot count before subtraction
                    BigDecimal scaledEntryCredit = trade.getEntryCredit().multiply(BigDecimal.valueOf(lots));
                    trade.setCurrentValue(scaledEntryCredit.subtract(pnl));
                    trade.setUpdatedAt(LocalDateTime.now(this.clock));

                    com.trademesh.strategy.config.StrategyProperties cfg = strategiesConfig.getStrategy("IRON_CONDOR");
                    LocalTime currentTime = LocalTime.now(this.clock);

                    return shouldExitIronCondor(trade, pnl, cfg, currentVix, currentTime)
                            .flatMap(shouldExit -> {
                                if (shouldExit) {
                                    return closeIronCondorTrade(trade);
                                }
                                return paperTradeRepository.save(trade);
                            });
                })
                .onErrorResume(error -> {
                    log.error("Error in Iron Condor mark-to-market for trade: {}", trade.getId(), error);
                    return Mono.empty();
                });
    }

    /**
     * Calculates Iron Condor P&L by analyzing all 4 legs.
     * SELL legs profit when premium decays (entry > current).
     * BUY legs profit when premium appreciates (current > entry).
     *
     * @param trade the Iron Condor paper trade
     * @return Mono emitting the unrealized P&L in points
     */
    Mono<BigDecimal> calculateIronCondorPnl(PaperTrade trade) {
        try {
            List<TradeLeg> legs = trade.getLegs();
            if (legs == null || legs.isEmpty()) {
                log.warn("No legs found for Iron Condor trade {}, falling back to last known unrealized P&L: {}",
                        trade.getId(), trade.getUnrealizedPnl());
                return Mono.just(trade.getUnrealizedPnl());
            }

            return optionChainService.fetchOptionChain(trade.getSymbol())
                    .flatMap(chainData -> {
                        BigDecimal totalPnl = BigDecimal.ZERO;

                        for (TradeLeg leg : legs) {
                            BigDecimal currentLtp = extractLTP(chainData, leg);
                            BigDecimal entryPrice = leg.getLimitPrice();
                            BigDecimal legPnl;

                            if (leg.getAction() == Action.SELL) {
                                // SELL legs: profit when premium decays
                                legPnl = entryPrice.subtract(currentLtp).multiply(BigDecimal.valueOf(leg.getQuantity()));
                            } else {
                                // BUY legs: profit when premium appreciates
                                legPnl = currentLtp.subtract(entryPrice).multiply(BigDecimal.valueOf(leg.getQuantity()));
                            }

                            totalPnl = totalPnl.add(legPnl);
                            log.debug("Iron Condor leg P&L: {} {} @ {} (entry: {}, current: {}, pnl: {})",
                                    leg.getAction(), leg.getOptionType(), leg.getStrikePrice(),
                                    entryPrice, currentLtp, legPnl);
                        }

                        log.debug("Iron Condor total unrealized P&L for trade {}: {}", trade.getId(), totalPnl);
                        return Mono.just(totalPnl);
                    })
                    .onErrorResume(e -> {
                        log.warn("Failed to fetch option chain for Iron Condor P&L (trade {}), falling back to last known unrealized P&L: {} ({})",
                                trade.getId(), trade.getUnrealizedPnl(), e.getMessage());
                        return Mono.just(trade.getUnrealizedPnl());
                    });
        } catch (Exception e) {
            log.error("Error calculating Iron Condor P&L for trade: {}, falling back to last known unrealized P&L: {}",
                    trade.getId(), trade.getUnrealizedPnl(), e);
            return Mono.just(trade.getUnrealizedPnl());
        }
    }

    /**
     * Checks if Iron Condor position should be exited based on multiple conditions.
     * Exit priority: VIX panic → DTE exit → leg breach → stop loss → profit target.
     *
     * @param trade         the Iron Condor paper trade
     * @param unrealizedPnl the current unrealized P&L
     * @param cfg           the Iron Condor strategy configuration
     * @param currentVix    the current VIX value (or -1.0 if unavailable)
     * @param currentTime   the current time for dte-exit-earliest-time check
     * @return true if any exit condition is met, false otherwise
     */
    Mono<Boolean> shouldExitIronCondor(PaperTrade trade, BigDecimal unrealizedPnl,
                                       com.trademesh.strategy.config.StrategyProperties cfg,
                                       double currentVix, LocalTime currentTime) {
        // Check 0: VIX panic exit (highest priority)
        double vixPanicThreshold = cfg.getExtraDouble("vix-panic-threshold", 25.0);
        if (currentVix > 0 && currentVix > vixPanicThreshold) {
            log.warn("VIX panic exit triggered for {} trade {}: vix={}, threshold={}",
                    cfg.getName(), trade.getId(), currentVix, vixPanicThreshold);
            trade.setExitReason("VIX_PANIC");
            return Mono.just(true);
        }
        // currentVix <= 0 means VIX data was unavailable — skip this check silently

        // Check 1: DTE exit (scheduled wind-down)
        if (isDteExitDue(trade, cfg, 2, currentTime)) {
            trade.setExitReason("DTE_EXIT");
            return Mono.just(true);
        }

        // Check 2: Leg breach (spot crosses short strike) — reactive check
        return isLegBreach(trade)
                .flatMap(isBreach -> {
                    if (isBreach) {
                        trade.setExitReason("LEG_BREACH");
                        return Mono.just(true);
                    }

                    // Derive lot count from trade legs (all legs share same quantity)
                    int lots = (trade.getLegs() != null && !trade.getLegs().isEmpty())
                            ? trade.getLegs().get(0).getQuantity()
                            : 1;

                    // Check 3: Stop loss
                    BigDecimal entryCredit = trade.getEntryCredit()
                            .multiply(BigDecimal.valueOf(lots));
                    double slPct = cfg.getStopLossPercent() / 100.0;
                    BigDecimal stopLossThreshold = entryCredit.multiply(BigDecimal.valueOf(slPct)).negate();

                    if (unrealizedPnl.compareTo(stopLossThreshold) < 0) {
                        log.warn("Stop loss triggered for Iron Condor trade {}: pnl={}, threshold={}",
                                trade.getId(), unrealizedPnl, stopLossThreshold);
                        trade.setExitReason("STOPLOSS_HIT");
                        return Mono.just(true);
                    }

                    // Check 4: Profit target
                    double targetPct = cfg.getTargetProfitPercent() / 100.0;
                    BigDecimal profitTarget = entryCredit.multiply(BigDecimal.valueOf(targetPct));

                    if (unrealizedPnl.compareTo(profitTarget) >= 0) {
                        log.info("Profit target hit for Iron Condor trade {}: pnl={}, target={}",
                                trade.getId(), unrealizedPnl, profitTarget);
                        trade.setExitReason("TARGET_HIT");
                        return Mono.just(true);
                    }

                    return Mono.just(false);
                });
    }

    /**
     * Closes an Iron Condor trade with Telegram alert.
     * <p>
     * Process:
     * 1. Re-fetch trade from DB to ensure current status (guard against concurrent closes)
     * 2. Check if trade is still OPEN (skip if already closed)
     * 3. Update status to CLOSED
     * 4. Set exit time and realized P&L
     * 5. Save to PostgreSQL
     * 6. Send Telegram alert
     *
     * @param trade the Iron Condor paper trade to close
     * @return Mono emitting the closed PaperTrade
     */
    Mono<PaperTrade> closeIronCondorTrade(PaperTrade trade) {
        log.info("Closing Iron Condor trade: {} with reason: {}", trade.getId(), trade.getExitReason());

        // Re-fetch trade from DB to ensure we have current state, not stale in-memory object
        // This guards against race condition where two concurrent chains (MTM cycle + scheduled exit)
        // both read the same trade as OPEN and attempt to close it simultaneously
        return paperTradeRepository.findById(trade.getId())
                .flatMap(freshTrade -> {
                    // Guard: check if trade is still OPEN
                    if (freshTrade.getStatus() != TradeStatus.OPEN) {
                        log.warn("closeIronCondorTrade() called on trade {} with status {} "
                                        + "(expected OPEN) — skipping duplicate close attempt",
                                freshTrade.getId(), freshTrade.getStatus());
                        return Mono.just(freshTrade);
                    }

                    // Proceed with close logic on the original trade parameter
                    // (which already has correct unrealizedPnl, exitReason, legs, etc. from the reactive chain)
                    trade.setStatus(TradeStatus.CLOSED);
                    trade.setExitTime(LocalDateTime.now(this.clock));
                    trade.setRealizedPnl(trade.getUnrealizedPnl());
                    trade.setUnrealizedPnl(BigDecimal.ZERO);
                    trade.setUpdatedAt(LocalDateTime.now(this.clock));

                    return paperTradeRepository.save(trade)
                            .flatMap(closedTrade -> {
                                String alert = formatIronCondorExitAlert(closedTrade);
                                return telegramNotificationBot.sendAlert(createAlert(closedTrade, alert))
                                        .then(Mono.just(closedTrade));
                            })
                            .doOnSuccess(closedTrade -> log.info("Iron Condor trade closed: {} with P&L: {}, reason: {}",
                                    closedTrade.getId(), closedTrade.getRealizedPnl(), closedTrade.getExitReason()))
                            .doOnError(error -> log.error("Error closing Iron Condor trade: {}", trade.getId(), error));
                })
                .onErrorResume(e -> {
                    log.error("Error re-fetching trade {} for close operation: {}", trade.getId(), e.getMessage());
                    return Mono.just(trade);
                });
    }

    /**
     * Formats Iron Condor exit alert for Telegram.
     *
     * @param trade the closed Iron Condor trade
     * @return formatted alert message
     */
    private String formatIronCondorExitAlert(PaperTrade trade) {
        Double pnlAmount = trade.getRealizedPnl().doubleValue() * lotSizeService.getLotSize(trade.getSymbol());

        long durationMinutes = java.time.temporal.ChronoUnit.MINUTES.between(trade.getEntryTime(), trade.getExitTime());
        String duration = formatDuration(durationMinutes);

        String emoji = trade.getRealizedPnl().doubleValue() >= 0 ? "✅" : "❌";

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
                trade.getRealizedPnl().doubleValue(),
                pnlAmount,
                trade.getEntryCredit().doubleValue(),
                duration,
                trade.getEntryTime(),
                trade.getExitTime()
        );
    }

    /**
     * Closes a paper trade and realizes P&L.
     * <p>
     * Process:
     * 1. Re-fetch trade from DB to ensure current status (guard against concurrent closes)
     * 2. Check if trade is still OPEN (skip if already closed)
     * 3. Fetch final prices from Redis
     * 4. Calculate realized P&L
     * 5. Update status to CLOSED
     * 6. Set exit time and reason
     * 7. Save to PostgreSQL
     * 8. Send Telegram alert
     *
     * @param trade  the paper trade to close
     * @param reason the reason for closing (e.g., "STOP_LOSS", "TARGET_HIT", "MANUAL_EXIT")
     * @return Mono emitting the closed PaperTrade
     */
    public Mono<PaperTrade> closeTrade(PaperTrade trade, String reason) {
        log.info("Closing paper trade: {} with reason: {}", trade.getId(), reason);

        // Re-fetch trade from DB to ensure we have current state, not stale in-memory object
        // This guards against race condition where two concurrent chains (MTM cycle + scheduled exit)
        // both read the same trade as OPEN and attempt to close it simultaneously
        return paperTradeRepository.findById(trade.getId())
                .flatMap(freshTrade -> {
                    // Guard: check if trade is still OPEN
                    if (freshTrade.getStatus() != TradeStatus.OPEN) {
                        log.warn("closeTrade() called on trade {} with status {} "
                                        + "(expected OPEN) — skipping duplicate close attempt",
                                freshTrade.getId(), freshTrade.getStatus());
                        return Mono.just(freshTrade);
                    }

                    // Fetch final prices and calculate realized P&L
                    return fetchCurrentPrices(freshTrade)
                            .flatMap(finalValue -> {
                                freshTrade.setCurrentValue(finalValue);
                                freshTrade.setRealizedPnl(freshTrade.getEntryCredit().subtract(finalValue));
                                freshTrade.setStatus(TradeStatus.CLOSED);
                                freshTrade.setExitTime(LocalDateTime.now(this.clock));
                                freshTrade.setExitReason(reason);
                                freshTrade.setUpdatedAt(LocalDateTime.now(this.clock));

                                // Save to PostgreSQL
                                return paperTradeRepository.save(freshTrade);
                            })
                            .flatMap(closedTrade -> {
                                // Send Telegram alert
                                String alert = formatExitAlert(closedTrade);
                                return telegramNotificationBot.sendAlert(createAlert(closedTrade, alert))
                                        .then(Mono.just(closedTrade));
                            })
                            .doOnSuccess(closedTrade -> log.info("Paper trade closed: {} with P&L: {}",
                                    closedTrade.getId(), closedTrade.getRealizedPnl()))
                            .doOnError(error -> log.error("Error closing paper trade: {}", freshTrade.getId(), error));
                })
                .onErrorResume(e -> {
                    log.error("Error re-fetching trade {} for close operation: {}", trade.getId(), e.getMessage());
                    return Mono.just(trade);
                });
    }

    /**
     * Fetches current option prices from OptionChainService for all legs of a trade.
     * Uses the cache-first-with-live-Kite-fallback mechanism provided by OptionChainService.
     * Loops through all legs to extract LTPs and applies signed netting.
     *
     * @param trade the paper trade
     * @return Mono emitting the current value in points as BigDecimal
     */
    private Mono<BigDecimal> fetchCurrentPrices(PaperTrade trade) {
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

            final BigDecimal entryCredit = trade.getEntryCredit();
            final BigDecimal currentValue = trade.getCurrentValue();

            return optionChainService.fetchOptionChain(trade.getSymbol())
                    .flatMap(chainData -> {
                        log.debug("Retrieved option chain for {} with {} strikes", trade.getSymbol(), chainData.getStrikes().size());

                        // Loop through legs and apply signed netting
                        BigDecimal totalCurrentValue = BigDecimal.ZERO;
                        for (TradeLeg leg : finalLegs) {
                            BigDecimal ltp = extractLTP(chainData, leg);
                            if (leg.getAction() == Action.SELL) {
                                totalCurrentValue = totalCurrentValue.add(ltp);
                            } else {
                                totalCurrentValue = totalCurrentValue.subtract(ltp);
                            }
                        }

                        log.debug("Calculated total current value for trade {}: {}", trade.getId(), totalCurrentValue);
                        return Mono.just(totalCurrentValue);
                    })
                    .onErrorResume(e -> {
                        log.warn("Failed to fetch option chain for current prices (trade {}), falling back to last known value: {} ({})",
                                trade.getId(), currentValue != null ? currentValue : entryCredit, e.getMessage());
                        return Mono.just(currentValue != null ? currentValue : entryCredit);
                    });
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
     * @return the LTP value as BigDecimal, or the leg's limit price if strike not found
     */
    public BigDecimal extractLTP(OptionChain chainData, TradeLeg leg) {
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
                    BigDecimal callLTP = strike.getCallLTP();
                    log.debug("Found call LTP {} for strike {}", callLTP, legStrikeInt);
                    return callLTP;
                } else if (leg.getOptionType() == OptionType.PE) {
                    if (strike.getPutLTP() == null) {
                        log.warn("Put LTP is null for strike {}, using limit price", legStrikeInt);
                        return leg.getLimitPrice();
                    }
                    BigDecimal putLTP = strike.getPutLTP();
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

            // Calculate total quantity (lots × lot size)
            int lotSize = lotSizeService.getLotSize(trade.getSymbol());
            int lotsPerLeg = legs.isEmpty() ? 0 : legs.get(0).getQuantity();
            int totalQuantity = lotsPerLeg * lotSize;

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
            sb.append(String.format("Total Quantity: <b>%d</b> (%d lots × %d)\n", totalQuantity, lotsPerLeg, lotSize));
            sb.append(String.format("Max Loss: <b>₹%.2f</b>\n", signal.getMaxLoss()));
            sb.append(String.format("Max Profit: <b>₹%s</b>\n", signal.getMetadata() != null ? signal.getMetadata().getOrDefault("maxProfit", "N/A") : "N/A"));
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
        sb.append(String.format("Max Loss: <b>₹%.2f</b>\n", signal.getMaxLoss()));

        return sb.toString();
    }

    /**
     * Formats exit alert message for Telegram.
     *
     * @param trade the closed paper trade
     * @return formatted alert message
     */
    private String formatExitAlert(PaperTrade trade) {
        Double pnlAmount = trade.getRealizedPnl().doubleValue() * lotSizeService.getLotSize(trade.getSymbol());

        long durationMinutes = java.time.temporal.ChronoUnit.MINUTES.between(trade.getEntryTime(), trade.getExitTime());
        String duration = formatDuration(durationMinutes);

        String emoji = trade.getRealizedPnl().doubleValue() >= 0 ? "✅" : "❌";

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
                trade.getRealizedPnl().doubleValue(),
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
        return Mono.zip(
                        calculateSpreadPnl(trade),
                        vixMonitorService.fetchCurrentVix()
                                .map(vixData -> vixData.getCurrentVix())
                                .defaultIfEmpty(-1.0)
                )
                .flatMap(tuple -> {
                    BigDecimal pnl = tuple.getT1();
                    double currentVix = tuple.getT2();

                    trade.setUnrealizedPnl(pnl);
                    // Derive lot count from trade legs (all legs share same quantity)
                    int lots = (trade.getLegs() != null && !trade.getLegs().isEmpty())
                            ? trade.getLegs().get(0).getQuantity()
                            : 1;
                    // Scale entryCredit by lot count before subtraction
                    BigDecimal scaledEntryCredit = trade.getEntryCredit().multiply(BigDecimal.valueOf(lots));
                    trade.setCurrentValue(scaledEntryCredit.subtract(pnl));
                    trade.setUpdatedAt(LocalDateTime.now(this.clock));

                    String strategyName = trade.getStrategyName();
                    com.trademesh.strategy.config.StrategyProperties cfg = strategiesConfig.getStrategy(strategyName);

                    return shouldExitSpread(trade, pnl, cfg, currentVix, LocalTime.now(this.clock))
                            .flatMap(shouldExit -> {
                                if (shouldExit) {
                                    return closeSpreadTrade(trade);
                                }
                                return paperTradeRepository.save(trade);
                            });
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
    Mono<BigDecimal> calculateSpreadPnl(PaperTrade trade) {
        try {
            List<TradeLeg> legs = trade.getLegs();
            if (legs == null || legs.isEmpty()) {
                log.warn("No legs found for spread trade {}, falling back to last known unrealized P&L: {}",
                        trade.getId(), trade.getUnrealizedPnl());
                return Mono.just(trade.getUnrealizedPnl());
            }

            return optionChainService.fetchOptionChain(trade.getSymbol())
                    .flatMap(chainData -> {
                        BigDecimal totalPnl = BigDecimal.ZERO;

                        for (TradeLeg leg : legs) {
                            BigDecimal currentLtp = extractLTP(chainData, leg);
                            BigDecimal entryPrice = leg.getLimitPrice();
                            BigDecimal legPnl;

                            if (leg.getAction() == Action.SELL) {
                                // SELL legs: profit when premium decays
                                legPnl = entryPrice.subtract(currentLtp).multiply(BigDecimal.valueOf(leg.getQuantity()));
                            } else {
                                // BUY legs: profit when premium appreciates
                                legPnl = currentLtp.subtract(entryPrice).multiply(BigDecimal.valueOf(leg.getQuantity()));
                            }

                            totalPnl = totalPnl.add(legPnl);
                            log.debug("Spread leg P&L: {} {} @ {} (entry: {}, current: {}, pnl: {})",
                                    leg.getAction(), leg.getOptionType(), leg.getStrikePrice(),
                                    entryPrice, currentLtp, legPnl);
                        }

                        log.debug("Spread total unrealized P&L for trade {}: {}", trade.getId(), totalPnl);
                        return Mono.just(totalPnl);
                    })
                    .onErrorResume(e -> {
                        log.warn("Failed to fetch option chain for spread P&L (trade {}), falling back to last known unrealized P&L: {} ({})",
                                trade.getId(), trade.getUnrealizedPnl(), e.getMessage());
                        return Mono.just(trade.getUnrealizedPnl());
                    });
        } catch (Exception e) {
            log.error("Error calculating spread P&L for trade: {}, falling back to last known unrealized P&L: {}",
                    trade.getId(), trade.getUnrealizedPnl(), e);
            return Mono.just(trade.getUnrealizedPnl());
        }
    }

    /**
     * Checks if a trade should be exited based on DTE (Days To Expiry).
     * Implements a time gate on the threshold day to prevent premature exits.
     * <p>
     * Logic:
     * - If tradingDaysRemaining < threshold: exit immediately (past threshold day)
     * - If tradingDaysRemaining == threshold: exit only if currentTime >= dte-exit-earliest-time
     * - If tradingDaysRemaining > threshold: do not exit (not yet at threshold)
     *
     * @param trade            the paper trade
     * @param cfg              the strategy configuration
     * @param defaultThreshold the default DTE threshold if config key is missing
     * @param currentTime      the current time for time-gate check on threshold day
     * @return true if DTE exit condition is met, false otherwise
     */
    public boolean isDteExitDue(PaperTrade trade,
                                com.trademesh.strategy.config.StrategyProperties cfg,
                                int defaultThreshold,
                                LocalTime currentTime) {
        List<TradeLeg> legs = trade.getLegs();

        // Guard: no legs
        if (legs == null || legs.isEmpty()) {
            log.warn("No legs found for trade {}, skipping DTE exit check", trade.getId());
            return false;
        }

        LocalDate expiryDate = legs.get(0).getExpiryDate();

        // Guard: no expiry date
        if (expiryDate == null) {
            log.warn("Expiry date is null for trade {}, skipping DTE exit check", trade.getId());
            return false;
        }

        // Read threshold from config with explicit default (never hardcode)
        int dteExitThreshold = cfg.getExtraInt("dte-exit-threshold", defaultThreshold);
        int tradingDaysRemaining = marketCalendarService.countTradingDays(
                LocalDate.now(this.clock), expiryDate);

        // If we haven't reached the threshold day yet, don't exit
        if (tradingDaysRemaining > dteExitThreshold) {
            return false;
        }

        // If we're past the threshold day, exit immediately (no time gate)
        if (tradingDaysRemaining < dteExitThreshold) {
            log.info("DTE exit due for trade {}: {} trading days remaining (past threshold: {})",
                    trade.getId(), tradingDaysRemaining, dteExitThreshold);
            return true;
        }

        // We're exactly at the threshold day — apply time gate
        // Only exit if current time >= dte-exit-earliest-time
        String dteExitEarliestTimeStr = cfg.getExtraString("dte-exit-earliest-time", "10:00");

        try {
            LocalTime dteExitEarliestTime = LocalTime.parse(dteExitEarliestTimeStr,
                    DateTimeFormatter.ofPattern("HH:mm"));

            if (currentTime.isBefore(dteExitEarliestTime)) {
                log.debug("DTE exit blocked for trade {}: threshold day but time gate not yet reached. " +
                        "Current: {}, Gate: {}", trade.getId(), currentTime, dteExitEarliestTime);
                return false;
            }

            log.info("DTE exit due for trade {}: threshold day ({} trading days) and time gate passed. " +
                    "Current: {}, Gate: {}", trade.getId(), tradingDaysRemaining, currentTime, dteExitEarliestTime);
            return true;
        } catch (Exception e) {
            log.warn("Error parsing dte-exit-earliest-time '{}' for trade {}: {}. " +
                    "Allowing exit as fallback.", dteExitEarliestTimeStr, trade.getId(), e.getMessage());
            return true;
        }
    }

    /**
     * Checks if spread position should be exited based on multiple conditions.
     * Exit priority: VIX panic → DTE exit → leg breach → stop loss → profit target.
     * Fully reactive — returns Mono<Boolean>.
     *
     * @param trade         the spread paper trade
     * @param unrealizedPnl the current unrealized P&L
     * @param cfg           the strategy configuration
     * @param currentVix    the current VIX value (or -1.0 if unavailable)
     * @param currentTime   the current time for dte-exit-earliest-time check
     * @return Mono emitting true if any exit condition is met, false otherwise
     */
    Mono<Boolean> shouldExitSpread(PaperTrade trade, BigDecimal unrealizedPnl,
                                   com.trademesh.strategy.config.StrategyProperties cfg,
                                   double currentVix, LocalTime currentTime) {
        // Check 0: VIX panic exit (highest priority)
        double vixPanicThreshold = cfg.getExtraDouble("vix-panic-threshold", 25.0);
        if (currentVix > 0 && currentVix > vixPanicThreshold) {
            log.warn("VIX panic exit triggered for {} trade {}: vix={}, threshold={}",
                    cfg.getName(), trade.getId(), currentVix, vixPanicThreshold);
            trade.setExitReason("VIX_PANIC");
            return Mono.just(true);
        }
        // currentVix <= 0 means VIX data was unavailable — skip this check silently

        // Check 1: DTE exit (scheduled wind-down)
        if (isDteExitDue(trade, cfg, 1, currentTime)) {
            trade.setExitReason("DTE_EXIT");
            return Mono.just(true);
        }

        // Check 2: Leg breach (spot crosses short strike) — reactive check
        return isLegBreach(trade)
                .flatMap(isBreach -> {
                    if (isBreach) {
                        trade.setExitReason("LEG_BREACH");
                        return Mono.just(true);
                    }

                    // Derive lot count from trade legs (all legs share same quantity)
                    int lots = (trade.getLegs() != null && !trade.getLegs().isEmpty())
                            ? trade.getLegs().get(0).getQuantity()
                            : 1;

                    // Check 3: Stop loss (based on entry credit, matching shouldExitIronCondor pattern)
                    BigDecimal entryCredit = trade.getEntryCredit()
                            .multiply(BigDecimal.valueOf(lots));
                    double slPct = cfg.getStopLossPercent() / 100.0;
                    BigDecimal stopLossThreshold = entryCredit.multiply(BigDecimal.valueOf(slPct)).negate();

                    if (unrealizedPnl.compareTo(stopLossThreshold) <= 0) {
                        log.warn("Stop loss triggered for spread trade {}: pnl={}, threshold={}",
                                trade.getId(), unrealizedPnl, stopLossThreshold);
                        trade.setExitReason("STOPLOSS_HIT");
                        return Mono.just(true);
                    }

                    // Check 4: Profit target
                    double targetPct = cfg.getTargetProfitPercent() / 100.0;
                    BigDecimal profitTarget = entryCredit.multiply(BigDecimal.valueOf(targetPct));

                    if (unrealizedPnl.compareTo(profitTarget) >= 0) {
                        log.info("Profit target hit for spread trade {}: pnl={}, target={}",
                                trade.getId(), unrealizedPnl, profitTarget);
                        trade.setExitReason("TARGET_HIT");
                        return Mono.just(true);
                    }

                    return Mono.just(false);
                })
                .onErrorResume(e -> {
                    log.error("Error in shouldExitSpread for trade {}: {}", trade.getId(), e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Closes a spread trade with Telegram alert.
     * <p>
     * Process:
     * 1. Re-fetch trade from DB to ensure current status (guard against concurrent closes)
     * 2. Check if trade is still OPEN (skip if already closed)
     * 3. Update status to CLOSED
     * 4. Set exit time and realized P&L
     * 5. Save to PostgreSQL
     * 6. Send Telegram alert
     *
     * @param trade the spread paper trade to close
     * @return Mono emitting the closed PaperTrade
     */
    Mono<PaperTrade> closeSpreadTrade(PaperTrade trade) {
        log.info("Closing spread trade: {} with reason: {}", trade.getId(), trade.getExitReason());

        // Re-fetch trade from DB to ensure we have current state, not stale in-memory object
        // This guards against race condition where two concurrent chains (MTM cycle + scheduled exit)
        // both read the same trade as OPEN and attempt to close it simultaneously
        return paperTradeRepository.findById(trade.getId())
                .flatMap(freshTrade -> {
                    // Guard: check if trade is still OPEN
                    if (freshTrade.getStatus() != TradeStatus.OPEN) {
                        log.warn("closeSpreadTrade() called on trade {} with status {} "
                                        + "(expected OPEN) — skipping duplicate close attempt",
                                freshTrade.getId(), freshTrade.getStatus());
                        return Mono.just(freshTrade);
                    }

                    // Proceed with close logic on the original trade parameter
                    // (which already has correct unrealizedPnl, exitReason, legs, etc. from the reactive chain)
                    trade.setStatus(TradeStatus.CLOSED);
                    trade.setExitTime(LocalDateTime.now(this.clock));
                    trade.setRealizedPnl(trade.getUnrealizedPnl());
                    trade.setUnrealizedPnl(BigDecimal.ZERO);
                    trade.setUpdatedAt(LocalDateTime.now(this.clock));

                    return paperTradeRepository.save(trade)
                            .flatMap(closedTrade -> {
                                String alert = formatExitAlert(closedTrade);
                                return telegramNotificationBot.sendAlert(createAlert(closedTrade, alert))
                                        .then(Mono.just(closedTrade));
                            })
                            .doOnSuccess(closedTrade -> log.info("Spread trade closed: {} with P&L: {}, reason: {}",
                                    closedTrade.getId(), closedTrade.getRealizedPnl(), closedTrade.getExitReason()))
                            .doOnError(error -> log.error("Error closing spread trade: {}", trade.getId(), error));
                })
                .onErrorResume(e -> {
                    log.error("Error re-fetching trade {} for close operation: {}", trade.getId(), e.getMessage());
                    return Mono.just(trade);
                });
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
                .triggeredAt(LocalDateTime.now(this.clock))
                .build();
    }

    /**
     * Calculates live unrealized P&L for a position at read time from Redis.
     * This is a read-only operation that does NOT write to the database.
     * Used by REST endpoints to provide fresh P&L values without persisting.
     * <p>
     * Process:
     * 1. Fetch current option chain from Redis using key optionchain:{symbol}
     * 2. Calculate current value by summing LTPs of all legs
     * 3. Calculate unrealized P&L as: entryCredit - currentValue
     * 4. If Redis has no data, gracefully fall back to stored DB value with isPnlLive=false
     *
     * @param trade the paper trade to calculate live P&L for
     * @return Mono emitting LivePnlData with liveUnrealizedPnl and isPnlLive flag
     */
    public Mono<LivePnlData> calculateLiveUnrealizedPnl(PaperTrade trade) {
        if (trade.getStatus() != TradeStatus.OPEN) {
            log.debug("Trade {} is not open, returning stored P&L", trade.getId());
            return Mono.just(LivePnlData.builder()
                    .liveUnrealizedPnl(trade.getUnrealizedPnl().doubleValue())
                    .isPnlLive(false)
                    .build());
        }

        try {
            List<TradeLeg> legs = trade.getLegs();
            if (legs == null || legs.isEmpty()) {
                log.debug("No legs found for trade {}, returning stored P&L", trade.getId());
                return Mono.just(LivePnlData.builder()
                        .liveUnrealizedPnl(trade.getUnrealizedPnl().doubleValue())
                        .isPnlLive(false)
                        .build());
            }

            String redisKey = "optionchain:" + trade.getSymbol();
            final BigDecimal entryCredit = trade.getEntryCredit();
            final BigDecimal storedUnrealizedPnl = trade.getUnrealizedPnl();

            return redisTemplate.opsForValue().get(redisKey)
                    .flatMap(chainJson -> {
                        try {
                            OptionChain chainData = objectMapper.readValue(chainJson, OptionChain.class);
                            log.debug("Retrieved option chain for {} with {} strikes for live P&L calculation",
                                    trade.getSymbol(), chainData.getStrikes().size());

                            // Calculate current value based on strategy type
                            Mono<Double> currentValueMono;
                            if ("IRON_CONDOR".equals(trade.getStrategyName())) {
                                currentValueMono = calculateIronCondorCurrentValue(chainData, legs);
                            } else if ("BULL_PUT_SPREAD".equals(trade.getStrategyName()) ||
                                    "BEAR_CALL_SPREAD".equals(trade.getStrategyName())) {
                                currentValueMono = calculateSpreadCurrentValue(chainData, legs);
                            } else {
                                currentValueMono = calculateDefaultCurrentValue(chainData, legs);
                            }

                            return currentValueMono.map(currentValue -> {
                                int quantity = (legs != null && !legs.isEmpty()) ? legs.get(0).getQuantity() : 1;
                                double scaledEntryCredit = entryCredit.doubleValue() * quantity;
                                double liveUnrealizedPnl = scaledEntryCredit - currentValue;
                                log.debug("Calculated live P&L for trade {}: currentValue={}, liveUnrealizedPnl={}",
                                        trade.getId(), currentValue, liveUnrealizedPnl);
                                return LivePnlData.builder()
                                        .liveUnrealizedPnl(liveUnrealizedPnl)
                                        .isPnlLive(true)
                                        .build();
                            });
                        } catch (Exception e) {
                            log.warn("Error deserializing option chain for live P&L calculation, using stored value: {}",
                                    e.getMessage());
                            return Mono.just(LivePnlData.builder()
                                    .liveUnrealizedPnl(storedUnrealizedPnl.doubleValue())
                                    .isPnlLive(false)
                                    .build());
                        }
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.debug("Redis key missing for live P&L: {}, using stored value", redisKey);
                        return Mono.just(LivePnlData.builder()
                                .liveUnrealizedPnl(storedUnrealizedPnl.doubleValue())
                                .isPnlLive(false)
                                .build());
                    }));
        } catch (Exception e) {
            log.error("Error calculating live P&L for trade: {}", trade.getId(), e);
            return Mono.just(LivePnlData.builder()
                    .liveUnrealizedPnl(trade.getUnrealizedPnl().doubleValue())
                    .isPnlLive(false)
                    .build());
        }
    }

    /**
     * Calculates current value for Iron Condor by applying signed logic across all 4 legs.
     * SELL legs: ADD their LTP (cost to buy back the short position)
     * BUY legs: SUBTRACT their LTP (value received when selling back the long position)
     *
     * @param chainData the option chain data
     * @param legs      the trade legs
     * @return Mono emitting the current value
     */
    Mono<Double> calculateIronCondorCurrentValue(OptionChain chainData, List<TradeLeg> legs) {
        try {
            double totalCurrentValue = 0.0;
            for (TradeLeg leg : legs) {
                double currentLtp = extractLTP(chainData, leg).doubleValue();
                if (leg.getAction() == Action.SELL) {
                    totalCurrentValue += currentLtp * leg.getQuantity();
                } else {
                    totalCurrentValue -= currentLtp * leg.getQuantity();
                }
            }
            return Mono.just(totalCurrentValue);
        } catch (Exception e) {
            log.error("Error calculating Iron Condor current value: {}", e.getMessage());
            return Mono.just(0.0);
        }
    }

    /**
     * Calculates current value for spreads (Bull Put, Bear Call) by applying signed logic.
     * SELL legs: ADD their LTP (cost to buy back the short position)
     * BUY legs: SUBTRACT their LTP (value received when selling back the long position)
     *
     * @param chainData the option chain data
     * @param legs      the trade legs
     * @return Mono emitting the current value
     */
    Mono<Double> calculateSpreadCurrentValue(OptionChain chainData, List<TradeLeg> legs) {
        try {
            double totalCurrentValue = 0.0;
            for (TradeLeg leg : legs) {
                double currentLtp = extractLTP(chainData, leg).doubleValue();
                if (leg.getAction() == Action.SELL) {
                    totalCurrentValue += currentLtp * leg.getQuantity();
                } else {
                    totalCurrentValue -= currentLtp * leg.getQuantity();
                }
            }
            return Mono.just(totalCurrentValue);
        } catch (Exception e) {
            log.error("Error calculating spread current value: {}", e.getMessage());
            return Mono.just(0.0);
        }
    }

    /**
     * Calculates current value for default strategies by applying signed logic.
     * SELL legs: ADD their LTP (cost to buy back the short position)
     * BUY legs: SUBTRACT their LTP (value received when selling back the long position)
     *
     * @param chainData the option chain data
     * @param legs      the trade legs
     * @return Mono emitting the current value
     */
    Mono<Double> calculateDefaultCurrentValue(OptionChain chainData, List<TradeLeg> legs) {
        try {
            double totalCurrentValue = 0.0;
            for (TradeLeg leg : legs) {
                double currentLtp = extractLTP(chainData, leg).doubleValue();
                if (leg.getAction() == Action.SELL) {
                    totalCurrentValue += currentLtp * leg.getQuantity();
                } else {
                    totalCurrentValue -= currentLtp * leg.getQuantity();
                }
            }
            return Mono.just(totalCurrentValue);
        } catch (Exception e) {
            log.error("Error calculating default current value: {}", e.getMessage());
            return Mono.just(0.0);
        }
    }

    /**
     * Checks if a trade has breached a short leg strike (spot crosses short strike).
     * <p>
     * Strategy-specific logic:
     * - IRON_CONDOR: breach if spot < short put strike OR spot > short call strike
     * - BULL_PUT_SPREAD: breach if spot < short put strike
     * - BEAR_CALL_SPREAD: breach if spot > short call strike
     * - EXPIRY_DAY_STRANGLE: NOT APPLICABLE — no short-strike breach concept
     * <p>
     * Spot price is fetched from Redis key "price:NIFTY50" (cached by ZerodhaTickerService).
     * If Redis key is missing, gracefully skips check with WARN log.
     * <p>
     * Fully reactive — no .block() calls.
     *
     * @param trade the paper trade to check for leg breach
     * @return Mono emitting true if leg breach detected, false otherwise (including if spot price unavailable)
     */
    private Mono<Boolean> isLegBreach(PaperTrade trade) {
        String strategyName = trade.getStrategyName();

        // EXPIRY_DAY_STRANGLE explicitly excluded — no short-strike breach concept
        if ("EXPIRY_DAY_STRANGLE".equals(strategyName)) {
            return Mono.just(false);
        }

        // Only check for strategies with short legs
        if (!("IRON_CONDOR".equals(strategyName) || "BULL_PUT_SPREAD".equals(strategyName) || "BEAR_CALL_SPREAD".equals(strategyName))) {
            return Mono.just(false);
        }

        List<TradeLeg> legs = trade.getLegs();
        if (legs == null || legs.isEmpty()) {
            log.warn("No legs found for trade {}, skipping leg breach check", trade.getId());
            return Mono.just(false);
        }

        // Fetch spot price from Redis reactively
        String spotPriceKey = "price:NIFTY50";
        return redisTemplate.opsForValue().get(spotPriceKey)
                .flatMap(spotPriceStr -> {
                    if (spotPriceStr == null || spotPriceStr.isEmpty()) {
                        log.warn("Spot price not available in Redis key {}, skipping leg breach check", spotPriceKey);
                        return Mono.just(false);
                    }

                    double spotPrice;
                    try {
                        spotPrice = Double.parseDouble(spotPriceStr);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid spot price format in Redis: {}, skipping leg breach check", spotPriceStr);
                        return Mono.just(false);
                    }

                    // Check breach based on strategy type
                    if ("IRON_CONDOR".equals(strategyName)) {
                        // Find short put and short call legs
                        BigDecimal shortPutStrike = null;
                        BigDecimal shortCallStrike = null;

                        for (TradeLeg leg : legs) {
                            if (leg.getAction() == Action.SELL) {
                                if (leg.getOptionType() == OptionType.PE) {
                                    shortPutStrike = leg.getStrikePrice();
                                } else if (leg.getOptionType() == OptionType.CE) {
                                    shortCallStrike = leg.getStrikePrice();
                                }
                            }
                        }

                        // Breach if spot < short put strike OR spot > short call strike
                        if (shortPutStrike != null && BigDecimal.valueOf(spotPrice).compareTo(shortPutStrike) < 0) {
                            log.warn("Iron Condor leg breach: spot {} < short put strike {}", spotPrice, shortPutStrike);
                            return Mono.just(true);
                        }
                        if (shortCallStrike != null && BigDecimal.valueOf(spotPrice).compareTo(shortCallStrike) > 0) {
                            log.warn("Iron Condor leg breach: spot {} > short call strike {}", spotPrice, shortCallStrike);
                            return Mono.just(true);
                        }
                    } else if ("BULL_PUT_SPREAD".equals(strategyName)) {
                        // Find short put leg
                        BigDecimal shortPutStrike = null;

                        for (TradeLeg leg : legs) {
                            if (leg.getAction() == Action.SELL && leg.getOptionType() == OptionType.PE) {
                                shortPutStrike = leg.getStrikePrice();
                                break;
                            }
                        }

                        // Breach if spot < short put strike
                        if (shortPutStrike != null && BigDecimal.valueOf(spotPrice).compareTo(shortPutStrike) < 0) {
                            log.warn("Bull Put Spread leg breach: spot {} < short put strike {}", spotPrice, shortPutStrike);
                            return Mono.just(true);
                        }
                    } else if ("BEAR_CALL_SPREAD".equals(strategyName)) {
                        // Find short call leg
                        BigDecimal shortCallStrike = null;

                        for (TradeLeg leg : legs) {
                            if (leg.getAction() == Action.SELL && leg.getOptionType() == OptionType.CE) {
                                shortCallStrike = leg.getStrikePrice();
                                break;
                            }
                        }

                        // Breach if spot > short call strike
                        if (shortCallStrike != null && BigDecimal.valueOf(spotPrice).compareTo(shortCallStrike) > 0) {
                            log.warn("Bear Call Spread leg breach: spot {} > short call strike {}", spotPrice, shortCallStrike);
                            return Mono.just(true);
                        }
                    }

                    return Mono.just(false);
                })
                .onErrorResume(e -> {
                    log.warn("Error fetching spot price from Redis key {}: {}, skipping leg breach check", spotPriceKey, e.getMessage());
                    return Mono.just(false);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Spot price not available in Redis key {}, skipping leg breach check", spotPriceKey);
                    return Mono.just(false);
                }));
    }

    /**
     * Nested enum for markToMarket exit trigger decision.
     * Scoped narrowly to this method's decision logic only.
     * Do NOT confuse with the separate String-based trade.exitReason field
     * used throughout the codebase for DTE_EXIT/LEG_BREACH/etc.
     */
    enum MarkToMarketExitTrigger {STOP_LOSS, TARGET_HIT}
}
