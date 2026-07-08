package com.trademesh.intelligence;

import com.trademesh.alert.Alert;
import com.trademesh.alert.AlertType;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.intelligence.vix.VixAlertService;
import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalTime;

import static com.trademesh.config.ApplicationConstants.*;

/**
 * Scheduled service for auto-refreshing market intelligence every 5 minutes.
 * <p>
 * Responsibilities:
 * - Refreshes market intelligence for NIFTY50 and NIFTYBANK every 5 minutes
 * - Only runs during market hours (9:15 AM to 3:30 PM IST)
 * - Logs summary after each refresh
 * - Detects market condition changes and sends Telegram alerts
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntelligenceScheduler {

    private static final String SCHEDULER_NAME = "IntelligenceScheduler";

    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final MarketIntelligenceService marketIntelligenceService;
    private final VixAlertService vixAlertService;
    private final TokenAvailabilityTracker tokenTracker;
    private final TelegramNotificationBot telegramNotificationBot;

    // ─── State tracking for condition changes ──────────────────────────────────────
    private MarketCondition previousConditionNifty50 = null;
    private MarketCondition previousConditionBankNifty = null;

    /**
     * Refreshes market intelligence every 5 minutes (300,000 milliseconds).
     * Only runs during market hours (9:15 AM to 3:30 PM IST).
     * Performs a single token availability check before fan-out to prevent redundant
     * Redis lookups and log spam when unauthenticated.
     */
    @Scheduled(cron = "${intelligence.market-data.fetch-interval-minutes}", zone = "Asia/Kolkata")
    public void refreshMarketIntelligence() {
        // Check if within market hours
        LocalTime now = LocalTime.now(IST);
        if (!isMarketHours(now)) {
            log.debug("Outside market hours ({}). Skipping intelligence refresh.", now);
            return;
        }

        if (!tokenTracker.isTokenAvailable()) {
            log.warn("[IntelligenceScheduler] Zerodha token unavailable. Skipping market intelligence refresh.");
            return;
        }

        log.info("Starting market intelligence refresh ...");

        for (String symbol : SYMBOLS) {
            refreshIntelligence(symbol)
                    .doOnError(e -> log.error("Failed to refresh intelligence for {}: {}", symbol, e.getMessage()))
                    .onErrorResume(e -> {
                        // Suppress error propagation to prevent ErrorCallbackNotImplemented
                        log.debug("Intelligence refresh failed for {}, continuing with next symbol", symbol);
                        return Mono.empty();
                    })
                    .subscribe();
        }
    }

    /**
     * Refreshes intelligence for a single symbol and detects condition changes.
     */
    private Mono<Void> refreshIntelligence(String symbol) {
        return marketIntelligenceService.generateIntelligence(symbol)
                .flatMap(intelligence -> {
                    // Log summary
                    log.info("Intelligence refreshed for {}: {}", symbol, intelligence.summary());

                    // Detect condition change and send alert
                    MarketCondition currentCondition = intelligence.getMarketCondition();
                    MarketCondition previousCondition = getPreviousCondition(symbol);

                    if (previousCondition != null && !previousCondition.equals(currentCondition)) {
                        log.warn("Market condition changed for {}: {} → {}",
                                symbol, previousCondition, currentCondition);

                        // Send Telegram alert
                        String alertMessage = String.format(
                                "📊 Market Condition Change: %s\n%s → %s\n%s",
                                symbol,
                                previousCondition.name(),
                                currentCondition.name(),
                                intelligence.summary()
                        );

                        return sendConditionChangeAlert(symbol, alertMessage)
                                .doOnSuccess(v -> log.info("Condition change alert sent for {}", symbol))
                                .then(Mono.fromRunnable(() -> setPreviousCondition(symbol, currentCondition)));
                    } else {
                        // Update previous condition even if no change
                        setPreviousCondition(symbol, currentCondition);
                        return Mono.empty();
                    }
                })
                .doOnError(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        tokenTracker.trackTokenMissing(SCHEDULER_NAME);
                    } else {
                        log.error("Failed to refresh intelligence for {}: {}", symbol, e.getMessage());
                    }
                })
                .onErrorResume(e -> {
                    if (e instanceof IllegalStateException && e.getMessage().contains("No Zerodha access token")) {
                        log.debug("Intelligence refresh failed due to missing token, continuing with next symbol");
                        return Mono.empty();
                    }
                    return Mono.error(e);
                })
                .then();
    }

    /**
     * Sends a Telegram alert for market condition changes.
     *
     * @param symbol the trading symbol
     * @param message the alert message
     * @return Mono<Void> completion signal
     */
    Mono<Void> sendConditionChangeAlert(String symbol, String message) {
        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol(symbol)
                .message(message)
                .build();

        return telegramNotificationBot.sendAlert(alert);
    }

    /**
     * Checks if the current time is within market hours.
     *
     * @param now current time
     * @return true if within market hours (9:15 AM to 3:30 PM IST)
     */
    private boolean isMarketHours(LocalTime now) {
        return !now.isBefore(MARKET_OPEN) && now.isBefore(MARKET_CLOSE);
    }

    /**
     * Gets the previous market condition for a symbol.
     */
    private MarketCondition getPreviousCondition(String symbol) {
        if ("NIFTY50".equals(symbol)) {
            return previousConditionNifty50;
        } else if ("NIFTYBANK".equals(symbol)) {
            return previousConditionBankNifty;
        }
        return null;
    }

    /**
     * Sets the previous market condition for a symbol.
     */
    private void setPreviousCondition(String symbol, MarketCondition condition) {
        if ("NIFTY50".equals(symbol)) {
            previousConditionNifty50 = condition;
        } else if ("NIFTYBANK".equals(symbol)) {
            previousConditionBankNifty = condition;
        }
    }
}
