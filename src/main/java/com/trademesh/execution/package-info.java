/**
 * Paper Trading Engine for simulating trades without real money.
 *
 * <p>This package provides a complete paper trading system that:
 * <ul>
 *   <li>Captures strategy signals and executes simulated trades</li>
 *   <li>Tracks trade lifecycle (OPEN → CLOSED/CANCELLED)</li>
 *   <li>Calculates realized and unrealized P&L</li>
 *   <li>Implements automatic stop loss and target triggers</li>
 *   <li>Persists trades to PostgreSQL via R2DBC</li>
 *   <li>Sends Telegram alerts for all trade events</li>
 *   <li>Provides REST endpoints for trade management and analytics</li>
 * </ul>
 *
 * <p><b>Core Components:</b>
 * <ul>
 *   <li>{@link com.trademesh.execution.PaperTrade} - Entity representing a simulated trade</li>
 *   <li>{@link com.trademesh.execution.TradeStatus} - Enum for trade lifecycle states</li>
 *   <li>{@link com.trademesh.execution.PaperTradeRepository} - R2DBC repository for data access</li>
 *   <li>{@link com.trademesh.execution.PaperTradingEngine} - Core engine for trade execution and management</li>
 *   <li>{@link com.trademesh.execution.PaperTradeController} - REST API for trade operations</li>
 * </ul>
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li><b>Trade Execution:</b> Converts strategy signals into paper trades with entry credit tracking</li>
 *   <li><b>Mark-to-Market:</b> Updates position values based on current option prices from Redis</li>
 *   <li><b>Automatic Exits:</b> Triggers stop loss (2x loss) and target (50% profit) exits</li>
 *   <li><b>P&L Calculation:</b> Tracks both unrealized (mark-to-market) and realized (at close) P&L</li>
 *   <li><b>Telegram Alerts:</b> Sends formatted alerts on trade entry, exit, and key events</li>
 *   <li><b>Analytics:</b> Provides weekly P&L summaries, win rates, and performance metrics</li>
 * </ul>
 *
 * <p><b>REST API Endpoints:</b>
 * <ul>
 *   <li>GET /paper/trades/open - All open trades</li>
 *   <li>GET /paper/trades/history - Closed trades history</li>
 *   <li>GET /paper/trades/pnl/weekly - Weekly P&L summary</li>
 *   <li>POST /paper/trades/{id}/close - Manually close a trade</li>
 *   <li>GET /paper/trades/{id} - Get specific trade details</li>
 *   <li>GET /paper/trades/symbol/{symbol} - Trades by symbol</li>
 *   <li>GET /paper/trades/strategy/{strategyName} - Trades by strategy</li>
 *   <li>GET /paper/trades/stats - Overall trading statistics</li>
 * </ul>
 *
 * <p><b>Database Schema:</b>
 * <p>The {@code paper_trades} table stores all trade records with:
 * <ul>
 *   <li>UUID primary key for unique identification</li>
 *   <li>JSONB column for storing trade legs (strike prices, actions, quantities)</li>
 *   <li>Numeric columns for credit, current value, and P&L tracking</li>
 *   <li>Timestamp columns for entry/exit times and audit trail</li>
 *   <li>Indexes on status, symbol, strategy, and date for query performance</li>
 * </ul>
 *
 * @since 1.0
 */
package com.trademesh.execution;
