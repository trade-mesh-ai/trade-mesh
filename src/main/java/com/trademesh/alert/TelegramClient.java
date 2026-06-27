package com.trademesh.alert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Telegram Bot client for sending messages via the Telegram Bot API.
 * Uses reactive WebClient for non-blocking HTTP calls.
 */
@Slf4j
@Service
public class TelegramClient {

    private static final String TELEGRAM_API_URL = "https://api.telegram.org";
    private final WebClient.Builder webClientBuilder;
    private final TelegramProperties properties;

    public TelegramClient(WebClient.Builder webClientBuilder, TelegramProperties properties) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
    }

    /**
     * Sends a message via Telegram Bot API.
     *
     * @param text the message text to send
     * @return a Mono that completes when the message is sent
     */
    public Mono<Void> sendMessage(String text) {
        if (!isConfigured()) {
            log.warn("Telegram is not configured (missing bot-token or chat-id). Message not sent.");
            return Mono.empty();
        }

        TelegramSendMessageRequest request = TelegramSendMessageRequest.builder()
                .chatId(properties.getChatId())
                .text(text)
                .parseMode("HTML")
                .build();

        String url = String.format("%s/bot%s/sendMessage",
                TELEGRAM_API_URL, properties.getBotToken());

        WebClient webClient = webClientBuilder.build();

        return webClient
                .post()
                .uri(url)
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(response -> log.info("Telegram message sent successfully"))
                .doOnError(error -> log.error("Failed to send Telegram message", error))
                .onErrorResume(error -> {
                    log.error("Telegram API error: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private boolean isConfigured() {
        return properties.getBotToken() != null && !properties.getBotToken().isBlank()
                && properties.getChatId() != null && !properties.getChatId().isBlank();
    }

    /**
     * Request DTO for Telegram sendMessage API.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TelegramSendMessageRequest {
        @JsonProperty("chat_id")
        private String chatId;
        private String text;
        @JsonProperty("parse_mode")
        private String parseMode;
        @JsonProperty("disable_web_page_preview")
        private Boolean disableWebPagePreview;
    }
}


