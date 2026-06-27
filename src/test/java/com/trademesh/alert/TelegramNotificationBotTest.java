package com.trademesh.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class TelegramNotificationBotTest {

    @Mock
    private TelegramClient telegramClient;

    private TelegramNotificationBot telegramNotificationBot;

    @BeforeEach
    void setUp() {
        telegramNotificationBot = new TelegramNotificationBot(telegramClient);
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

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
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

        telegramNotificationBot.sendAlert(alert).block();

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

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();
    }

    @Test
    void shouldIncludeAllAlertDetailsInFormattedMessage() {
        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol("FINNIFTY")
                .message("Strong buy signal with high confidence")
                .build();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        when(telegramClient.sendMessage(messageCaptor.capture()))
                .thenReturn(Mono.empty());

        telegramNotificationBot.sendAlert(alert).block();

        String sentMessage = messageCaptor.getValue();
        assert sentMessage.contains("SIGNAL_GENERATED");
        assert sentMessage.contains("FINNIFTY");
        assert sentMessage.contains("Strong buy signal with high confidence");
    }

    @Test
    void shouldIncludeTimestampInFormattedMessage() {
        Alert alert = Alert.builder()
                .type(AlertType.ORDER_FILLED)
                .tradingSymbol("NIFTY50")
                .message("Order executed at best price")
                .build();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        when(telegramClient.sendMessage(messageCaptor.capture()))
                .thenReturn(Mono.empty());

        telegramNotificationBot.sendAlert(alert).block();

        String sentMessage = messageCaptor.getValue();
        assert sentMessage.contains(alert.getTriggeredAt().toString());
    }

    @Test
    void shouldUseHtmlFormatting() {
        Alert alert = Alert.builder()
                .type(AlertType.PRICE_TARGET_HIT)
                .tradingSymbol("BANKNIFTY")
                .message("Target price reached")
                .build();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        when(telegramClient.sendMessage(messageCaptor.capture()))
                .thenReturn(Mono.empty());

        telegramNotificationBot.sendAlert(alert).block();

        String sentMessage = messageCaptor.getValue();
        assert sentMessage.contains("<b>");
        assert sentMessage.contains("</b>");
        assert sentMessage.contains("<code>");
        assert sentMessage.contains("</code>");
        assert sentMessage.contains("<i>");
        assert sentMessage.contains("</i>");
    }

    @Test
    void shouldSendStopLossAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.STOP_LOSS_HIT)
                .tradingSymbol("SBIN")
                .message("Stop loss triggered")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void shouldSendOrderPlacedAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.ORDER_PLACED)
                .tradingSymbol("INFY")
                .message("Buy order placed for 100 shares")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void shouldSendRiskLimitBreachedAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.RISK_LIMIT_BREACHED)
                .tradingSymbol("NIFTY50")
                .message("Daily risk limit breached at 3%")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void shouldSendSystemErrorAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.SYSTEM_ERROR)
                .tradingSymbol("N/A")
                .message("Database connection failed")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void shouldHandleAlertWithVeryLongMessage() {
        String longMessage = "A".repeat(2000);
        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol("NIFTY50")
                .message(longMessage)
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void shouldHandleAlertWithSpecialCharactersInMessage() {
        Alert alert = Alert.builder()
                .type(AlertType.PRICE_TARGET_HIT)
                .tradingSymbol("TCS")
                .message("Price crossed <3000> with 50% increase & strong momentum")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }

    @Test
    void shouldHandleAlertWithSpecialCharactersInSymbol() {
        Alert alert = Alert.builder()
                .type(AlertType.ORDER_FILLED)
                .tradingSymbol("NIFTY50-APR")
                .message("Order filled")
                .build();

        when(telegramClient.sendMessage(anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(telegramNotificationBot.sendAlert(alert))
                .verifyComplete();

        verify(telegramClient).sendMessage(anyString());
    }
}
