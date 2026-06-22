/**
 * Core Strategy Domain Layer.
 *
 * <p>This package provides the foundational domain model and interfaces for all trading strategies
 * in the trade-mesh platform. It defines the contract that all strategy implementations must follow
 * and provides the data structures for signal generation and execution.
 *
 * <p><strong>Core Components:</strong>
 *
 * <p><strong>Interfaces:</strong>
 * <ul>
 *   <li>{@link com.trademesh.strategy.TradingStrategy} - Core contract for all strategy implementations
 *     <ul>
 *       <li>getName() - Returns strategy identifier</li>
 *       <li>isApplicable(MarketCondition) - Checks if strategy fits market condition</li>
 *       <li>evaluate(MarketIntelligence) - Main evaluation method returning Mono&lt;StrategyResult&gt;</li>
 *       <li>getConfig() - Returns strategy configuration</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><strong>Domain Classes:</strong>
 * <ul>
 *   <li>{@link com.trademesh.strategy.TradeSignal} - Complete trading signal with multi-leg structure
 *     <ul>
 *       <li>signalId (UUID) - Unique identifier</li>
 *       <li>symbol - Trading symbol (NIFTY50, BANKNIFTY, etc.)</li>
 *       <li>strategyName - Name of generating strategy</li>
 *       <li>signalType - ENTER, EXIT, or ADJUST</li>
 *       <li>legs - List of TradeLeg objects</li>
 *       <li>expectedCredit - Expected profit/loss</li>
 *       <li>maxLoss - Maximum loss if strategy fails</li>
 *       <li>confidence - LOW, MEDIUM, or HIGH</li>
 *       <li>reason - Plain English explanation</li>
 *       <li>generatedAt - Timestamp</li>
 *     </ul>
 *   </li>
 *
 *   <li>{@link com.trademesh.strategy.TradeLeg} - Individual leg of a multi-leg strategy
 *     <ul>
 *       <li>instrument - Trading instrument identifier (e.g., NIFTY26JUN22300CE)</li>
 *       <li>action - BUY or SELL</li>
 *       <li>optionType - CE (Call) or PE (Put)</li>
 *       <li>strikePrice - Strike price of the option</li>
 *       <li>expiryDate - Expiry date</li>
 *       <li>quantity - Quantity in lots</li>
 *       <li>limitPrice - Order limit price</li>
 *       <li>legType - ENTRY or HEDGE</li>
 *     </ul>
 *   </li>
 *
 *   <li>{@link com.trademesh.strategy.StrategyResult} - Result of strategy evaluation
 *     <ul>
 *       <li>signal - The generated TradeSignal (may be null)</li>
 *       <li>isExecutable - Whether signal should be executed</li>
 *       <li>skipReason - Reason for skipping (if not executable)</li>
 *       <li>executedAt - Timestamp of evaluation</li>
 *     </ul>
 *   </li>
 *
 *   <li>{@link com.trademesh.strategy.StrategyConfig} - Configuration for a strategy
 *     <ul>
 *       <li>strategyName - Strategy identifier</li>
 *       <li>maxLotsPerTrade - Maximum position size</li>
 *       <li>stopLossPercent - Stop loss threshold</li>
 *       <li>targetProfitPercent - Profit target threshold</li>
 *       <li>allowedDays - Days of week to trade</li>
 *       <li>minVix - Minimum VIX to trade</li>
 *       <li>maxVix - Maximum VIX to trade</li>
 *       <li>isEnabled - Whether strategy is active</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><strong>Enumerations:</strong>
 * <ul>
 *   <li>{@link com.trademesh.strategy.enums.SignalType} - ENTER, EXIT, ADJUST</li>
 *   <li>{@link com.trademesh.strategy.enums.Confidence} - LOW, MEDIUM, HIGH</li>
 *   <li>{@link com.trademesh.strategy.enums.Action} - BUY, SELL</li>
 *   <li>{@link com.trademesh.strategy.enums.OptionType} - CE, PE</li>
 *   <li>{@link com.trademesh.strategy.enums.LegType} - ENTRY, HEDGE</li>
 * </ul>
 *
 * <p><strong>Architecture:</strong>
 *
 * <p>The strategy layer follows a reactive, non-blocking architecture:
 * <ul>
 *   <li>All strategy evaluations return Mono&lt;StrategyResult&gt; for async processing</li>
 *   <li>Strategies are stateless and can be evaluated concurrently</li>
 *   <li>Configuration is externalized and can be updated without code changes</li>
 *   <li>Market intelligence is aggregated from multiple sources (VIX, OI, max pain, etc.)</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
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
 *
 * <p><strong>Integration Points:</strong>
 * <ul>
 *   <li>Input: {@link com.trademesh.intelligence.MarketIntelligence} - Aggregated market data</li>
 *   <li>Output: {@link com.trademesh.strategy.StrategyResult} - Signal or skip decision</li>
 *   <li>Downstream: Execution pipeline (order placement, position tracking, P&L)</li>
 * </ul>
 *
 * @see com.trademesh.strategy.TradingStrategy
 * @see com.trademesh.strategy.TradeSignal
 * @see com.trademesh.strategy.TradeLeg
 * @see com.trademesh.strategy.StrategyResult
 * @see com.trademesh.strategy.StrategyConfig
 * @see com.trademesh.intelligence.MarketIntelligence
 */
package com.trademesh.strategy;
