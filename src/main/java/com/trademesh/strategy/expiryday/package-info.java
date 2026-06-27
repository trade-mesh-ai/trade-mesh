/**
 * Expiry Day Strangle Strategy Package.
 *
 * Implements a Thursday-only options selling strategy with zero overnight risk.
 * The strategy sells both call and put options at OI walls to capture maximum theta decay
 * on expiry day.
 *
 * Strategy Overview:
 * - Name: EXPIRY_DAY_STRANGLE
 * - Applicable Condition: MarketCondition.EXPIRY_DAY (Thursday only)
 * - Risk Level: Low (zero overnight risk)
 * - Position Type: Short strangle (sell call + sell put)
 * - Profit Source: Theta decay on expiry day
 *
 * Strategy Logic (4 Steps):
 *
 * Step 1: Safety Checks
 * - VIX Check: Skip if VIX > 18.0 (too volatile for expiry day)
 * - OI Walls Check: Skip if OI walls not clearly defined
 * - Entry Time Check: Skip if current time > 12:30 PM (too late to enter)
 *
 * Step 2: Strike Selection
 * - Call Strike: Maximum call OI strike (biggest resistance wall)
 * - Put Strike: Maximum put OI strike (biggest support wall)
 * - Distance Validation: Ensure minimum 100 points from spot price
 * - Widening: If too close to spot, widen by 50 points
 *
 * Step 3: Build Trade Legs
 * - Leg 1: SELL call option at selected call strike (1 lot)
 * - Leg 2: SELL put option at selected put strike (1 lot)
 * - Credit Calculation: Sum of call LTP + put LTP
 * - Minimum Credit Check: Skip if total credit < 20 points
 *
 * Step 4: Return StrategyResult
 * - Generate TradeSignal with both legs
 * - Include expected credit and max loss calculations
 * - Set confidence to HIGH (expiry day is high-probability setup)
 * - Provide detailed reason with strike prices and credit
 *
 * Configuration (from application.yml):
 *
 * The strategy configuration is loaded from the unified strategies list in application.yml:
 *
 * strategies:
 *   - name: EXPIRY_DAY_STRANGLE
 *     enabled: true
 *     allowed-days: [THURSDAY]
 *     applicable-conditions: [EXPIRY_DAY]
 *     max-vix: 18
 *     min-vix: 10
 *     latest-entry-time: "12:30"
 *     min-credit: 20.0
 *     min-distance-from-spot: 100
 *     max-lots: 1
 *     stop-loss-percent: 100
 *     target-profit-percent: 50
 *
 * Configuration is accessed via StrategiesConfig which binds to the strategies list
 * and provides getStrategy(name) method for lookup.
 *
 * Key Features:
 * - Zero Overnight Risk: Trades only on expiry day, exits same day
 * - OI-Based: Uses institutional OI walls for strike selection
 * - Theta Decay: Captures maximum time decay on expiry day
 * - Reactive: Uses Spring WebFlux Mono for non-blocking evaluation
 * - Configurable: All parameters loaded from application.yml via StrategiesConfig
 *
 * Classes:
 * - ExpiryDayStrategy: Main strategy implementation
 * - StrategyProperties: POJO representing one strategy's configuration
 * - StrategiesConfig: Unified configuration container binding to strategies list
 *
 * @since 1.0
 */
package com.trademesh.strategy.expiryday;
