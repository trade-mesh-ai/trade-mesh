package com.trademesh.zerodha.auth;

import com.trademesh.alert.Alert;
import com.trademesh.alert.AlertType;
import com.trademesh.alert.TelegramNotificationBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors Zerodha access token availability across three scheduled check cadences:
 * <ul>
 *   <li><strong>5-minute continuous check</strong> (checkTokenExpiry): Runs every 5 minutes
 *       (configurable via zerodha.token-check-interval-ms, default 300000ms).
 *       Detects token expiry in real-time during trading hours.</li>
 *   <li><strong>8 AM pre-market health check</strong> (checkTokenHealthPreMarket): Runs daily
 *       at 08:00 AM IST, before market open (09:15 AM IST). Ensures a valid token is present
 *       before trading begins.</li>
 *   <li><strong>9 AM final pre-market warning</strong> (checkTokenHealthFinalWarning): Runs
 *       weekdays at 09:00 AM IST (15 minutes before market open). Escalated check if token
 *       is still missing at this critical moment.</li>
 * </ul>
 *
 * <p>All three check cadences perform the identical underlying check: verify token existence
 * in Redis. If the token is missing, marks it unavailable via TokenAvailabilityTracker
 * and sends a Telegram re-authentication alert.
 *
 * <p>This class consolidates the former TokenRefreshScheduler functionality into a single
 * component with unified alerting via Telegram (replacing log-based alerting that assumed
 * external infrastructure like Grafana Loki or PagerDuty).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenExpiryWatcher {

    /**
     * Redis key for Zerodha access token (must match ZerodhaAuthService.ACCESS_TOKEN_KEY).
     */
    private static final String TOKEN_KEY = "zerodha:access_token";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final TokenAvailabilityTracker tokenAvailabilityTracker;
    private final TelegramNotificationBot telegramNotificationBot;

    /**
     * Periodically checks if the Zerodha access token is still available in Redis.
     * If the token is missing, marks it unavailable and sends a re-authentication alert.
     * Scheduled interval is configurable via zerodha.token-check-interval-ms (default: 300000ms = 5 minutes).
     */
    @Scheduled(fixedRateString = "${zerodha.token-check-interval-ms:300000}")
    public void checkTokenExpiry() {
        performTokenCheck();
    }

    /**
     * Daily pre-market token health check at 08:00 AM IST.
     * Ensures a valid token is present before market open (09:15 AM IST).
     * Cron: {@code "0 0 8 * * *"} (every day at 08:00:00)
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void checkTokenHealthPreMarket() {
        performTokenCheck();
    }

    /**
     * Final pre-market token check at 09:00 AM IST (15 minutes before market open).
     * Runs on weekdays only. If token is still missing at this critical moment,
     * the operator receives an escalated alert.
     * Cron: {@code "0 0 9 * * MON-FRI"} (weekdays at 09:00:00)
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void checkTokenHealthFinalWarning() {
        performTokenCheck();
    }

    /**
     * Core token availability check logic shared by all three scheduled entry points.
     * Checks if the Zerodha access token exists in Redis. If missing, marks it unavailable
     * and sends a Telegram re-authentication alert.
     */
    private void performTokenCheck() {
        // If token is already marked as unavailable, skip check (already known missing)
        if (!tokenAvailabilityTracker.isTokenAvailable()) {
            return;
        }

        redisTemplate.opsForValue().get(TOKEN_KEY)
                .hasElement()
                .doOnSuccess(exists -> {
                    if (!exists) {
                        log.warn("Zerodha access token not found in Redis");
                        tokenAvailabilityTracker.setTokenUnavailable();
                        sendReAuthAlert();
                    }
                })
                .doOnError(error -> log.error("Error checking token expiry from Redis: {}", error.getMessage()))
                .onErrorResume(error -> reactor.core.publisher.Mono.empty())
                .subscribe();
    }

    /**
     * Sends a Telegram alert notifying the operator that the Zerodha token has expired
     * and re-authentication is required.
     */
    private void sendReAuthAlert() {
        String htmlMessage = "⚠️ TradeMesh Alert\n" +
                "Zerodha access token has expired.\n" +
                "Please re-authenticate to resume trading.\n" +
                "Open: <code>http://your-server/zerodha/login</code>";

        Alert alert = Alert.builder()
                .type(AlertType.SYSTEM_ERROR)
                .tradingSymbol("SYSTEM")
                .message(htmlMessage)
                .build();

        telegramNotificationBot.sendAlert(alert)
                .subscribe(
                        unused -> {
                            // Alert sent successfully
                        },
                        error -> {
                            // Log error if alert fails, do not throw
                            log.error("Failed to send token expiry alert via Telegram: {}", error.getMessage());
                        }
                );
    }
}
