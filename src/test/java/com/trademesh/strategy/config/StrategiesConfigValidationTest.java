package com.trademesh.strategy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StrategiesConfig DTE validation.
 * Tests that startup validation prevents DTE boundary overlap.
 */
@DisplayName("StrategiesConfig DTE Validation Tests")
class StrategiesConfigValidationTest {

    private StrategiesConfig strategiesConfig;

    @BeforeEach
    void setUp() {
        strategiesConfig = new StrategiesConfig();
    }

    // ─── Test Case 1: Valid configuration - dte-min > dte-exit-threshold ───
    @Test
    @DisplayName("Should pass validation when dte-min > dte-exit-threshold")
    void testValidDteConfiguration() {
        StrategyProperties strategy = createStrategy("BULL_PUT_SPREAD", true, 2, 1);
        strategiesConfig.setItems(List.of(strategy));

        // Should not throw exception
        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "Validation should pass when dte-min > dte-exit-threshold");
    }

    // ─── Test Case 2: Invalid configuration - dte-min == dte-exit-threshold ───
    @Test
    @DisplayName("Should fail validation when dte-min == dte-exit-threshold")
    void testInvalidDteConfigurationEqual() {
        StrategyProperties strategy = createStrategy("BULL_PUT_SPREAD", true, 1, 1);
        strategiesConfig.setItems(List.of(strategy));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> strategiesConfig.validateDteConfiguration(),
                "Validation should fail when dte-min == dte-exit-threshold");

        assertTrue(exception.getMessage().contains("dte-min"),
                "Error message should mention dte-min");
        assertTrue(exception.getMessage().contains("dte-exit-threshold"),
                "Error message should mention dte-exit-threshold");
        assertTrue(exception.getMessage().contains("BULL_PUT_SPREAD"),
                "Error message should mention strategy name");
    }

    // ─── Test Case 3: Invalid configuration - dte-min < dte-exit-threshold ───
    @Test
    @DisplayName("Should fail validation when dte-min < dte-exit-threshold")
    void testInvalidDteConfigurationLess() {
        StrategyProperties strategy = createStrategy("BEAR_CALL_SPREAD", true, 1, 2);
        strategiesConfig.setItems(List.of(strategy));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> strategiesConfig.validateDteConfiguration(),
                "Validation should fail when dte-min < dte-exit-threshold");

        assertTrue(exception.getMessage().contains("BEAR_CALL_SPREAD"),
                "Error message should mention strategy name");
    }

    // ─── Test Case 4: Disabled strategy should be skipped ───
    @Test
    @DisplayName("Should skip validation for disabled strategies")
    void testDisabledStrategySkipped() {
        StrategyProperties strategy = createStrategy("IRON_CONDOR", false, 1, 2);
        strategiesConfig.setItems(List.of(strategy));

        // Should not throw exception even though config is invalid
        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "Validation should skip disabled strategies");
    }

    // ─── Test Case 5: Strategy without DTE config should be skipped ───
    @Test
    @DisplayName("Should skip validation for strategies without DTE configuration")
    void testStrategyWithoutDteConfigSkipped() {
        StrategyProperties strategy = new StrategyProperties();
        strategy.setName("EXPIRY_DAY_STRANGLE");
        strategy.setEnabled(true);
        strategy.setExtra(new HashMap<>()); // Empty extra map
        strategiesConfig.setItems(List.of(strategy));

        // Should not throw exception
        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "Validation should skip strategies without DTE configuration");
    }

    // ─── Test Case 6: Multiple strategies - one invalid ───
    @Test
    @DisplayName("Should fail validation if any strategy has invalid DTE configuration")
    void testMultipleStrategiesOneInvalid() {
        List<StrategyProperties> strategies = new ArrayList<>();
        strategies.add(createStrategy("BULL_PUT_SPREAD", true, 2, 1)); // Valid
        strategies.add(createStrategy("BEAR_CALL_SPREAD", true, 1, 1)); // Invalid
        strategies.add(createStrategy("IRON_CONDOR", true, 3, 2)); // Valid

        strategiesConfig.setItems(strategies);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> strategiesConfig.validateDteConfiguration(),
                "Validation should fail if any strategy is invalid");

        assertTrue(exception.getMessage().contains("BEAR_CALL_SPREAD"),
                "Error message should identify the invalid strategy");
    }

    // ─── Test Case 7: Multiple strategies - all valid ───
    @Test
    @DisplayName("Should pass validation when all strategies have valid DTE configuration")
    void testMultipleStrategiesAllValid() {
        List<StrategyProperties> strategies = new ArrayList<>();
        strategies.add(createStrategy("BULL_PUT_SPREAD", true, 2, 1));
        strategies.add(createStrategy("BEAR_CALL_SPREAD", true, 2, 1));
        strategies.add(createStrategy("IRON_CONDOR", true, 3, 2));

        strategiesConfig.setItems(strategies);

        // Should not throw exception
        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "Validation should pass when all strategies are valid");
    }

    // ─── Test Case 8: IRON_CONDOR specific - dte-min: 3, dte-exit-threshold: 2 ───
    @Test
    @DisplayName("Should pass validation for IRON_CONDOR with dte-min=3, dte-exit-threshold=2")
    void testIronCondorValidConfiguration() {
        StrategyProperties strategy = createStrategy("IRON_CONDOR", true, 3, 2);
        strategiesConfig.setItems(List.of(strategy));

        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "IRON_CONDOR with dte-min=3, dte-exit-threshold=2 should be valid");
    }

    // ─── Test Case 9: BULL_PUT_SPREAD specific - dte-min: 2, dte-exit-threshold: 1 ───
    @Test
    @DisplayName("Should pass validation for BULL_PUT_SPREAD with dte-min=2, dte-exit-threshold=1")
    void testBullPutSpreadValidConfiguration() {
        StrategyProperties strategy = createStrategy("BULL_PUT_SPREAD", true, 2, 1);
        strategiesConfig.setItems(List.of(strategy));

        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "BULL_PUT_SPREAD with dte-min=2, dte-exit-threshold=1 should be valid");
    }

    // ─── Test Case 10: BEAR_CALL_SPREAD specific - dte-min: 2, dte-exit-threshold: 1 ───
    @Test
    @DisplayName("Should pass validation for BEAR_CALL_SPREAD with dte-min=2, dte-exit-threshold=1")
    void testBearCallSpreadValidConfiguration() {
        StrategyProperties strategy = createStrategy("BEAR_CALL_SPREAD", true, 2, 1);
        strategiesConfig.setItems(List.of(strategy));

        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "BEAR_CALL_SPREAD with dte-min=2, dte-exit-threshold=1 should be valid");
    }

    // ─── Test Case 11: Large DTE values ───
    @Test
    @DisplayName("Should validate correctly with large DTE values")
    void testLargeDteValues() {
        StrategyProperties strategy = createStrategy("BULL_PUT_SPREAD", true, 10, 5);
        strategiesConfig.setItems(List.of(strategy));

        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "Validation should work with large DTE values");
    }

    // ─── Test Case 12: Edge case - dte-min: 2, dte-exit-threshold: 0 ───
    @Test
    @DisplayName("Should pass validation when dte-exit-threshold is 0")
    void testDteExitThresholdZero() {
        StrategyProperties strategy = createStrategy("BULL_PUT_SPREAD", true, 2, 0);
        strategiesConfig.setItems(List.of(strategy));

        assertDoesNotThrow(() -> strategiesConfig.validateDteConfiguration(),
                "Validation should pass when dte-exit-threshold is 0");
    }

    // ─── Helper method to create a strategy with DTE configuration ───
    private StrategyProperties createStrategy(String name, boolean enabled, int dteMin, int dteExitThreshold) {
        StrategyProperties strategy = new StrategyProperties();
        strategy.setName(name);
        strategy.setEnabled(enabled);

        Map<String, Object> extra = new HashMap<>();
        extra.put("dte-min", dteMin);
        extra.put("dte-exit-threshold", dteExitThreshold);
        extra.put("dte-exit-earliest-time", "10:00");
        strategy.setExtra(extra);

        return strategy;
    }
}
