package com.trademesh.risk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for risk management limits.
 * <p>
 * Loaded from application.yml under the 'risk' section.
 * Example:
 * risk:
 * max-position-size-per-symbol: 5
 * daily-loss-limit-percent: 2.0
 * max-sector-exposure: 50000
 * min-margin-buffer-percent: 10
 * position-sizing:
 * risk-percent: 1.0
 * max-quantity: 10
 */
@Data
@Component
@ConfigurationProperties(prefix = "risk")
public class RiskLimitsConfig {

    /**
     * Maximum position size per symbol (in lots).
     * Default: 5 lots
     */
    private int maxPositionSizePerSymbol = 5;

    /**
     * Daily loss limit as percentage of account equity.
     * Default: 2.0% (2% of account equity)
     */
    private double dailyLossLimitPercent = 2.0;

    /**
     * Maximum sector exposure in rupees.
     * Default: 50000
     */
    private double maxSectorExposure = 50000;

    /**
     * Minimum margin buffer percentage.
     * Default: 10% (maintain 10% buffer above required margin)
     */
    private double minMarginBufferPercent = 10;

    /**
     * Position sizing configuration.
     */
    private PositionSizingConfig positionSizing = new PositionSizingConfig();

    /**
     * Position sizing configuration.
     */
    @Data
    public static class PositionSizingConfig {

        /**
         * Risk percentage per trade.
         * Default: 1.0% (risk 1% of account equity per trade)
         */
        private double riskPercent = 1.0;

        /**
         * Maximum quantity per trade.
         * Default: 10 lots
         */
        private int maxQuantity = 10;
    }
}
