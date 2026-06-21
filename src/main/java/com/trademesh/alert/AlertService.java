package com.trademesh.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Placeholder alert service.
 * TODO: Implement price-level alerts, signal notifications, and push/email/webhook dispatch.
 */
@Slf4j
@Service
public class AlertService {

    /**
     * Sends an alert notification.
     *
     * @param alert the alert to dispatch
     * @return completion signal
     */
    public Mono<Void> sendAlert(Alert alert) {
        log.info("ALERT [{}] {}: {}", alert.getType(), alert.getTradingSymbol(), alert.getMessage());
        // TODO: dispatch via email / Telegram / webhook
        return Mono.empty();
    }
}
