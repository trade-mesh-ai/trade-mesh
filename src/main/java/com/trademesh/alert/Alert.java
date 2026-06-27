package com.trademesh.alert;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;

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
    private LocalDateTime triggeredAt = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
}
