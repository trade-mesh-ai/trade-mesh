/**
 * Core Strategy Domain Layer.
 * <p>
 * This package provides the foundational domain model and interfaces for all trading strategies
 * in the trade-mesh platform. It defines the contract that all strategy implementations must follow
 * and provides the data structures for signal generation and execution.
 * <p>
 * Core Components:
 * <p>
 * Interfaces:
 * - {@link com.trademesh.strategy.TradingStrategy} - Core contract for all strategy implementations
 * - getName() - Returns strategy identifier
 * - isApplicable(MarketCondition) - Checks if strategy fits market condition
 * - evaluate(MarketIntelligence) - Main evaluation method returning Mono&lt;StrategyResult&gt;
 * - getConfig() - Returns strategy configuration
 * <p>
 * Domain Classes:
 * - {@link com.trademesh.strategy.TradeSignal} - Complete trading signal with multi-leg structure
 * - signalId (UUID) - Unique identifier
 * - symbol - Trading symbol (NIFTY50, BANKNIFTY, etc.)
 * - strategyName - Name of generating strategy
 * - signalType - ENTER, EXIT, or ADJUST
 * - legs - List of TradeLeg objects
 * - expectedCredit - Expected profit/loss
 * - maxLoss - Maximum loss if strategy fails
 * - confidence - LOW, MEDIUM, or HIGH
 * - reason - Plain English explanation
 * - generatedAt - Timestamp
 * <p>
 * - {@link com.trademesh.strategy.TradeLeg} - Individual leg of a multi-leg strategy
 * - instrument - Trading instrument identifier (e.g., NIFTY26JUN22300CE)
 * - action - BUY or SELL
 * - optionType - CE (Call) or PE (Put)
 * - strikePrice - Strike price of the option
 * - expiryDate - Expiry date
 * - quantity - Quantity in lots
 * - limitPrice - Order limit price
 * - legType - ENTRY or HEDGE
 * <p>
 * - {@link com.trademesh.strategy.StrategyResult} - Result of strategy evaluation
 * - signal - The generated TradeSignal (may be null)
 * - isExecutable - Whether signal should be executed
 * - skipReason - Reason for skipping (if not executable)
 * - executedAt - Timestamp of evaluation
 * <p>
 * - {@link com.trademesh.strategy.StrategyConfig} - Configuration for a strategy
 * - strategyName - Strategy identifier
 * - maxLotsPerTrade - Maximum position size
 * - stopLossPercent - Stop loss threshold
 * - targetProfitPercent - Profit target threshold
 * - allowedDays - Days of week to trade
 * - minVix - Minimum VIX to trade
 * - maxVix - Maximum VIX to trade
 * - isEnabled - Whether strategy is active
 * <p>
 * Enumerations:
 * - {@link com.trademesh.strategy.enums.SignalType} - ENTER, EXIT, ADJUST
 * - {@link com.trademesh.strategy.enums.Confidence} - LOW, MEDIUM, HIGH
 * - {@link com.trademesh.strategy.enums.Action} - BUY, SELL
 * - {@link com.trademesh.strategy.enums.OptionType} - CE, PE
 * - {@link com.trademesh.strategy.enums.LegType} - ENTRY, HEDGE
 * <p>
 * Architecture:
 * <p>
 * The strategy layer follows a reactive, non-blocking architecture:
 * - All strategy evaluations return Mono&lt;StrategyResult&gt; for async processing
 * - Strategies are stateless and can be evaluated concurrently
 * - Configuration is externalized and can be updated without code changes
 * - Market intelligence is aggregated from multiple sources (VIX, OI, max pain, etc.)
 * <p>
 * Usage Example:
 *
 * <pre>
 * // Implement a strategy
 * @Component
 * public class IronCondorStrategy implements TradingStrategy {
 *
 *     private final StrategyConfig config;
 *
 *     @Override
 *     public String getName() {
 *         return "IronCondor";
 *     }
 *
 *     @Override
 *     public boolean isApplicable(MarketCondition condition) {
 *         return condition == MarketCondition.SIDEWAYS;
 *     }
 *
 *     @Override
 *     public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
 *         // Check VIX constraints
 *         if (intelligence.getVixData().getCurrentVix() > config.getMaxVix()) {
 *             return Mono.just(StrategyResult.builder()
 *                 .isExecutable(false)
 *                 .skipReason("VIX exceeds maximum threshold")
 *                 .build());
 *         }
 *
 *         // Generate signal
 *         TradeSignal signal = TradeSignal.builder()
 *             .signalId(UUID.randomUUID())
 *             .symbol(intelligence.getSymbol())
 *             .strategyName(getName())
 *             .signalType(SignalType.ENTER)
 *             .legs(generateLegs(intelligence))
 *             .expectedCredit(calculateCredit(intelligence))
 *             .maxLoss(calculateMaxLoss(intelligence))
 *             .confidence(Confidence.HIGH)
 *             .reason("VIX at 15, NIFTY at support, IV rank 60%")
 *             .build();
 *
 *         return Mono.just(StrategyResult.builder()
 *             .signal(signal)
 *             .isExecutable(true)
 *             .build());
 *     }
 *
 *     @Override
 *     public StrategyConfig getConfig() {
 *         return config;
 *     }
 * }
 * </pre>
 * <p>
 * Integration Points:
 * - Input: {@link com.trademesh.intelligence.MarketIntelligence} - Aggregated market data
 * - Output: {@link com.trademesh.strategy.StrategyResult} - Signal or skip decision
 * - Downstream: Execution pipeline (order placement, position tracking, P&L)
 *
 * @see com.trademesh.strategy.TradingStrategy
 * @see com.trademesh.strategy.TradeSignal
 * @see com.trademesh.strategy.TradeLeg
 * @see com.trademesh.strategy.StrategyResult
 * @see com.trademesh.strategy.StrategyConfig
 * @see com.trademesh.intelligence.MarketIntelligence
 */
package com.trademesh.strategy;
