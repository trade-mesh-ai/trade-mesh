/**
 * Trading orchestration and scheduling package.
 * <p>
 * Provides end-to-end paper trading functionality:
 * <p>
 * Core Components:
 * - TradingOrchestrator: Main orchestrator connecting all layers
 * - TradingScheduler: Automated scheduled tasks for trading cycles
 * - DailyReportService: Daily P&L reporting and alerts
 * - TradingController: REST endpoints for manual operations
 * <p>
 * Data Models:
 * - StrategyExecutionLog: Audit log for strategy executions
 * - StrategyExecutionLogRepository: Database access for execution logs
 * <p>
 * Workflow:
 * 1. TradingScheduler triggers scheduled tasks at specific times
 * 2. TradingOrchestrator orchestrates the complete trading cycle
 * 3. MarketIntelligenceService generates market intelligence
 * 4. StrategySelector chooses appropriate strategy
 * 5. PaperTradingEngine executes trades
 * 6. AlertService sends Telegram notifications
 * 7. StrategyExecutionLog records all executions
 * 8. DailyReportService generates daily summaries
 * <p>
 * Scheduled Tasks:
 * - Thursday 9:30 AM: Entry signal generation
 * - Every 5 minutes (9 AM - 3 PM, Mon-Fri): Position monitoring
 * - Thursday 2:30 PM: Forced exit and daily report
 * <p>
 * REST Endpoints:
 * - POST /trading/run/{symbol}: Manual trading cycle
 * - GET /trading/positions/open: List open positions
 * - POST /trading/monitor: Manual position monitoring
 * - GET /trading/report/today: Daily summary
 */
package com.trademesh.trading;
