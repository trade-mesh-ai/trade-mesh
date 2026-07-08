package com.trademesh.strategy.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified configuration for all trading strategies.
 * Binds to the top-level strategies: list from application.yml.
 * Provides centralized access to strategy configurations.
 * <p>
 * Validates DTE configuration at startup to prevent misconfiguration:
 * - dte-min must be strictly greater than dte-exit-threshold
 * - Fails fast with IllegalStateException if validation fails
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "trading-strategies")
public class StrategiesConfig {

    /**
     * List of all strategy configurations from application.yml.
     * Each entry represents one strategy's configuration.
     */
    private List<StrategyProperties> items = new ArrayList<>();

    /**
     * Validates DTE configuration for all strategies at startup.
     * Ensures dte-min > dte-exit-threshold to prevent boundary overlap.
     *
     * @throws IllegalStateException if any strategy has invalid DTE configuration
     */
    @PostConstruct
    public void validateDteConfiguration() {
        log.info("Validating DTE configuration for all strategies...");

        for (StrategyProperties strategy : items) {
            if (!strategy.isEnabled()) {
                log.debug("Skipping validation for disabled strategy: {}", strategy.getName());
                continue;
            }

            // Only validate strategies that have DTE configuration
            if (strategy.getExtra() == null || strategy.getExtra().isEmpty()) {
                continue;
            }

            Integer dteMin = strategy.getExtraInt("dte-min", -1);
            Integer dteExitThreshold = strategy.getExtraInt("dte-exit-threshold", -1);

            // Skip if either value is not configured
            if (dteMin < 0 || dteExitThreshold < 0) {
                continue;
            }

            // Validate: dte-min must be strictly greater than dte-exit-threshold
            if (dteMin <= dteExitThreshold) {
                String errorMsg = String.format(
                        "INVALID DTE CONFIGURATION for strategy '%s': " +
                                "dte-min (%d) must be strictly greater than dte-exit-threshold (%d). " +
                                "This boundary overlap would cause trades to exit before they can be entered.",
                        strategy.getName(), dteMin, dteExitThreshold);
                log.error(errorMsg);
                throw new IllegalStateException(errorMsg);
            }

            log.debug("✓ DTE configuration valid for {}: dte-min={}, dte-exit-threshold={}",
                    strategy.getName(), dteMin, dteExitThreshold);
        }

        log.info("✓ All strategy DTE configurations validated successfully");
    }

    /**
     * Retrieves a strategy configuration by name.
     * Searches through the strategies list for a matching name.
     *
     * @param name the strategy name to look up
     * @return the StrategyProperties for the given name
     * @throws IllegalStateException if strategy configuration not found
     */
    public StrategyProperties getStrategy(String name) {
        return items.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Strategy config not found for: " + name));
    }
}
