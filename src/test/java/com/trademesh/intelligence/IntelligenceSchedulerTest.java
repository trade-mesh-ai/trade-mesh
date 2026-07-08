package com.trademesh.intelligence;

import com.trademesh.alert.Alert;
import com.trademesh.alert.AlertType;
import com.trademesh.alert.TelegramNotificationBot;
import com.trademesh.intelligence.vix.VixAlertService;
import com.trademesh.zerodha.auth.TokenAvailabilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IntelligenceScheduler condition-change alert functionality.
 * Verifies that sendConditionChangeAlert() correctly builds and sends Alert objects
 * via TelegramNotificationBot.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IntelligenceScheduler Condition-Change Alert Tests")
class IntelligenceSchedulerTest {

    @Mock
    private MarketIntelligenceService marketIntelligenceService;

    @Mock
    private VixAlertService vixAlertService;

    @Mock
    private TokenAvailabilityTracker tokenTracker;

    @Mock
    private TelegramNotificationBot telegramNotificationBot;

    @InjectMocks
    private IntelligenceScheduler intelligenceScheduler;

    @BeforeEach
    void setUp() {
        // Default mock behavior: sendAlert returns Mono.empty() (successful completion)
        lenient().when(telegramNotificationBot.sendAlert(any(Alert.class)))
                .thenReturn(Mono.empty());
    }

    /**
     * Test: sendConditionChangeAlert() invokes telegramNotificationBot.sendAlert()
     * with a properly constructed Alert object.
     *
     * Verifies:
     * 1. telegramNotificationBot.sendAlert() is called exactly once
     * 2. The Alert has type SIGNAL_GENERATED
     * 3. The Alert has the correct tradingSymbol
     * 4. The Alert has the correct message content
     */
    @Test
    @DisplayName("Should send Alert with correct type, symbol, and message")
    void testSendConditionChangeAlertInvokesBotWithCorrectAlert() {
        // Arrange
        String symbol = "NIFTY50";
        String message = "📊 Market Condition Change: NIFTY50\nTREND_UP → TREND_DOWN\nSummary data";

        // Act & Assert
        StepVerifier.create(
                intelligenceScheduler.sendConditionChangeAlert(symbol, message)
        )
                .verifyComplete();

        // Verify telegramNotificationBot.sendAlert() was called
        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(telegramNotificationBot).sendAlert(alertCaptor.capture());

        Alert capturedAlert = alertCaptor.getValue();
        assertNotNull(capturedAlert, "Alert should not be null");
        assertEquals(AlertType.SIGNAL_GENERATED, capturedAlert.getType(),
                "Alert type should be SIGNAL_GENERATED");
        assertEquals(symbol, capturedAlert.getTradingSymbol(),
                "Alert tradingSymbol should match the provided symbol");
        assertEquals(message, capturedAlert.getMessage(),
                "Alert message should match the provided message");
        assertNotNull(capturedAlert.getTriggeredAt(),
                "Alert triggeredAt should be set (defaults to IST now)");
    }

    /**
     * Test: sendConditionChangeAlert() with NIFTYBANK symbol.
     *
     * Verifies the method works correctly with different symbols.
     */
    @Test
    @DisplayName("Should send Alert with NIFTYBANK symbol")
    void testSendConditionChangeAlertWithNiftyBankSymbol() {
        // Arrange
        String symbol = "NIFTYBANK";
        String message = "📊 Market Condition Change: NIFTYBANK\nTREND_DOWN → TREND_UP\nSummary";

        // Act & Assert
        StepVerifier.create(
                intelligenceScheduler.sendConditionChangeAlert(symbol, message)
        )
                .verifyComplete();

        // Verify the correct symbol was passed
        ArgumentCaptor<Alert> alertCaptor = ArgumentCaptor.forClass(Alert.class);
        verify(telegramNotificationBot).sendAlert(alertCaptor.capture());

        Alert capturedAlert = alertCaptor.getValue();
        assertEquals(symbol, capturedAlert.getTradingSymbol(),
                "Alert tradingSymbol should be NIFTYBANK");
        assertEquals(AlertType.SIGNAL_GENERATED, capturedAlert.getType(),
                "Alert type should be SIGNAL_GENERATED");
    }

    /**
     * Test: sendConditionChangeAlert() returns Mono<Void> that completes successfully.
     *
     * Verifies the reactive chain completes without error.
     */
    @Test
    @DisplayName("Should return Mono<Void> that completes successfully")
    void testSendConditionChangeAlertReturnsCompletingMono() {
        // Arrange
        String symbol = "NIFTY50";
        String message = "Test message";

        // Act & Assert
        StepVerifier.create(
                intelligenceScheduler.sendConditionChangeAlert(symbol, message)
        )
                .expectComplete()
                .verify();
    }

    /**
     * Test: sendConditionChangeAlert() propagates errors from telegramNotificationBot.
     *
     * Verifies error handling in the reactive chain.
     */
    @Test
    @DisplayName("Should propagate errors from telegramNotificationBot")
    void testSendConditionChangeAlertPropagatesErrors() {
        // Arrange
        String symbol = "NIFTY50";
        String message = "Test message";
        RuntimeException testException = new RuntimeException("Telegram send failed");

        when(telegramNotificationBot.sendAlert(any(Alert.class)))
                .thenReturn(Mono.error(testException));

        // Act & Assert
        StepVerifier.create(
                intelligenceScheduler.sendConditionChangeAlert(symbol, message)
        )
                .expectError(RuntimeException.class)
                .verify();
    }
}
