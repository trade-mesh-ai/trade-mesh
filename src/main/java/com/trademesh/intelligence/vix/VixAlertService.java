package com.trademesh.intelligence.vix;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Service for handling VIX alerts and Telegram notifications.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Checks if VIX has crossed the alert threshold</li>
 *   <li>Logs alerts to the database (vix_alerts table)</li>
 *   <li>Sends Telegram notifications for VIX threshold breaches</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VixAlertService {

    // ─── Dependencies ─────────────────────────────────────────────────────────────
    private final VixAlertRepository vixAlertRepository;
    private final WebClient.Builder webClientBuilder;
    // ─── Configuration ────────────────────────────────────────────────────────────
    @Value("${vix.alert-threshold:20.0}")
    private Double alertThreshold;
    @Value("${telegram.bot-token:}")
    private String telegramBotToken;
    @Value("${telegram.chat-id:}")
    private String telegramChatId;

    // ─── Public API ────────────────────────────────────────────────────────────────

    /**
     * Processes a VIX data point and triggers alerts if threshold is crossed.
     *
     * <p>This method:
     * <ol>
     *   <li>Checks if VIX has crossed the alert threshold</li>
     *   <li>Creates and persists a VixAlert record to the database</li>
     *   <li>Sends a Telegram notification if configured</li>
     * </ol>
     *
     * @param vixData the current VIX data
     * @return Mono<VixAlert> the created alert, or empty if no alert triggered
     */
    public Mono<VixAlert> processVixAlert(VixData vixData) {
        if (vixData.getCurrentVix() < alertThreshold) {
            log.debug("VIX {} is below threshold {}, no alert triggered",
                    vixData.getCurrentVix(), alertThreshold);
            return Mono.empty();
        }

        String alertMessage = String.format(
                "⚠️ VIX Alert: %.2f — Consider reducing positions",
                vixData.getCurrentVix()
        );

        VixAlert alert = VixAlert.fromVixData(vixData, alertMessage);

        // Save alert to database
        return vixAlertRepository.save(alert)
                .flatMap(savedAlert -> {
                    log.warn("VIX Alert triggered: {} (Level: {})",
                            alertMessage, vixData.getVixLevel());

                    // Send Telegram notification if configured
                    if (isTelegramConfigured()) {
                        return sendTelegramAlert(alertMessage)
                                .flatMap(success -> {
                                    if (success) {
                                        savedAlert.setTelegramSent(true);
                                        return vixAlertRepository.save(savedAlert);
                                    }
                                    return Mono.just(savedAlert);
                                })
                                .onErrorResume(e -> {
                                    log.error("Failed to send Telegram alert: {}", e.getMessage());
                                    return Mono.just(savedAlert);
                                });
                    }

                    return Mono.just(savedAlert);
                })
                .doOnError(e -> log.error("Failed to process VIX alert: {}", e.getMessage(), e));
    }

    /**
     * Sends a Telegram message with the VIX alert.
     *
     * @param message the alert message to send
     * @return Mono<Boolean> true if message was sent successfully, false otherwise
     */
    private Mono<Boolean> sendTelegramAlert(String message) {
        if (!isTelegramConfigured()) {
            log.warn("Telegram is not configured. Skipping notification.");
            return Mono.just(false);
        }

        String url = String.format(
                "https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s",
                telegramBotToken,
                telegramChatId,
                URLEncoder.encode(message, StandardCharsets.UTF_8)
        );

        WebClient client = webClientBuilder.build();

        return client.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    log.info("Telegram alert sent successfully: {}", message);
                    return true;
                })
                .onErrorResume(e -> {
                    log.error("Failed to send Telegram alert: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Checks if Telegram is properly configured.
     *
     * @return true if both bot token and chat ID are configured, false otherwise
     */
    private boolean isTelegramConfigured() {
        return telegramBotToken != null && !telegramBotToken.isBlank()
                && telegramChatId != null && !telegramChatId.isBlank();
    }
}
