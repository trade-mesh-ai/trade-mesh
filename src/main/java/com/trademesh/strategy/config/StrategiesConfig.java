package com.trademesh.strategy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified configuration for all trading strategies.
 * Binds to the top-level strategies: list from application.yml.
 * Provides centralized access to strategy configurations.
 */
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
