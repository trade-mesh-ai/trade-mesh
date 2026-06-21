package com.trademeshai.alert;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Represents an alert event to be dispatched.
 */
@Data
@Builder
public class Alert {

    private AlertType type;
    private String tradingSymbol;
    private String message;
    @Builder.Default
    private Instant triggeredAt = Instant.now();
}
