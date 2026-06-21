package com.trademesh.intelligence;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Represents a trading signal produced by the intelligence engine.
 */
@Data
@Builder
public class TradingSignal {

    private String tradingSymbol;
    private SignalDirection direction;
    private double confidence;          // 0.0 – 1.0
    private String strategyName;
    private String reason;
    @Builder.Default
    private Instant generatedAt = Instant.now();
}
