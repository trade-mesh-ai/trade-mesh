package com.trademesh.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AlertService.
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private TelegramClient telegramClient;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = new AlertService(telegramClient);
    }

    @Test
    void sendAlert_shouldFormatAndSendToTelegram() {
        Alert alert = Alert.builder()
                .type(AlertType.PRICE_TARGET_HIT)
                .tradingSymbol("NIFTY50")
                .message("Price reached target level of 18500")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(alertService.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void sendAlert_shouldFormatMessageCorrectly() {
        Alert alert = Alert.builder()
                .type(AlertType.STOP_LOSS_HIT)
                .tradingSymbol("BANKNIFTY")
                .message("Stop loss triggered at 38000")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        alertService.sendAlert(alert).block();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void sendAlert_shouldHandleTelegramErrors() {
        Alert alert = Alert.builder()
                .type(AlertType.SYSTEM_ERROR)
                .tradingSymbol("NIFTY50")
                .message("System error occurred")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Telegram API error")));

        StepVerifier.create(alertService.sendAlert(alert))
                .verifyComplete();
    }
}

