/**
 * Expiry Day Strangle Strategy Package.
 *
 * <p>Implements a Thursday-only options selling strategy with zero overnight risk.
 * The strategy sells both call and put options at OI walls to capture maximum theta decay
 * on expiry day.
 *
 * <p><strong>Strategy Overview:</strong>
 * <ul>
 *   <li><strong>Name:</strong> EXPIRY_DAY_STRANGLE</li>
 *   <li><strong>Applicable Condition:</strong> MarketCondition.EXPIRY_DAY (Thursday only)</li>
 *   <li><strong>Risk Level:</strong> Low (zero overnight risk)</li>
 *   <li><strong>Position Type:</strong> Short strangle (sell call + sell put)</li>
 *   <li><strong>Profit Source:</strong> Theta decay on expiry day</li>
 * </ul>
 *
 * <p><strong>Strategy Logic (4 Steps):</strong>
 *
 * <p><strong>Step 1: Safety Checks</strong>
 * <ul>
 *   <li>VIX Check: Skip if VIX > 18.0 (too volatile for expiry day)</li>
 *   <li>OI Walls Check: Skip if OI walls not clearly defined</li>
 *   <li>Entry Time Check: Skip if current time > 12:30 PM (too late to enter)</li>
 * </ul>
 *
 * <p><strong>Step 2: Strike Selection</strong>
 * <ul>
 *   <li>Call Strike: Maximum call OI strike (biggest resistance wall)</li>
 *   <li>Put Strike: Maximum put OI strike (biggest support wall)</li>
 *   <li>Distance Validation: Ensure minimum 100 points from spot price</li>
 *   <li>Widening: If too close to spot, widen by 50 points</li>
 * </ul>
 *
 * <p><strong>Step 3: Build Trade Legs</strong>
 * <ul>
 *   <li>Leg 1: SELL call option at selected call strike (1 lot)</li>
 *   <li>Leg 2: SELL put option at selected put strike (1 lot)</li>
 *   <li>Credit Calculation: Sum of call LTP + put LTP</li>
 *   <li>Minimum Credit Check: Skip if total credit < 20 points</li>
 * </ul>
 *
 * <p><strong>Step 4: Return StrategyResult</strong>
 * <ul>
 *   <li>Generate TradeSignal with both legs</li>
 *   <li>Include expected credit and max loss calculations</li>
 *   <li>Set confidence to HIGH (expiry day is high-probability setup)</li>
 *   <li>Provide detailed reason with strike prices and credit</li>
 * </ul>
 *
 * <p><strong>Configuration (from application.yml):</strong>
 * <pre>
 * strategy:
 *   expiry-day:
 *     max-vix: 18.0                    # Maximum VIX to trade
 *     latest-entry-time: "12:30"       # Latest entry time (HH:mm)
 *     min-credit: 20.0                 # Minimum credit required (points)
 *     min-distance-from-spot: 100      # Minimum distance from spot (points)
 *     max-lots: 1                      # Maximum lots per trade
 *     stop-loss-percent: 100           # Exit if premium doubles (100% loss)
 *     target-profit-percent: 50        # Exit if premium halves (50% profit)
 * </pre>
 *
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li><strong>Zero Overnight Risk:</strong> Trades only on expiry day, exits same day</li>
 *   <li><strong>OI-Based:</strong> Uses institutional OI walls for strike selection</li>
 *   <li><strong>Theta Decay:</strong> Captures maximum time decay on expiry day</li>
 *   <li><strong>Reactive:</strong> Uses Spring WebFlux Mono for non-blocking evaluation</li>
 *   <li><strong>Configurable:</strong> All parameters loaded from application.yml</li>
 * </ul>
 *
 * <p><strong>Classes:</strong>
 * <ul>
 *   <li>{@link com.trademesh.strategy.expiryday.ExpiryDayStrategy} - Main strategy implementation</li>
 *   <li>{@link com.trademesh.strategy.expiryday.ExpiryDayConfig} - Configuration properties</li>
 * </ul>
 *
 * @since 1.0
 */
package com.trademesh.strategy.expiryday;
