/**
 * Paper Trading Engine for simulating trades without real money.
 * <p>
 * This package provides a complete paper trading system that:
 * - Captures strategy signals and executes simulated trades
 * - Tracks trade lifecycle (OPEN → CLOSED/CANCELLED)
 * - Calculates realized and unrealized P&L
 * - Implements automatic stop loss and target triggers
 * - Persists trades to PostgreSQL via R2DBC
 * - Sends Telegram alerts for all trade events
 * - Provides REST endpoints for trade management and analytics
 *
 * <b>Core Components:</b>
 * - {@link com.trademesh.execution.PaperTrade} - Entity representing a simulated trade
 * - {@link com.trademesh.execution.TradeStatus} - Enum for trade lifecycle states
 * - {@link com.trademesh.execution.PaperTradeRepository} - R2DBC repository for data access
 * - {@link com.trademesh.execution.PaperTradingEngine} - Core engine for trade execution and management
 * - {@link com.trademesh.execution.PaperTradeController} - REST API for trade operations
 *
 * <b>Key Features:</b>
 * - <b>Trade Execution:</b> Converts strategy signals into paper trades with entry credit tracking
 * - <b>Mark-to-Market:</b> Updates position values based on current option prices from Redis
 * - <b>Automatic Exits:</b> Triggers stop loss (2x loss) and target (50% profit) exits
 * - <b>P&L Calculation:</b> Tracks both unrealized (mark-to-market) and realized (at close) P&L
 * - <b>Telegram Alerts:</b> Sends formatted alerts on trade entry, exit, and key events
 * - <b>Analytics:</b> Provides weekly P&L summaries, win rates, and performance metrics
 *
 * <b>REST API Endpoints:</b>
 * - GET /paper/trades/open - All open trades
 * - GET /paper/trades/history - Closed trades history
 * - GET /paper/trades/pnl/weekly - Weekly P&L summary
 * - POST /paper/trades/{id}/close - Manually close a trade
 * - GET /paper/trades/{id} - Get specific trade details
 * - GET /paper/trades/symbol/{symbol} - Trades by symbol
 * - GET /paper/trades/strategy/{strategyName} - Trades by strategy
 * - GET /paper/trades/stats - Overall trading statistics
 *
 * <b>Database Schema:</b>
 * The {@code paper_trades} table stores all trade records with:
 * - UUID primary key for unique identification
 * - JSONB column for storing trade legs (strike prices, actions, quantities)
 * - Numeric columns for credit, current value, and P&L tracking
 * - Timestamp columns for entry/exit times and audit trail
 * - Indexes on status, symbol, strategy, and date for query performance
 *
 * @since 1.0
 */
package com.trademesh.execution;
