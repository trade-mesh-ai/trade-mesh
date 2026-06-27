package com.trademesh.strategy;

import com.trademesh.intelligence.MarketCondition;
import com.trademesh.intelligence.MarketIntelligenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST Controller for strategy evaluation and status endpoints.
 * <p>
 * Provides endpoints for:
 * - Evaluating strategies for a given symbol
 * - Checking which strategies are applicable for current market conditions
 * - Viewing all available strategies and their status
 */
@Slf4j
@RestController
@RequestMapping("/strategy")
@RequiredArgsConstructor
@Tag(name = "Strategy", description = "Strategy evaluation and status endpoints")
public class StrategyController {

    private final StrategyOrchestrator strategyOrchestrator;
    private final MarketIntelligenceService marketIntelligenceService;
    private final List<TradingStrategy> strategies;

    /**
     * Evaluates strategies for a given symbol and returns the result.
     * <p>
     * Orchestrates the complete pipeline:
     * 1. Fetch market intelligence
     * 2. Select appropriate strategy
     * 3. Evaluate strategy
     * 4. Return result
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return Mono emitting ResponseEntity with StrategyResult
     */
    @Operation(summary = "Evaluate strategies for a symbol")
    @GetMapping(value = "/evaluate/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<StrategyResult>> evaluateStrategy(
            @Parameter(description = "Trading symbol (e.g., NIFTY50, NIFTYBANK)")
            @PathVariable String symbol) {
        log.info("Received request to evaluate strategy for symbol: {}", symbol);

        return strategyOrchestrator.orchestrate(symbol)
                .map(result -> {
                    log.info("Strategy evaluation completed for {}: executable={}", symbol, result.getIsExecutable());
                    return ResponseEntity.ok(result);
                })
                .onErrorResume(e -> {
                    log.error("Error evaluating strategy for symbol: {}", symbol, e);
                    StrategyResult errorResult = StrategyResult.builder()
                            .isExecutable(false)
                            .skipReason("Error evaluating strategy: " + e.getMessage())
                            .build();
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResult));
                });
    }

    /**
     * Returns which strategies are applicable for the current market condition of a symbol.
     * <p>
     * Response includes:
     * - Market condition
     * - List of applicable strategy names
     * - Configuration details for each applicable strategy
     *
     * @param symbol trading symbol (e.g., "NIFTY50", "NIFTYBANK")
     * @return Mono emitting ResponseEntity with applicable strategies information
     */
    @Operation(summary = "Get applicable strategies for a symbol")
    @GetMapping(value = "/applicable/{symbol}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getApplicableStrategies(
            @Parameter(description = "Trading symbol (e.g., NIFTY50, NIFTYBANK)")
            @PathVariable String symbol) {
        log.info("Received request for applicable strategies for symbol: {}", symbol);

        return marketIntelligenceService.generateIntelligence(symbol)
                .map(intel -> {
                    MarketCondition condition = intel.getMarketCondition();

                    // Filter applicable strategies
                    List<Map<String, Object>> applicableList = strategies.stream()
                            .filter(strategy -> strategy.isApplicable(condition))
                            .map(strategy -> {
                                Map<String, Object> strategyInfo = new HashMap<>();
                                strategyInfo.put("name", strategy.getName());
                                StrategyConfig config = strategy.getConfig();
                                strategyInfo.put("enabled", config.getIsEnabled());
                                strategyInfo.put("maxLotsPerTrade", config.getMaxLotsPerTrade());
                                strategyInfo.put("stopLossPercent", config.getStopLossPercent());
                                strategyInfo.put("targetProfitPercent", config.getTargetProfitPercent());
                                strategyInfo.put("maxVix", config.getMaxVix());
                                return strategyInfo;
                            })
                            .collect(Collectors.toList());

                    // Build response
                    Map<String, Object> response = new HashMap<>();
                    response.put("symbol", symbol);
                    response.put("marketCondition", condition);
                    response.put("applicableCount", applicableList.size());
                    response.put("applicableStrategies", applicableList);
                    response.put("marketSafe", intel.getIsSafeToTrade());

                    log.info("Found {} applicable strategies for {} in condition: {}",
                            applicableList.size(), symbol, condition);

                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.error("Error fetching applicable strategies for symbol: {}", symbol, e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error fetching applicable strategies: " + e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }

    /**
     * Returns all available strategies and their status.
     * <p>
     * Response includes:
     * - Total number of strategies
     * - List of all strategies with:
     * - Name
     * - Enabled status
     * - Configuration details
     * - Applicable market conditions
     *
     * @return Mono emitting ResponseEntity with all strategies information
     */
    @Operation(summary = "Get status of all available strategies")
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> getStrategyStatus() {
        log.info("Received request for strategy status");

        return Mono.fromCallable(() -> {
                    List<Map<String, Object>> strategyList = strategies.stream()
                            .map(strategy -> {
                                Map<String, Object> strategyInfo = new HashMap<>();
                                strategyInfo.put("name", strategy.getName());

                                StrategyConfig config = strategy.getConfig();
                                strategyInfo.put("enabled", config.getIsEnabled());
                                strategyInfo.put("maxLotsPerTrade", config.getMaxLotsPerTrade());
                                strategyInfo.put("stopLossPercent", config.getStopLossPercent());
                                strategyInfo.put("targetProfitPercent", config.getTargetProfitPercent());
                                strategyInfo.put("minVix", config.getMinVix());
                                strategyInfo.put("maxVix", config.getMaxVix());
                                strategyInfo.put("allowedDays", config.getAllowedDays());

                                // Use applicableConditions from config if available
                                List<String> applicableConditions;
                                if (config.getApplicableConditions() != null && !config.getApplicableConditions().isEmpty()) {
                                    applicableConditions = config.getApplicableConditions().stream()
                                            .map(MarketCondition::name)
                                            .collect(Collectors.toList());
                                } else {
                                    // Fallback: determine applicable conditions from strategy
                                    applicableConditions = List.of(
                                                    MarketCondition.SIDEWAYS,
                                                    MarketCondition.TRENDING_BULLISH,
                                                    MarketCondition.TRENDING_BEARISH,
                                                    MarketCondition.EXPIRY_DAY,
                                                    MarketCondition.HIGH_VOLATILITY,
                                                    MarketCondition.NO_TRADE
                                            ).stream()
                                            .filter(condition -> strategy.isApplicable(condition))
                                            .map(MarketCondition::name)
                                            .collect(Collectors.toList());
                                }

                                strategyInfo.put("applicableConditions", applicableConditions);

                                return strategyInfo;
                            })
                            .collect(Collectors.toList());

                    // Build response
                    Map<String, Object> response = new HashMap<>();
                    response.put("totalStrategies", strategyList.size());
                    response.put("strategies", strategyList);
                    response.put("marketOpen", strategyOrchestrator.isMarketOpen());

                    log.info("Returning status for {} strategies", strategyList.size());

                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.error("Error fetching strategy status", e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Error fetching strategy status: " + e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
                });
    }
}
