package com.trademesh.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Alert service for dispatching alerts via multiple channels (Telegram, email, webhook).
 * Sends alerts to configured channels asynchronously and logs all alerts.
 */
@Slf4j
@Service
public class AlertService {

    private final TelegramClient telegramClient;

    public AlertService(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    /**
     * Sends an alert notification via configured channels (Telegram, etc.).
     *
     * @param alert the alert to dispatch
     * @return completion signal
     */
    public Mono<Void> sendAlert(Alert alert) {
        log.info("ALERT [{}] {}: {}", alert.getType(), alert.getTradingSymbol(), alert.getMessage());

        String telegramMessage = formatTelegramMessage(alert);
        return telegramClient.sendMessage(telegramMessage)
                .onErrorResume(error -> {
                    log.error("Error sending alert via Telegram", error);
                    return Mono.empty();
                });
    }

    /**
     * Formats an alert into a Telegram-friendly HTML message.
     *
     * @param alert the alert to format
     * @return formatted message string
     */
    private String formatTelegramMessage(Alert alert) {
        return String.format(
                "<b>%s</b>\n" +
                        "<code>%s</code>\n\n" +
                        "%s\n\n" +
                        "<i>%s</i>",
                alert.getType(),
                alert.getTradingSymbol(),
                alert.getMessage(),
                alert.getTriggeredAt()
        );
    }
}
