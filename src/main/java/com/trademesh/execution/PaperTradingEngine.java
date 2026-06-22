package com.trademesh.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.alert.AlertService;
import com.trademesh.strategy.StrategyResult;
import com.trademesh.strategy.TradeLeg;
import com.trademesh.strategy.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    /**
     * Executes a trade from a strategy signal.
     * <p>
     * Process:
     * 1. Check if signal is executable
     * 2. Create PaperTrade entity from signal
     * 3. Save to PostgreSQL
     * 4. Send Telegram alert
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

        // Create PaperTrade entity
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        PaperTrade trade = PaperTrade.builder()
                .id(UUID.randomUUID())
                .tradeDate(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                .symbol(signal.getSymbol())
                .strategyName(signal.getStrategyName())
                .status(TradeStatus.OPEN)
                .legs(serializeLegs(signal.getLegs()))
                .entryCredit(signal.getExpectedCredit())
                .currentValue(signal.getExpectedCredit())
                .unrealizedPnl(0.0)
                .entryTime(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // Save to PostgreSQL
        return paperTradeRepository.save(trade)
                .flatMap(savedTrade -> {
                    // Send Telegram alert
                    String alert = formatEntryAlert(savedTrade, signal);
                    return alertService.sendAlert(createAlert(savedTrade, alert))
                            .then(Mono.just(savedTrade));
                })
                .doOnSuccess(savedTrade -> log.info("Paper trade executed: {} for {}", savedTrade.getId(), savedTrade.getSymbol()))
                .doOnError(error -> log.error("Error executing paper trade", error));
    }

    /**
     * Marks a paper trade to market (updates current value and P&L).
     * <p>
     * Process:
     * 1. Fetch current option prices from Redis
     * 2. Calculate current value of position
     * 3. Update unrealizedPnl
     * 4. Check stop loss trigger (currentValue > entryCredit × 2)
     * 5. Check target trigger (currentValue < entryCredit × 0.5)
     * 6. Save updated trade
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

        // Fetch current prices from Redis and calculate current value
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
                    return alertService.sendAlert(createAlert(closedTrade, alert))
                            .then(Mono.just(closedTrade));
                })
                .doOnSuccess(closedTrade -> log.info("Paper trade closed: {} with P&L: {}",
                        closedTrade.getId(), closedTrade.getRealizedPnl()))
                .doOnError(error -> log.error("Error closing paper trade: {}", trade.getId(), error));
    }

    /**
     * Fetches current option prices from Redis for all legs of a trade.
     * <p>
     * Calculates the current value of the position based on:
     * - Current prices of all legs
     * - Action (BUY/SELL) for each leg
     * - Quantity of each leg
     *
     * @param trade the paper trade
     * @return Mono emitting the current value in points
     */
    private Mono<Double> fetchCurrentPrices(PaperTrade trade) {
        try {
            List<TradeLeg> legs = objectMapper.readValue(
                    trade.getLegs(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TradeLeg.class)
            );

            // For now, return entry credit as current value (placeholder)
            // In production, fetch actual prices from Redis for each leg
            // and calculate position value based on current prices
            log.debug("Fetching current prices for {} legs", legs.size());

            // TODO: Implement actual price fetching from Redis
            // For each leg, fetch price using PRICE_KEY_PREFIX + instrument
            // Calculate position value based on current prices and actions

            return Mono.just(trade.getCurrentValue() != null ? trade.getCurrentValue() : trade.getEntryCredit());
        } catch (Exception e) {
            log.error("Error fetching current prices for trade: {}", trade.getId(), e);
            return Mono.just(trade.getCurrentValue() != null ? trade.getCurrentValue() : trade.getEntryCredit());
        }
    }

    /**
     * Serializes trade legs to JSON string.
     *
     * @param legs the list of trade legs
     * @return JSON string representation of legs
     */
    private String serializeLegs(List<TradeLeg> legs) {
        try {
            return objectMapper.writeValueAsString(legs);
        } catch (Exception e) {
            log.error("Error serializing trade legs", e);
            return "[]";
        }
    }

    /**
     * Formats entry alert message for Telegram.
     *
     * @param trade  the paper trade
     * @param signal the trade signal
     * @return formatted alert message
     */
    private String formatEntryAlert(PaperTrade trade, TradeSignal signal) {
        try {
            List<TradeLeg> legs = objectMapper.readValue(
                    trade.getLegs(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TradeLeg.class)
            );

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
