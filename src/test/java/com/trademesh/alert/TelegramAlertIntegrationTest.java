package com.trademesh.alert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * Integration tests for Telegram alert sending.
 * Verifies the complete flow from AlertService to TelegramClient.
 */
@SpringBootTest
class TelegramAlertIntegrationTest {

    @Autowired
    private AlertService alertService;

    @Autowired
    private TelegramProperties telegramProperties;

    @MockBean
    private WebClient.Builder webClientBuilder;

    @Test
    void shouldSendPriceTargetAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.PRICE_TARGET_HIT)
                .tradingSymbol("NIFTY50")
                .message("Price reached target level of 18500")
                .build();

        StepVerifier.create(alertService.sendAlert(alert))
                .verifyComplete();
    }

    @Test
    void shouldSendStopLossAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.STOP_LOSS_HIT)
                .tradingSymbol("BANKNIFTY")
                .message("Stop loss triggered at 38000")
                .build();

        StepVerifier.create(alertService.sendAlert(alert))
                .verifyComplete();
    }

    @Test
    void shouldSendSignalAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.SIGNAL_GENERATED)
                .tradingSymbol("FINNIFTY")
                .message("Buy signal generated with 75% confidence")
                .build();

        StepVerifier.create(alertService.sendAlert(alert))
                .verifyComplete();
    }

    @Test
    void shouldHandleRiskLimitAlert() {
        Alert alert = Alert.builder()
                .type(AlertType.RISK_LIMIT_BREACHED)
                .tradingSymbol("NIFTY50")
                .message("Risk limit breached: Portfolio loss 2.5%")
                .build();

        StepVerifier.create(alertService.sendAlert(alert))
                .verifyComplete();
    }
}

