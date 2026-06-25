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
 * Monitors Zerodha access token expiry by periodically checking Redis.
 * If the token is missing, marks it unavailable and sends a re-authentication alert.
 * Runs on a configurable schedule (default: every 5 minutes).
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
