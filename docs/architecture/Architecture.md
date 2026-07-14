# TradeMesh — Complete Architecture Documentation

**Version:** 0.0.1-SNAPSHOT | **Last Updated:** July 14, 2026 | **Market:** NSE (India) | **Timezone:** IST (Asia/Kolkata)

TradeMesh is a fully-reactive, Spring WebFlux-based automated options trading platform for NSE that executes multi-leg options strategies (Iron Condor, Bull Put Spread, Bear Call Spread, Expiry Day Strangle) based on real-time market intelligence and risk management rules.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [System Overview](#system-overview)
3. [High-Level Architecture](#high-level-architecture)
4. [Runtime Flow](#runtime-flow)
5. [Package Structure](#package-structure)
6. [Module Responsibilities](#module-responsibilities)
7. [Strategy Architecture](#strategy-architecture)
8. [Market Intelligence Architecture](#market-intelligence-architecture)
9. [Risk Management Architecture](#risk-management-architecture)
10. [Broker Architecture](#broker-architecture)
11. [Database Architecture](#database-architecture)
12. [Scheduler Architecture](#scheduler-architecture)
13. [Configuration Architecture](#configuration-architecture)
14. [API Architecture](#api-architecture)
15. [Reactive Flow (WebFlux)](#reactive-flow-webflux)
16. [Design Patterns Used](#design-patterns-used)
17. [Extension Points](#extension-points)
18. [Deployment Architecture](#deployment-architecture)
19. [Technology Stack](#technology-stack)
20. [Glossary](#glossary)

---

## Executive Summary

TradeMesh is a **fully-reactive, event-driven options trading platform** built on Spring WebFlux 3.3.0 with R2DBC for reactive database access and Redis for caching. It automates multi-leg options strategies on NSE by:

- **Monitoring market conditions** in real-time (VIX, volatility, open interest, max pain)
- **Evaluating trading strategies** based on market intelligence and risk parameters
- **Executing trades** through paper trading simulation (with Zerodha Kite Connect integration)
- **Managing positions** with mark-to-market calculations every 60 seconds
- **Enforcing risk limits** (position sizing, daily loss limits, margin checks)
- **Sending real-time alerts** via Telegram for all trading events

The system is designed for **horizontal scalability** with stateless application instances coordinating through shared PostgreSQL and Redis infrastructure. All database writes happen exclusively through schedulers, while REST controllers remain read-only, ensuring data consistency and preventing race conditions.

---

## System Overview

### Purpose

TradeMesh automates options trading on NSE by:
- Fetching real-time market data from Zerodha Kite Connect API
- Calculating market metrics (HV20, ATR14, IVR, IV/HV ratio, PCR)
- Classifying market conditions (SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE)
- Selecting and evaluating applicable strategies based on market condition
- Executing trades via paper trading engine
- Monitoring open positions with mark-to-market every 60 seconds
- Closing positions based on stop-loss, take-profit, or DTE-exit triggers
- Sending Telegram alerts for all trading events

### Key Characteristics

| Characteristic | Implementation |
|---|---|
| **Reactive** | Spring WebFlux throughout; no `.block()` anywhere |
| **Database** | R2DBC + PostgreSQL (no JPA, no Hibernate) |
| **Caching** | Redis for tokens, option chains, tick prices, market intelligence |
| **Schema Management** | Liquibase for all migrations (13 changesets) |
| **Build System** | Gradle 8.x |
| **Java Version** | Java 21 |
| **Base Package** | `com.trademesh` |
| **Market Hours** | 9:15 AM - 3:30 PM IST, Monday-Friday |
| **Trading Mode** | Paper trading (simulation) |

### Core Responsibilities

1. **Market Intelligence** — Fetch and aggregate VIX, OI, max pain, metrics
2. **Strategy Selection** — Choose applicable strategy based on market condition
3. **Trade Execution** — Execute trades from strategy signals
4. **Position Monitoring** — Mark-to-market every 60 seconds
5. **Risk Management** — Enforce position limits and daily loss limits
6. **Alert Notifications** — Send Telegram alerts for all events
7. **Data Persistence** — Store trades, execution logs, intelligence snapshots

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          EXTERNAL SYSTEMS                                   │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐          │
│  │  Zerodha Kite    │  │   Telegram Bot   │  │   Market Data    │          │
│  │  Connect API     │  │   (Alerts)       │  │   (WebSocket)    │          │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘          │
└───────────┼──────────────────────┼──────────────────────┼───────────────────┘
            │                      │                      │
            ▼                      ▼                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      TRADEMESH APPLICATION LAYER                            │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                    REST CONTROLLERS (Read-Only)                      │  │
│  │  ┌─────────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────────┐    │  │
│  │  │ Trading     │ │ Strategy     │ │ Market   │ │ Paper Trade  │    │  │
│  │  │ Controller  │ │ Controller   │ │ Data     │ │ Controller   │    │  │
│  │  └─────────────┘ └──────────────┘ └──────────┘ └──────────────┘    │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│  ┌──────────────────────────────────▼──────────────────────────────────┐  │
│  │                    SERVICE LAYER (Business Logic)                   │  │
│  │  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │  │
│  │  │ Market           │ │ Strategy         │ │ Trading          │   │  │
│  │  │ Intelligence     │ │ Orchestrator     │ │ Orchestrator     │   │  │
│  │  │ Service          │ │                  │ │                  │   │  │
│  │  └──────────────────┘ └──────────────────┘ └──────────────────┘   │  │
│  │  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │  │
│  │  │ Paper Trading    │ │ Risk Manager     │ │ Portfolio        │   │  │
│  │  │ Engine           │ │                  │ │ Service          │   │  │
│  │  └──────────────────┘ └──────────────────┘ └──────────────────┘   │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│  ┌──────────────────────────────────▼──────────────────────────────────┐  │
│  │                    SCHEDULERS (DB Write Access)                     │  │
│  │  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │  │
│  │  │ Trading          │ │ Intelligence     │ │ Mark-to-Market   │   │  │
│  │  │ Scheduler        │ │ Scheduler        │ │ Scheduler        │   │  │
│  │  │ (Entry/Exit)     │ │ (VIX, OI, etc)   │ │ (Position MTM)   │   │  │
│  │  └──────────────────┘ └──────────────────┘ └──────────────────┘   │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                    │                                        │
│  ┌──────────────────────────────────▼──────────────────────────────────┐  │
│  │                    REPOSITORY LAYER (R2DBC)                         │  │
│  │  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │  │
│  │  │ Paper Trade      │ │ Instrument       │ │ Strategy         │   │  │
│  │  │ Repository       │ │ Repository       │ │ Execution Log    │   │  │
│  │  └──────────────────┘ └──────────────────┘ └──────────────────┘   │  │
│  │  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐   │  │
│  │  │ Nifty Daily      │ │ VIX History      │ │ IV History       │   │  │
│  │  │ Price Repository │ │ Repository       │ │ Repository       │   │  │
│  │  └──────────────────┘ └──────────────────┘ └──────────────────┘   │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
            │                                              │
            ▼                                              ▼
┌──────────────────────────────────┐      ┌──────────────────────────────────┐
│      PostgreSQL Database         │      │      Redis Cache Store           │
│  ┌────────────────────────────┐  │      │  ┌──────────────────────────┐   │
│  │ Core Tables:               │  │      │  │ Token Storage            │   │
│  │ - paper_trades             │  │      │  │ Option Chain Cache       │   │
│  │ - instruments              │  │      │  │ Tick Price Cache         │   │
│  │ - strategy_execution_logs  │  │      │  │ Market Intelligence      │   │
│  │ - nifty_daily_prices       │  │      │  │ VIX Data                 │   │
│  │ - vix_history              │  │      │  │ OI Snapshots             │   │
│  │ - iv_history               │  │      │  │ Max Pain Data            │   │
│  │ - oi_snapshots             │  │      │  └──────────────────────────┘   │
│  │ - max_pain_history         │  │      │                                  │
│  │ - option_chain_snapshots   │  │      │  TTL: 60 sec - 24 hours          │
│  │ - vix_alerts               │  │      │  (Strategy-specific)             │
│  └────────────────────────────┘  │      │                                  │
│                                   │      │                                  │
│  Schema: trade_mesh               │      │                                  │
│  Migrations: Liquibase (13)       │      │                                  │
└──────────────────────────────────┘      └──────────────────────────────────┘
```

---

## Runtime Flow

### Complete Trading Cycle (Market Open to Close)

```
MARKET OPEN (9:15 AM IST)
│
├─→ IntelligenceScheduler (every 5 minutes, cron: "0 */5 9-15 ? * MON-FRI")
│   ├─→ Check token availability
│   ├─→ Fetch VIX, OI, max pain for NIFTY50 and NIFTYBANK
│   ├─→ Calculate market metrics (HV20, ATR14, IVR, IV/HV, PCR)
│   ├─→ Determine market condition (SIDEWAYS, TRENDING_BULLISH, etc.)
│   ├─→ Cache in Redis (5-min TTL)
│   └─→ Detect condition changes and send Telegram alerts
│
├─→ TradingScheduler - dailySafetyNetCheck (9:20 AM, cron: "0 20 9 * * MON-FRI")
│   └─→ Check DTE-exit conditions for IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD
│       (Backstop for any mark-to-market failures)
│
├─→ TradingScheduler - scheduledDailyEntry (9:30 AM, cron: "0 30 9 * * MON-FRI")
│   ├─→ Fetch fresh market intelligence (bypasses cache)
│   ├─→ StrategySelector picks strategy based on market condition
│   ├─→ Strategy evaluates and generates signal
│   ├─→ RiskManager validates position limits
│   ├─→ PaperTradingEngine executes trade
│   ├─→ Save PaperTrade record to PostgreSQL
│   ├─→ Log execution event
│   └─→ Send Telegram alert (ENTRY_SIGNAL)
│
├─→ MarkToMarketScheduler (every 60 seconds, fixedRate: 60000ms)
│   ├─→ Fetch all OPEN trades from PostgreSQL
│   ├─→ For each trade:
│   │   ├─→ Fetch latest prices from Redis (KiteTicker)
│   │   ├─→ Calculate current P&L (in POINTS, not rupees)
│   │   ├─→ Check stop-loss trigger (100% of credit)
│   │   ├─→ Check take-profit trigger (50% of credit)
│   │   ├─→ Check DTE-exit trigger (if applicable)
│   │   ├─→ Check VIX-panic-exit trigger (if applicable)
│   │   ├─→ Update PaperTrade record in PostgreSQL
│   │   └─→ Send alert if exit triggered
│   │
│   └─→ If exit triggered:
│       ├─→ Calculate realized P&L
│       ├─→ Update trade status to CLOSED
│       ├─→ Send Telegram alert (EXIT_SIGNAL)
│       └─→ Log execution event
│
├─→ TradingScheduler - scheduledDailyExit (3:15 PM, cron: "0 15 15 * * MON-FRI")
│   └─→ Force close EXPIRY_DAY_STRANGLE positions only
│       (IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD can hold overnight)
│
└─→ TradingScheduler - scheduledDailyReport (3:45 PM, cron: "0 45 15 * * MON-FRI")
    ├─→ Aggregate daily P&L from all closed trades
    ├─→ Calculate win rate, avg profit/loss
    ├─→ Send Telegram daily report
    └─→ Log summary
```

### Strategy Evaluation Pipeline

```
StrategySelector.evaluateSelectedStrategy(MarketIntelligence)
│
├─→ Determine applicable strategy based on market condition:
│   ├─→ EXPIRY_DAY → EXPIRY_DAY_STRANGLE
│   ├─→ SIDEWAYS → IRON_CONDOR
│   ├─→ TRENDING_BULLISH → BULL_PUT_SPREAD
│   └─→ TRENDING_BEARISH → BEAR_CALL_SPREAD
│
└─→ For selected strategy:
    ├─→ Check if enabled in application.yml
    ├─→ Check if allowed day (MONDAY, TUESDAY, etc.)
    ├─→ Check if within VIX range (min-vix ≤ VIX ≤ max-vix)
    ├─→ Check if before latest-entry-time
    │
    └─→ Strategy-specific evaluation:
        │
        ├─→ IRON_CONDOR:
        │   ├─→ Calculate confidence score (IVR, IV/HV, ATR%, VIX, PCR)
        │   ├─→ Check hard gates (min-score: 51)
        │   ├─→ Fetch option chain
        │   ├─→ Select strikes using delta-based approach (target delta = 0.18)
        │   ├─→ Calculate net credit and P&L matrix
        │   ├─→ Adjust exit levels based on ATR% (calm/normal/high)
        │   └─→ Return StrategyResult with signal or skip reason
        │
        ├─→ BULL_PUT_SPREAD / BEAR_CALL_SPREAD:
        │   ├─→ Check DTE range (dte-min: 2, dte-max: 4 trading days)
        │   ├─→ Select short strike (target delta: 0.30)
        │   ├─→ Select long strike (wing width: 100 points)
        │   ├─→ Calculate net credit
        │   └─→ Return StrategyResult with signal or skip reason
        │
        └─→ EXPIRY_DAY_STRANGLE:
            ├─→ Check if Tuesday (expiry day)
            ├─→ Check if before latest-entry-time (12:30 PM)
            ├─→ Fetch OI analysis
            ├─→ Select call strike (highest OI call, ATR-scaled distance)
            ├─→ Select put strike (highest OI put, ATR-scaled distance)
            ├─→ Validate minimum distance from spot
            └─→ Return StrategyResult with signal or skip reason
```

### Market Intelligence Generation Pipeline

```
MarketIntelligenceService.generateFreshIntelligence(symbol)
│
├─→ Fetch all components in parallel using Mono.zip():
│   ├─→ VixMonitorService.fetchVix() → VixData
│   ├─→ OptionChainService.fetchOptionChain() → OptionChain
│   ├─→ OIAnalyserService.analyzeOI() → OIAnalysis
│   └─→ MaxPainCalculatorService.calculateMaxPain() → MaxPainResult
│
├─→ MarketMetricsService.calculateMetrics():
│   ├─→ HV20: 20-day historical volatility from nifty_daily_prices
│   ├─→ ATR14: 14-day Average True Range
│   ├─→ ATRPct: ATR as percentage of spot price
│   ├─→ IVR: IV Rank (current IV percentile over 52-week range)
│   ├─→ IV/HV Ratio: Current IV divided by HV20
│   └─→ PCR: Put/Call Ratio from option chain OI
│
├─→ Classify market condition:
│   ├─→ EXPIRY_DAY: If today is expiry day (Tuesday)
│   ├─→ HIGH_VOLATILITY: If VIX > 20
│   ├─→ SIDEWAYS: If IVR 20-80 and PCR 0.8-1.2 (neutral)
│   ├─→ TRENDING_BULLISH: If IV/HV > 1.1 and PCR < 0.8
│   ├─→ TRENDING_BEARISH: If IV/HV > 1.1 and PCR > 1.2
│   └─→ NO_TRADE: If conditions unfavorable
│
├─→ Assess trading safety:
│   ├─→ isSafeToTrade = (VIX within range) AND (condition != NO_TRADE)
│   └─→ Return MarketIntelligence with all components
│
└─→ Cache in Redis (5-min TTL) under key: "intelligence:{symbol}"
```

---

## Package Structure

### `com.trademesh`

#### `alert` — Telegram Notifications
- **`Alert`** — Alert entity with type and message
- **`AlertType`** — Enum: ENTRY, EXIT, ERROR, VIX_ALERT, SYSTEM
- **`TelegramClient`** — HTTP client for Telegram Bot API
- **`TelegramNotificationBot`** — Service to send Telegram messages
- **`TelegramProperties`** — Configuration properties for Telegram

#### `common` — Shared Utilities
- **`dto`** — Data transfer objects for API responses
- **`exception`** — Custom exceptions (InvalidStrategyException, InsufficientMarginException, etc.)
- **`handler`** — Global exception handlers for REST controllers
- **`resilience`** — Retry logic and circuit breaker patterns

#### `config` — Application Configuration
- **`ApplicationConstants`** — Market hours, symbols, timezone constants
- **`JacksonConfig`** — JSON serialization/deserialization configuration
- **`JdbcConfig`** — JDBC DataSource for Liquibase
- **`LotSizeService`** — Centralized lot size lookup (NIFTY50: 65, NIFTYBANK: 30)
- **`PaperTradeLegsConverter`** — R2DBC converter for trade legs JSONB
- **`R2dbcConfig`** — R2DBC connection pool and custom converters
- **`ResilienceConfig`** — Retry and circuit breaker policies
- **`WebClientConfig`** — WebClient configuration for HTTP calls

#### `domain` — Core Domain Entities
- **`Instrument`** — Zerodha instrument master (token, symbol, strike, expiry, lot size)
- **`InstrumentRepository`** — R2DBC repository for instruments
- **`NiftyDailyPrice`** — NIFTY50 OHLC data (trade_date, open, high, low, close, volume)
- **`NiftyDailyPriceRepository`** — R2DBC repository for daily prices

#### `execution` — Trade Execution & Position Management
- **`MarkToMarketScheduler`** — Marks all open trades to market every 60 seconds
- **`OrderExecutionService`** — Executes orders via Zerodha API
- **`PaperTrade`** — Simulated trade record (UUID id, symbol, strategy, status, legs, P&L)
- **`PaperTradeController`** — REST endpoints for paper trade queries
- **`PaperTradeRepository`** — R2DBC repository for paper trades
- **`PaperTradingEngine`** — Simulates trade execution and mark-to-market calculations
- **`TradeStatus`** — Enum: OPEN, CLOSED, CANCELLED
- **`LivePnlData`** — DTO for live P&L data (liveUnrealizedPnl, isPnlLive flag)

#### `intelligence` — Market Intelligence & Analysis
- **`IntelligenceScheduler`** — Refreshes market intelligence every 5 minutes
- **`KiteHistoricalService`** — Fetches historical OHLC from Kite API
- **`MarketCondition`** — Enum: SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE
- **`MarketIntelligence`** — Aggregated market data (VIX, OI, max pain, condition, metrics)
- **`MarketIntelligenceController`** — REST endpoints for market intelligence
- **`MarketIntelligenceService`** — Generates complete market intelligence for a symbol
  - **`generateIntelligence(symbol)`** — Cached method (5-min TTL) for read-only endpoints
  - **`generateFreshIntelligence(symbol)`** — Bypasses cache read for trade-decision path
- **`MarketMetrics`** — Calculated metrics (HV20, ATR14, ATRPct, IVR, IV/HV ratio, PCR)
- **`MarketMetricsService`** — Calculates market metrics from historical data
- **`NiftyDailyPriceService`** — Manages NIFTY50 daily price data
- **`SignalDirection`** — Enum: BULLISH, BEARISH, NEUTRAL
- **`SignalService`** — Generates trading signals from market intelligence
- **`TradingSignal`** — Market signal with direction and confidence

##### `intelligence.iv` — Implied Volatility
- **`ImpliedVolatilityCalculator`** — Black-Scholes IV and delta calculations
- **`IvHistoryService`** — Tracks daily ATM IV at 9:25 AM
- **`IvHistory`** — Daily ATM IV record (symbol, trade_date, atm_iv, call_iv, put_iv, spot_price, expiry_date)
- **`IvHistoryScheduler`** — Captures ATM IV every day at 9:25 AM

##### `intelligence.maxpain` — Max Pain Analysis
- **`MaxPainCalculatorService`** — Calculates theoretical max pain for call/put sides
- **`MaxPainScheduler`** — Persists max pain snapshots every 30 minutes
- **`MaxPainResult`** — Max pain record (symbol, expiry_date, max_pain_strike, spot_price, distance)

##### `intelligence.oi` — Open Interest Analysis
- **`OIAnalysis`** — OI wall detection (max_call_oi_strike, max_put_oi_strike, market_bias)
- **`OIAnalyserService`** — Analyzes option chain for OI walls
- **`OIAnalysisScheduler`** — Captures OI snapshots every 15 minutes
- **`OISnapshot`** — OI snapshot record (symbol, max_call_strike, max_put_strike, data JSONB)

##### `intelligence.vix` — VIX Monitoring
- **`VixAlertService`** — Sends Telegram alerts when VIX crosses thresholds
- **`VixData`** — Current VIX value, level, change, safe-to-trade flag
- **`VixHistory`** — VIX snapshot record (vix_value, vix_level, recorded_at)
- **`VixMonitorService`** — Fetches current VIX from Kite API
- **`VixScheduler`** — Persists VIX every 15 minutes

#### `portfolio` — Portfolio Management
- **`PortfolioService`** — Aggregates positions and calculates portfolio P&L
- **`PortfolioSummary`** — Aggregated portfolio metrics (total_value, total_pnl, position_count)
- **`PositionSummary`** — Individual position summary (symbol, quantity, avg_price, current_price, pnl)

#### `risk` — Risk Management
- **`RiskLimitsConfig`** — Risk configuration (max_position_size, daily_loss_limit, margin_buffer)
- **`RiskManager`** — Validates trades against risk limits before execution

#### `scheduler` — Scheduled Jobs Coordination
- **`TradingScheduler`** — Orchestrates daily trading cycles and position exits

#### `strategy` — Strategy Engine
- **`StrategyConfig`** — Strategy configuration (name, max_lots, stop_loss%, target_profit%, VIX range, allowed_days)
- **`StrategyController`** — REST endpoints for strategy evaluation and status
- **`StrategyEngine`** — Evaluates all applicable strategies for a symbol
- **`StrategyOrchestrator`** — Selects and executes the best strategy
- **`StrategyResult`** — Strategy evaluation result (executable flag, signal, skip reason)
- **`StrategySelector`** — Selects strategy based on market condition
- **`TradeLeg`** — Individual trade leg (instrument, action, strike, quantity, expiry)
- **`TradeSignal`** — Strategy output (legs, credit, max loss, confidence, reason)
- **`TradingStrategy`** — Interface for all strategy implementations

##### `strategy.config` — Strategy Configuration
- **`StrategiesConfig`** — Loads all strategy configs from application.yml
- **`StrategyProperties`** — Individual strategy configuration properties

##### `strategy.enums` — Strategy Enums
- **`Action`** — BUY, SELL
- **`Confidence`** — LOW, MEDIUM, HIGH
- **`LegType`** — ENTRY, EXIT, ADJUSTMENT
- **`OptionType`** — CE (Call), PE (Put)
- **`SignalType`** — ENTER, EXIT, ADJUST

##### `strategy.expiryday` — Expiry Day Strangle
- **`ExpiryDayStrategy`** — Sells strangle at OI walls on expiry day (Tuesday)

##### `strategy.ironcondor` — Iron Condor
- **`IronCondorSignal`** — Iron Condor-specific signal with P&L matrix
- **`IronCondorStrategy`** — Sells call and put spreads in sideways markets

##### `strategy.spread` — Spread Strategies
- **`BearCallSpreadStrategy`** — Sells OTM call spread in bearish markets
- **`BullPutSpreadStrategy`** — Sells OTM put spread in bullish markets

#### `trading` — Trading Orchestration
- **`AdminController`** — Admin endpoints for bootstrapping and testing
- **`DailyReportService`** — Generates daily P&L reports and sends via Telegram
- **`MarketCalendarService`** — Checks if market is open (9:15 AM - 3:30 PM IST, Mon-Fri)
- **`StrategyExecutionLog`** — Audit log for strategy execution events
- **`StrategyExecutionLogRepository`** — R2DBC repository for execution logs
- **`TradingController`** — REST endpoints for manual trading operations
- **`TradingOrchestrator`** — Coordinates trading cycles, position monitoring, and exits
- **`TradingScheduler`** — Scheduled jobs for daily entry (9:30 AM), exit (3:15 PM), and reporting (3:45 PM)

#### `zerodha` — Zerodha Integration
- **`zerodha.auth`** — OAuth2 authentication and token management
  - **`TokenAvailabilityTracker`** — Tracks token availability in Redis
  - **`ZerodhaAuthController`** — OAuth2 login/callback endpoints
  - **`ZerodhaAuthService`** — Token exchange and storage
  - **`ZerodhaSession`** — OAuth2 session entity
  - **`ZerodhaSessionRepository`** — R2DBC repository for sessions

- **`zerodha.options`** — Option chain and Greeks
  - **`ImpliedVolatilityCalculator`** — Black-Scholes IV and delta
  - **`OptionChain`** — Option chain data (spot_price, expiry_date, strikes)
  - **`OptionChainService`** — Fetches and caches option chains
  - **`OptionStrike`** — Individual strike (price, call_ltp, put_ltp, call_iv, put_iv, oi)
  - **`OptionChainSnapshotScheduler`** — Captures option chain snapshots every 5 minutes

- **`zerodha.orders`** — Order execution
  - **`OrderExecutionService`** — Places orders via Kite API
  - **`OrderStatus`** — Order status tracking

- **`zerodha.ticker`** — WebSocket market data
  - **`KiteTickerService`** — WebSocket connection to KiteTicker
  - **`TickPrice`** — Tick price data (symbol, ltp, timestamp)
  - **`TickerController`** — Server-Sent Events endpoint for price streaming
  - **`TickerConnectionManager`** — Manages WebSocket connection lifecycle

---

## Module Responsibilities

### REST Controllers (Read-Only)

| Controller | Responsibility | Key Endpoints |
|---|---|---|
| **TradingController** | Trading status, open positions, daily reports | GET /trading/positions/open, POST /trading/run/{symbol} |
| **StrategyController** | Strategy evaluation, applicable strategies | GET /strategy/evaluate/{symbol}, GET /strategy/applicable/{symbol} |
| **MarketIntelligenceController** | Market intelligence, VIX, OI, max pain | GET /intelligence/{symbol}, GET /intelligence/vix |
| **PaperTradeController** | Paper trade history, P&L, statistics | GET /paper/trades/open, GET /paper/trades/history |
| **ZerodhaAuthController** | OAuth2 login, token status | GET /zerodha/login, GET /zerodha/callback |
| **AdminController** | Bootstrap data, test evaluations | POST /admin/bootstrap/historical-prices |

### Service Layer (Business Logic)

| Service | Responsibility |
|---|---|
| **TradingOrchestrator** | Orchestrates complete trading cycle (entry → monitoring → exit) |
| **StrategyOrchestrator** | Evaluates strategies, selects applicable ones |
| **StrategySelector** | Selects strategy based on market condition |
| **MarketIntelligenceService** | Aggregates market data (VIX, OI, max pain, condition) |
| **MarketMetricsService** | Calculates HV20, ATR14, IVR, IV/HV ratio, PCR |
| **PaperTradingEngine** | Simulates trade execution, mark-to-market |
| **RiskManager** | Enforces position limits, daily loss limits |
| **PortfolioService** | Aggregates portfolio data, P&L |
| **ZerodhaAuthService** | OAuth2 flow, token management |
| **OptionChainService** | Fetches and caches option chain data |
| **VixMonitorService** | Fetches current VIX from Kite API |
| **OIAnalyserService** | Analyzes option chain for OI walls |
| **MaxPainCalculatorService** | Calculates max pain for call/put sides |
| **DailyReportService** | Generates daily P&L reports |
| **TelegramNotificationBot** | Sends Telegram alerts |

### Schedulers (DB Write Access)

| Scheduler | Frequency | Responsibility |
|---|---|---|
| **TradingScheduler.dailySafetyNetCheck** | 9:20 AM | DTE-exit safety net check |
| **TradingScheduler.scheduledDailyEntry** | 9:30 AM | Strategy evaluation and trade entry |
| **TradingScheduler.scheduledDailyExit** | 3:15 PM | Force close EXPIRY_DAY_STRANGLE positions |
| **TradingScheduler.scheduledDailyReport** | 3:45 PM | Generate daily P&L report |
| **IntelligenceScheduler.refreshMarketIntelligence** | Every 5 min | Refresh market intelligence for all symbols |
| **MarkToMarketScheduler.markToMarketScheduled** | Every 60 sec | Mark open trades to market, check exits |
| **VixScheduler.persistVixData** | Every 15 min | Persist VIX snapshot to database |
| **IvHistoryScheduler.captureAtmIv** | 9:25 AM | Capture ATM IV for current expiry |
| **MaxPainScheduler.captureMaxPainSnapshots** | Every 30 min | Capture max pain snapshots |
| **OIAnalysisScheduler.captureOIAnalysisSnapshots** | Every 15 min | Capture OI analysis snapshots |
| **OptionChainSnapshotScheduler.captureOptionChainSnapshots** | Every 5 min | Capture full option chain snapshots |

### Repository Layer (R2DBC)

| Repository | Entity | Purpose |
|---|---|---|
| **PaperTradeRepository** | PaperTrade | Paper trade records |
| **InstrumentRepository** | Instrument | Zerodha instruments |
| **NiftyDailyPriceRepository** | NiftyDailyPrice | NIFTY50 daily OHLC |
| **StrategyExecutionLogRepository** | StrategyExecutionLog | Strategy execution events |
| **VixHistoryRepository** | VixHistory | VIX snapshots |
| **IvHistoryRepository** | IvHistory | ATM IV history |
| **MaxPainHistoryRepository** | MaxPainResult | Max pain snapshots |
| **OIAnalysisRepository** | OIAnalysis | OI analysis snapshots |
| **OptionChainSnapshotRepository** | OptionChainSnapshot | Full option chain snapshots |
| **VixAlertRepository** | VixAlert | VIX threshold alerts |

---

## Strategy Architecture

### Strategy Interface

All strategies implement `TradingStrategy`:

```java
public interface TradingStrategy {
    String getName();
    boolean isApplicable(MarketCondition condition);
    Mono<StrategyResult> evaluate(MarketIntelligence intelligence);
}
```

### Strategy Selection Flow

```
StrategySelector.evaluateSelectedStrategy(MarketIntelligence)
│
├─→ Determine applicable strategy based on market condition:
│   ├─→ EXPIRY_DAY → EXPIRY_DAY_STRANGLE
│   ├─→ SIDEWAYS → IRON_CONDOR
│   ├─→ TRENDING_BULLISH → BULL_PUT_SPREAD
│   └─→ TRENDING_BEARISH → BEAR_CALL_SPREAD
│
└─→ Call strategy.evaluate(intelligence)
    └─→ Return StrategyResult (executable, signal, skipReason)
```

### Strategy Configuration

All strategies configured in `application.yml` under `trading-strategies.items`:

```yaml
trading-strategies:
  items:
  - name: IRON_CONDOR
    enabled: true
    allowed-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
    applicable-conditions: [SIDEWAYS]
    max-vix: 20.0
    min-vix: 12.0
    latest-entry-time: "13:00"
    min-credit: 30.0
    max-lots: 1
    stop-loss-percent: 100.0
    target-profit-percent: 50.0
    extra:
      target-delta: 0.18
      wing-width: 200
      min-score: 51
      dte-min: 3
      dte-max: 4
      dte-exit-threshold: 2
      vix-panic-threshold: 25.0
```

### Strategy-Specific Details

#### 1. IRON_CONDOR

**Market Condition:** SIDEWAYS

**Entry Logic:**
- Sells both call and put spreads in sideways markets
- Strike selection: Delta-based (target delta 0.18) via Black-Scholes
- Wing width: 200 points
- Entry-side DTE: Uses TRADING DAYS (via MarketCalendarService.countTradingDays())
  - `dte-min: 3, dte-max: 4` (trading days, not calendar days)
- Confidence scoring:
  - IVR (20-80 range): 15-30 points
  - IV/HV ratio (≥1.1): 10-20 points
  - ATR% (<0.6-1.0): 10-20 points
  - VIX (12-20 range): 5-15 points
  - PCR (0.8-1.2 balanced): 15 points
  - **Minimum score: 51/100** to execute
- Minimum credit: 30 points (15% of wing width)

**Exit Conditions (Priority Order):**
1. **VIX_PANIC_EXIT:** VIX exceeds `vix-panic-threshold: 25.0` — highest priority
2. **DTE_EXIT:** Trading days remaining ≤ `dte-exit-threshold: 2` — uses shared `PaperTradingEngine.isDteExitDue()`
3. **LEG_BREACH / STOPLOSS_HIT / TARGET_HIT:** Leg breach, 100% credit loss, or 50% credit collected
4. **FORCE_CLOSE_EOD:** NO LONGER APPLIES (positions can hold overnight)

**Dynamic Exit Adjustment:**
- Calm market (ATR% < 0.7): Tighter targets (90% of base)
- High volatility (ATR% > 1.0): Wider targets (110% of base)

#### 2. BULL_PUT_SPREAD

**Market Condition:** TRENDING_BULLISH

**Entry Logic:**
- Sells OTM put spread in bullish markets
- Strike selection: Delta-based (target delta 0.30)
- Wing width: 100 points
- Entry-side DTE: Uses TRADING DAYS
  - `dte-min: 2, dte-max: 4` (trading days)
- Minimum credit: 10 points (10% of wing width)

**Exit Conditions (Priority Order):**
1. **VIX_PANIC_EXIT:** VIX exceeds `vix-panic-threshold: 25.0`
2. **DTE_EXIT:** Trading days remaining ≤ `dte-exit-threshold: 1`
3. **LEG_BREACH / STOPLOSS_HIT / TARGET_HIT:** Leg breach, 100% credit loss, or 50% credit collected
4. **FORCE_CLOSE_EOD:** NO LONGER APPLIES

#### 3. BEAR_CALL_SPREAD

**Market Condition:** TRENDING_BEARISH

**Entry Logic:**
- Sells OTM call spread in bearish markets
- Strike selection: Delta-based (target delta 0.30)
- Wing width: 100 points
- Entry-side DTE: Uses TRADING DAYS
  - `dte-min: 2, dte-max: 4` (trading days)
- Minimum credit: 10 points (10% of wing width)

**Exit Conditions (Priority Order):**
1. **VIX_PANIC_EXIT:** VIX exceeds `vix-panic-threshold: 25.0`
2. **DTE_EXIT:** Trading days remaining ≤ `dte-exit-threshold: 1`
3. **LEG_BREACH / STOPLOSS_HIT / TARGET_HIT:** Leg breach, 100% credit loss, or 50% credit collected
4. **FORCE_CLOSE_EOD:** NO LONGER APPLIES

#### 4. EXPIRY_DAY_STRANGLE

**Market Condition:** EXPIRY_DAY (Tuesday)

**Entry Logic:**
- Trades ONLY on Tuesday (NSE expiry day)
- Sells strangle at OI walls for maximum theta decay
- Strike selection: OI-wall based with ATR-scaled safety boundary
  - `min-distance-atr-multiplier: 0.4` — ATR14 × 0.4 = minimum distance from spot
  - Falls back to boundary strike if OI wall too close
  - Hard skip if OI wall on wrong side of spot (call strike ≤ spot or put strike ≥ spot)
- Expiry date: Fixed — correctly picks today's expiry (not next week's)
- Entry time limit: 12:30 PM IST

**Exit Conditions:**
- **TARGET_HIT:** 50% of credit collected
- **STOPLOSS_HIT:** 100% of credit collected (max loss)
- **FORCE_CLOSE_EOD:** 3:15 PM IST (end of day)

---

## Market Intelligence Architecture

### Intelligence Components

```
MarketIntelligence
├─→ VixData
│   ├─→ currentVix: Double
│   ├─→ vixLevel: String (LOW, MEDIUM, HIGH, EXTREME)
│   ├─→ change: Double
│   └─→ isSafeToTrade: Boolean
│
├─→ OptionChain
│   ├─→ spotPrice: BigDecimal
│   ├─→ expiryDate: LocalDate
│   └─→ strikes: List<OptionStrike>
│
├─→ OIAnalysis
│   ├─→ maxCallOIStrike: BigDecimal
│   ├─→ maxPutOIStrike: BigDecimal
│   ├─→ marketBias: String (BULLISH, BEARISH, NEUTRAL)
│   └─→ data: JSONB
│
├─→ MaxPainResult
│   ├─→ maxPainStrike: BigDecimal
│   ├─→ spotPrice: BigDecimal
│   └─→ distanceFromSpot: BigDecimal
│
├─→ MarketMetrics
│   ├─→ hv20: BigDecimal (20-day historical volatility)
│   ├─→ atr14: BigDecimal (14-day Average True Range)
│   ├─→ atrPct: BigDecimal (ATR as % of spot)
│   ├─→ ivr: BigDecimal (IV Rank, 0-100)
│   ├─→ ivHvRatio: BigDecimal (Current IV / HV20)
│   └─→ pcr: BigDecimal (Put/Call Ratio from OI)
│
├─→ MarketCondition: SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE
│
└─→ isSafeToTrade: Boolean (VIX within range AND condition != NO_TRADE)
```

### Market Condition Classification

```
if (today is Tuesday) {
    condition = EXPIRY_DAY
} else if (VIX > 20) {
    condition = HIGH_VOLATILITY
} else if (IVR 20-80 AND PCR 0.8-1.2) {
    condition = SIDEWAYS
} else if (IV/HV > 1.1 AND PCR < 0.8) {
    condition = TRENDING_BULLISH
} else if (IV/HV > 1.1 AND PCR > 1.2) {
    condition = TRENDING_BEARISH
} else {
    condition = NO_TRADE
}
```

### Caching Strategy

**Two methods for different use cases:**

1. **`generateIntelligence(symbol)`** — Cached (5-min TTL)
   - Used by: Read-only REST endpoints, monitoring, display
   - Retrieves from Redis cache first
   - If not found, generates and caches
   - Reduces Kite API calls

2. **`generateFreshIntelligence(symbol)`** — Bypasses cache read
   - Used by: `TradingOrchestrator.runTradingCycle()` only
   - Always fetches fresh data from Kite API
   - Still writes to cache after fetch
   - Ensures trade decisions use current market data

### Market Metrics Calculation

**HV20 (20-day Historical Volatility):**
```
1. Fetch last 20 daily closes from nifty_daily_prices
2. Calculate daily log returns: ln(close[i] / close[i-1])
3. Calculate standard deviation of returns
4. Annualize: stdev * sqrt(252)
```

**ATR14 (14-day Average True Range):**
```
1. Fetch last 14 daily OHLC from nifty_daily_prices
2. For each day: TR = max(high - low, |high - prev_close|, |low - prev_close|)
3. Calculate 14-day average of TR
```

**IVR (IV Rank):**
```
1. Fetch ATM IV for current expiry
2. Fetch 52-week IV range (min and max)
3. IVR = (current_IV - min_IV) / (max_IV - min_IV) * 100
```

**IV/HV Ratio:**
```
IV/HV = current_ATM_IV / HV20
```

**PCR (Put/Call Ratio):**
```
PCR = total_put_OI / total_call_OI
```

---

## Risk Management Architecture

### Risk Limits Configuration

```yaml
risk:
  max-position-size-per-symbol: 20
  daily-loss-limit-percent: 2.0
  max-sector-exposure: 2000000
  min-margin-buffer-percent: 10
  max-concurrent-open-positions: 3
  position-sizing:
    risk-percent: 1.0
    max-quantity: 10
```

### Risk Checks

**RiskManager.isWithinRiskLimits(symbol, quantity):**

1. **Max Position Size Check:**
   - Verify: `quantity ≤ max-position-size-per-symbol`
   - Prevents over-concentration in single symbol

2. **Daily Loss Limit Check:**
   - Track daily P&L from all closed trades
   - Verify: `daily_loss ≤ account_capital * daily-loss-limit-percent`
   - Prevents catastrophic daily losses

3. **Sector Exposure Check:**
   - Aggregate exposure across related symbols
   - Verify: `sector_exposure ≤ max-sector-exposure`
   - Prevents sector concentration risk

4. **Margin Buffer Check:**
   - Verify: `available_margin ≥ required_margin * (1 + min-margin-buffer-percent)`
   - Ensures margin safety buffer

5. **Concurrent Positions Check:**
   - Verify: `open_positions < max-concurrent-open-positions`
   - Prevents over-leverage

### Position Sizing

**Capital-based position sizing:**
```
risk_amount = account_capital * risk-percent
position_size = risk_amount / max_loss_per_trade
```

---

## Broker Architecture

### Zerodha Integration

```
┌─────────────────────────────────────────────────────────────┐
│                    Zerodha Kite Connect                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ OAuth2 Authentication                                │  │
│  │ - Login URL: https://kite.zerodha.com/connect/login  │  │
│  │ - Callback: http://localhost:8080/zerodha/callback   │  │
│  │ - Token TTL: 8 hours                                 │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ REST API Endpoints                                   │  │
│  │ - /session/token (OAuth2 token exchange)             │  │
│  │ - /instruments (instrument master)                   │  │
│  │ - /quote/ohlc (OHLC data)                            │  │
│  │ - /quote/ltp (last traded price)                     │  │
│  │ - /orders (order placement)                          │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ WebSocket (KiteTicker)                               │  │
│  │ - Streaming tick data (NIFTY50, NIFTYBANK)           │  │
│  │ - Full mode: OHLC + Greeks + OI                      │  │
│  │ - Subscription: Tokens 256265 (NIFTY50), 260105      │  │
│  │   (NIFTYBANK)                                        │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Authentication Flow

```
1. User clicks GET /zerodha/login
   ├─→ ZerodhaAuthController.login()
   ├─→ ZerodhaAuthService.generateLoginUrl()
   └─→ Redirect to Kite login page

2. User logs in to Kite
   └─→ Kite redirects to /zerodha/callback?request_token=xxx

3. ZerodhaAuthController.callback(request_token)
   ├─→ ZerodhaAuthService.generateSession(request_token)
   ├─→ Exchange request_token for access_token via Kite API
   ├─→ Store access_token in Redis under "zerodha:access_token"
   ├─→ Set TTL: 8 hours (zerodha.kite.access-token-ttl-hours)
   └─→ Return success response

4. TokenAvailabilityTracker monitors token expiry
   ├─→ Checks Redis every 5 minutes (zerodha.token-check-interval-ms)
   ├─→ If expired, sends Telegram re-auth alert
   └─→ Schedulers check TokenAvailabilityTracker before Kite API calls
```

### Token Management

**TokenAvailabilityTracker:**
- Tracks token availability in Redis
- Checks token expiry every 5 minutes
- Sends Telegram alert if token expired
- All schedulers check `tokenTracker.isTokenAvailable()` before Kite API calls

**Token Storage:**
- Key: `zerodha:access_token`
- Value: Access token string
- TTL: 8 hours (configurable via `zerodha.kite.access-token-ttl-hours`)

### WebSocket Ticker

**ZerodhaTickerService:**
- Connects to KiteTicker WebSocket using access token from Redis
- Subscribes to NIFTY50 (token 256265) and NIFTYBANK (token 260105) in FULL mode
- On each tick:
  - Logs tick data
  - Stores latest price in Redis under `price:{symbol}` with 60-second TTL
  - Emits to reactive Flux for downstream consumers

**TickerConnectionManager:**
- Manages WebSocket connection lifecycle
- Health check every 30 seconds
- Auto-reconnect with exponential backoff on disconnect
- Logs reconnection attempts (max 10 before ERROR)

### Option Chain Fetching

**OptionChainService:**
- Fetches option chain from Kite API for given symbol and expiry
- Caches in Redis with 5-minute TTL
- Returns: spot price, expiry date, list of strikes with call/put prices, IVs, OI

**OptionChainSnapshotScheduler:**
- Captures full option chain snapshots every 5 minutes
- Stores in PostgreSQL table `option_chain_snapshots` for historical analysis

### Order Execution

**OrderExecutionService:**
- Places orders via Kite API (currently paper trading only)
- Tracks order status
- Handles order rejections and errors

---

## Database Architecture

### Schema Design

**Database:** PostgreSQL 14+
**Schema:** `trade_mesh`
**Migrations:** Liquibase (13 changesets)

### Core Tables

#### `paper_trades`
```sql
CREATE TABLE paper_trades (
    id UUID PRIMARY KEY,
    trade_date DATE NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    strategy_name VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,  -- OPEN, CLOSED, CANCELLED
    legs JSONB NOT NULL,  -- List<TradeLeg>
    entry_credit NUMERIC(10,2) NOT NULL,  -- Points, not rupees
    current_value NUMERIC(10,2),  -- Points
    realized_pnl NUMERIC(10,2),  -- Points
    unrealized_pnl NUMERIC(10,2),  -- Points
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    is_new BOOLEAN DEFAULT FALSE
);
```

**Key Points:**
- All P&L fields stored in POINTS, not rupees
- Rupee conversion happens only at display/alert time
- `legs` is JSONB array of TradeLeg objects
- `is_new` field for R2DBC Persistable pattern

#### `instruments`
```sql
CREATE TABLE instruments (
    instrument_token BIGINT PRIMARY KEY,
    tradingsymbol VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100),
    strike NUMERIC(10,2),
    expiry DATE,
    lot_size INTEGER,
    instrument_type VARCHAR(20),
    segment VARCHAR(20)
);
```

#### `strategy_execution_logs`
```sql
CREATE TABLE strategy_execution_logs (
    id UUID PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    strategy_name VARCHAR(50) NOT NULL,
    action VARCHAR(20) NOT NULL,  -- ENTRY, EXIT, ADJUSTMENT
    reason VARCHAR(500),
    executed_at TIMESTAMP NOT NULL,
    is_new BOOLEAN DEFAULT FALSE
);
```

#### `nifty_daily_prices`
```sql
CREATE TABLE nifty_daily_prices (
    id UUID PRIMARY KEY,
    trade_date DATE NOT NULL UNIQUE,
    open NUMERIC(10,2) NOT NULL,
    high NUMERIC(10,2) NOT NULL,
    low NUMERIC(10,2) NOT NULL,
    close NUMERIC(10,2) NOT NULL,
    volume BIGINT NOT NULL,
    is_new BOOLEAN DEFAULT FALSE
);
```

#### `vix_history`
```sql
CREATE TABLE vix_history (
    id BIGSERIAL PRIMARY KEY,
    vix_value NUMERIC(10,2) NOT NULL,
    vix_level VARCHAR(20),  -- LOW, MEDIUM, HIGH, EXTREME
    recorded_at TIMESTAMP NOT NULL
);
```

#### `iv_history`
```sql
CREATE TABLE iv_history (
    id UUID PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    trade_date DATE NOT NULL,
    atm_iv NUMERIC(10,4) NOT NULL,
    call_iv NUMERIC(10,4),
    put_iv NUMERIC(10,4),
    spot_price NUMERIC(10,2),
    expiry_date DATE,
    is_new BOOLEAN DEFAULT FALSE,
    UNIQUE(symbol, trade_date)
);
```

#### `oi_snapshots`
```sql
CREATE TABLE oi_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    max_call_strike NUMERIC(10,2),
    max_put_strike NUMERIC(10,2),
    market_bias VARCHAR(20),
    data JSONB,
    recorded_at TIMESTAMP NOT NULL
);
```

#### `max_pain_history`
```sql
CREATE TABLE max_pain_history (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    expiry_date DATE NOT NULL,
    max_pain_strike NUMERIC(10,2) NOT NULL,
    spot_price NUMERIC(10,2),
    distance_from_spot NUMERIC(10,2),
    calculated_at TIMESTAMP NOT NULL
);
```

#### `option_chain_snapshots`
```sql
CREATE TABLE option_chain_snapshots (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    expiry_date DATE NOT NULL,
    spot_price NUMERIC(10,2),
    data JSONB NOT NULL,  -- Full option chain
    created_at TIMESTAMP NOT NULL
);
```

#### `vix_alerts`
```sql
CREATE TABLE vix_alerts (
    id BIGSERIAL PRIMARY KEY,
    vix_value NUMERIC(10,2) NOT NULL,
    vix_level VARCHAR(20),
    alert_message VARCHAR(500),
    telegram_sent BOOLEAN DEFAULT FALSE,
    triggered_at TIMESTAMP NOT NULL
);
```

### R2DBC Configuration

**R2dbcConfig:**
- Registers custom converters for JSONB fields
- `PaperTradeLegsWritingConverter`: List<TradeLeg> → JSONB
- `PaperTradeLegsReadingConverter`: JSONB → List<TradeLeg>
- Uses `R2dbcCustomConversions.of(PostgresDialect.INSTANCE, converters)`

**Connection Pool:**
```yaml
spring:
  r2dbc:
    pool:
      enabled: true
      initial-size: 2
      max-size: 10
      max-idle-time: 30m
```

### Entity Pattern (R2DBC)

All entities implement `Persistable<UUID>`:

```java
@Data
@Table(name = "paper_trades")
public class PaperTrade implements Persistable<UUID> {
    @Id
    private UUID id;
    
    @Transient
    @JsonIgnore
    private boolean isNew = false;  // Default: false (not new)
    
    // ... other fields with @Column annotations
    
    @Override
    public boolean isNew() {
        return isNew;
    }
    
    // Factory method (not @PostLoad — R2DBC doesn't support it)
    public static PaperTrade createNew() {
        PaperTrade trade = new PaperTrade();
        trade.isNew = true;
        trade.id = UUID.randomUUID();
        return trade;
    }
}
```

---

## Scheduler Architecture

### Scheduler Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    SCHEDULED JOBS                           │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ TradingScheduler                                     │  │
│  │ ├─→ dailySafetyNetCheck (9:20 AM)                    │  │
│  │ ├─→ scheduledDailyEntry (9:30 AM)                   │  │
│  │ ├─→ scheduledDailyExit (3:15 PM)                    │  │
│  │ └─→ scheduledDailyReport (3:45 PM)                  │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ IntelligenceScheduler                                │  │
│  │ └─→ refreshMarketIntelligence (every 5 min)          │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ MarkToMarketScheduler                                │  │
│  │ └─→ markToMarketScheduled (every 60 sec)             │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ VixScheduler                                         │  │
│  │ ├─→ fetchVixData (every 5 min)                       │  │
│  │ └─→ persistVixData (every 15 min)                    │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ IvHistoryScheduler                                   │  │
│  │ └─→ captureAtmIv (9:25 AM)                           │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ MaxPainScheduler                                     │  │
│  │ └─→ captureMaxPainSnapshots (every 30 min)           │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ OIAnalysisScheduler                                  │  │
│  │ └─→ captureOIAnalysisSnapshots (every 15 min)        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ OptionChainSnapshotScheduler                         │  │
│  │ └─→ captureOptionChainSnapshots (every 5 min)        │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ TokenRefreshScheduler                                │  │
│  │ ├─→ checkTokenHealth (8:00 AM)                       │  │
│  │ └─→ preMarketTokenCheck (9:00 AM)                    │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ TickerConnectionManager                              │  │
│  │ └─→ healthCheck (every 30 sec)                       │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Detailed Scheduler Specifications

| Scheduler | Cron | Time (IST) | Frequency | Action |
|---|---|---|---|---|
| **TradingScheduler.dailySafetyNetCheck** | `0 20 9 * * MON-FRI` | 9:20 AM | Weekdays | DTE-exit safety net check for IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD |
| **TradingScheduler.scheduledDailyEntry** | `0 30 9 * * MON-FRI` | 9:30 AM | Weekdays | Strategy evaluation and trade entry for NIFTY50 |
| **TradingScheduler.scheduledDailyExit** | `0 15 15 * * MON-FRI` | 3:15 PM | Weekdays | Force close EXPIRY_DAY_STRANGLE positions only |
| **TradingScheduler.scheduledDailyReport** | `0 45 15 * * MON-FRI` | 3:45 PM | Weekdays | Generate daily P&L report and send via Telegram |
| **IntelligenceScheduler.refreshMarketIntelligence** | `0 */5 9-15 ? * MON-FRI` | Every 5 min | Market hours | Refresh market intelligence for NIFTY50 and NIFTYBANK |
| **MarkToMarketScheduler.markToMarketScheduled** | fixedRate: 60000ms | Every 60 sec | Market hours | Mark open trades to market, check exits |
| **VixScheduler.fetchVixData** | `0 */5 9-15 ? * MON-FRI` | Every 5 min | Market hours | Fetch VIX from Kite API |
| **VixScheduler.persistVixData** | `0 */15 9-15 ? * MON-FRI` | Every 15 min | Market hours | Persist VIX snapshot to database |
| **IvHistoryScheduler.captureAtmIv** | `0 25 9 * * MON-FRI` | 9:25 AM | Weekdays | Capture ATM IV for current expiry |
| **MaxPainScheduler.captureMaxPainSnapshots** | `0 */30 9-15 ? * MON-FRI` | Every 30 min | Market hours | Capture max pain snapshots |
| **OIAnalysisScheduler.captureOIAnalysisSnapshots** | `0 */15 9-15 ? * MON-FRI` | Every 15 min | Market hours | Capture OI analysis snapshots |
| **OptionChainSnapshotScheduler.captureOptionChainSnapshots** | fixedRate: 300000ms | Every 5 min | Market hours | Capture full option chain snapshots |
| **TokenRefreshScheduler.checkTokenHealth** | `0 0 8 * * *` | 8:00 AM | Daily | Check token health |
| **TokenRefreshScheduler.preMarketTokenCheck** | `0 0 9 * * MON-FRI` | 9:00 AM | Weekdays | Pre-market token check |
| **TickerConnectionManager.healthCheck** | fixedDelay: 30000ms | Every 30 sec | Always | WebSocket health check |

### Scheduler Coordination

**Key Principles:**
1. **Token Guard:** All schedulers check `TokenAvailabilityTracker.isTokenAvailable()` before Kite API calls
2. **Market Hours Check:** Most schedulers check `MarketCalendarService.isMarketOpen()` before proceeding
3. **Error Handling:** All schedulers use `.onErrorResume()` to suppress error propagation
4. **Subscription:** All schedulers call `.subscribe()` to trigger reactive chains
5. **No Blocking:** No `.block()` anywhere; all operations are non-blocking

---

## Configuration Architecture

### application.yml Structure

```yaml
# 1. Server
server:
  port: 8080

# 2. Spring Core (R2DBC, DataSource, Liquibase, Redis)
spring:
  application:
    name: trade-mesh
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/trade_mesh_db?schema=trade_mesh
    username: postgres
    password: postgres
    pool:
      enabled: true
      initial-size: 2
      max-size: 10
      max-idle-time: 30m
  datasource:
    url: jdbc:postgresql://localhost:5432/trade_mesh_db
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.yaml
    default-schema: trade_mesh
  data:
    redis:
      host: localhost
      port: 6379
      password: ""
      timeout: 30s
      lettuce:
        pool:
          max-active: 8
          max-idle: 8

# 3. External Services (Zerodha, Telegram)
zerodha:
  kite:
    api-key: ${ZERODHA_API_KEY}
    api-secret: ${ZERODHA_API_SECRET}
    redirect-url: http://localhost:8080/zerodha/callback
    base-url: https://api.kite.trade
    login-url: https://kite.zerodha.com/connect/login
    access-token-ttl-hours: 8
    instruments-buffer-size-mb: 20MB
  token-check-interval-ms: 300000
  ticker:
    instruments:
      nifty50-token: 256265
      niftybank-token: 260105
    price-cache-ttl-seconds: 60
    health-check-interval-ms: 30000
    max-reconnect-alerts: 10

telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  chat-id: ${TELEGRAM_CHAT_ID}

# 4. API Docs
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    enabled: true

# 5. Market Intelligence
intelligence:
  market-data:
    fetch-interval-minutes: "0 */5 9-15 ? * MON-FRI"
    cache-ttl-minutes: 10
  vix:
    fetch-interval-cron: "0 */5 9-15 ? * MON-FRI"
    persist-interval-cron: "0 */15 9-15 ? * MON-FRI"
    alert-threshold: 20.0
  maxpain:
    persist-interval-minutes: "0 */30 9-15 ? * MON-FRI"
    cache-ttl-minutes: 15
  oi:
    persist-interval-minutes: "0 */15 9-15 ? * MON-FRI"

# 6. Trading Engine
trading:
  mode: paper
  expiry-day: THU
  symbols:
    - NIFTY50
    - NIFTYBANK
  mark-to-market-interval-ms: 60000
  lot-sizes:
    NIFTY50: 65
    NIFTYBANK: 30
  account-capital: 100000
  risk-percent-per-trade: 2.0
  paper:
    enabled: true

# 7. Strategies
trading-strategies:
  items:
  - name: EXPIRY_DAY_STRANGLE
    enabled: true
    allowed-days: [TUESDAY]
    applicable-conditions: [EXPIRY_DAY]
    max-vix: 18.0
    min-vix: 10.0
    latest-entry-time: "12:30"
    min-credit: 20.0
    max-lots: 1
    stop-loss-percent: 100.0
    target-profit-percent: 50.0
    extra:
      min-distance-atr-multiplier: 0.4
  
  - name: IRON_CONDOR
    enabled: true
    allowed-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
    applicable-conditions: [SIDEWAYS]
    max-vix: 20.0
    min-vix: 12.0
    latest-entry-time: "13:00"
    min-credit: 30.0
    max-lots: 1
    stop-loss-percent: 100.0
    target-profit-percent: 50.0
    extra:
      target-delta: 0.18
      wing-width: 200
      min-score: 51
      dte-min: 3
      dte-max: 4
      dte-exit-threshold: 2
      vix-panic-threshold: 25.0
  
  - name: BULL_PUT_SPREAD
    enabled: true
    allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
    applicable-conditions: [TRENDING_BULLISH]
    max-vix: 20.0
    min-vix: 10.0
    latest-entry-time: "13:00"
    min-credit: 20.0
    max-lots: 1
    stop-loss-percent: 100.0
    target-profit-percent: 50.0
    extra:
      dte-min: 2
      dte-max: 4
      dte-exit-threshold: 1
      target-delta: 0.30
      wing-width: 100
      vix-panic-threshold: 25.0
  
  - name: BEAR_CALL_SPREAD
    enabled: true
    allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
    applicable-conditions: [TRENDING_BEARISH]
    max-vix: 20.0
    min-vix: 10.0
    latest-entry-time: "13:00"
    min-credit: 20.0
    max-lots: 1
    stop-loss-percent: 100.0
    target-profit-percent: 50.0
    extra:
      dte-min: 2
      dte-max: 4
      dte-exit-threshold: 1
      target-delta: 0.30
      wing-width: 100
      vix-panic-threshold: 25.0

# 8. Risk Management
risk:
  max-position-size-per-symbol: 20
  daily-loss-limit-percent: 2.0
  max-sector-exposure: 2000000
  min-margin-buffer-percent: 10
  max-concurrent-open-positions: 3
  position-sizing:
    risk-percent: 1.0
    max-quantity: 10

# 9. Logging
logging:
  level:
    root: INFO
    com.trademesh: DEBUG
    org.springframework.r2dbc: DEBUG
    reactor.netty: INFO
    io.netty: INFO
    liquibase: INFO
```

### Strategy Configuration Pattern

**Shared Fields (all strategies):**
- `name`: Strategy name (IRON_CONDOR, BULL_PUT_SPREAD, etc.)
- `enabled`: Whether strategy is active
- `allowed-days`: Days of week strategy can trade
- `applicable-conditions`: Market conditions that trigger strategy
- `max-vix`: Maximum VIX for entry
- `min-vix`: Minimum VIX for entry
- `latest-entry-time`: Latest time to enter (HH:mm)
- `min-credit`: Minimum premium to justify trade
- `max-lots`: Maximum lots per trade
- `stop-loss-percent`: Exit if loss reaches this % of credit
- `target-profit-percent`: Exit if profit reaches this % of credit

**Strategy-Specific Fields (in `extra` map):**
- IRON_CONDOR: `target-delta`, `wing-width`, `min-score`, `dte-min`, `dte-max`, `dte-exit-threshold`, `vix-panic-threshold`, `atr-calm-threshold`, `atr-high-threshold`, `calm-target-multiplier`, `calm-sl-multiplier`, `high-target-multiplier`, `high-sl-multiplier`
- BULL_PUT_SPREAD: `dte-min`, `dte-max`, `dte-exit-threshold`, `target-delta`, `wing-width`, `vix-panic-threshold`
- BEAR_CALL_SPREAD: `dte-min`, `dte-max`, `dte-exit-threshold`, `target-delta`, `wing-width`, `vix-panic-threshold`
- EXPIRY_DAY_STRANGLE: `min-distance-atr-multiplier`

**Access Pattern:**
```java
StrategyProperties config = strategiesConfig.getStrategy("IRON_CONDOR");
double targetDelta = config.getExtraDouble("target-delta", 0.18);
int wingWidth = config.getExtraInt("wing-width", 200);
```

---

## API Architecture

### REST Endpoints

#### Authentication

| Method | Path | Purpose |
|---|---|---|
| GET | `/zerodha/login` | Redirect to Kite Connect OAuth2 login |
| GET | `/zerodha/callback?request_token=xxx` | OAuth2 callback; exchanges token and stores in Redis |
| GET | `/zerodha/token/status` | Check if Zerodha token is valid and available |

#### Market Data

| Method | Path | Purpose |
|---|---|---|
| GET | `/market/price/{symbol}` | Get latest cached price for symbol |
| GET | `/market/stream/{symbol}` | Server-Sent Events stream of live prices |
| GET | `/market/status` | WebSocket connection status |

#### Market Intelligence

| Method | Path | Purpose |
|---|---|---|
| GET | `/intelligence/{symbol}` | Complete market intelligence (VIX, OI, max pain, metrics, condition) |
| GET | `/intelligence/{symbol}/condition` | Market condition only (SIDEWAYS, TRENDING_BULLISH, etc.) |
| GET | `/intelligence/{symbol}/summary` | One-line summary of market intelligence |
| GET | `/intelligence/metrics/{symbol}` | Market metrics (HV20, ATR14, IVR, IV/HV, PCR) |
| GET | `/intelligence/vix` | Current VIX data (value, level, change, safe-to-trade) |
| GET | `/intelligence/maxpain` | Max pain data for current expiry |
| GET | `/intelligence/oi` | Open interest analysis (OI walls, market bias) |

#### Strategy

| Method | Path | Purpose |
|---|---|---|
| GET | `/strategy/evaluate/{symbol}` | Evaluate all strategies for symbol; returns best executable signal |
| GET | `/strategy/applicable/{symbol}` | List strategies applicable to current market condition |
| GET | `/strategy/status` | Status of all strategies (enabled/disabled) |

#### Trading

| Method | Path | Purpose |
|---|---|---|
| POST | `/trading/expiry-day/trigger` | Manually trigger expiry day entry (testing only) |
| POST | `/trading/run/{symbol}` | Manually trigger trading cycle for symbol; always returns JSON response with executable flag and skipReason |
| GET | `/trading/positions/open` | Get all currently open positions with live P&L refresh (read-only, no DB writes) |
| POST | `/trading/monitor` | Manually trigger position monitoring for all open positions |
| GET | `/trading/report/today` | Get today's trading summary (total trades, wins, losses, P&L) |

#### Paper Trading

| Method | Path | Purpose |
|---|---|---|
| GET | `/paper/trades/open` | Get all open paper trades |
| GET | `/paper/trades/history` | Get all closed paper trades |
| GET | `/paper/trades/{id}` | Get specific paper trade by ID |
| GET | `/paper/trades/symbol/{symbol}` | Get trades for specific symbol |
| GET | `/paper/trades/strategy/{strategyName}` | Get trades for specific strategy |
| GET | `/paper/trades/pnl/weekly` | Get weekly P&L summary |
| GET | `/paper/trades/stats` | Get overall trading statistics |
| POST | `/paper/trades/{id}/close` | Manually close a paper trade |

#### Admin (Internal Use)

| Method | Path | Purpose |
|---|---|---|
| POST | `/admin/bootstrap/historical-prices?from=YYYY-MM-DD&to=YYYY-MM-DD` | Bootstrap historical NIFTY50 OHLC from Kite API (idempotent) |
| GET | `/admin/test/atm-iv?symbol=NIFTY50` | Test ATM IV calculation (read-only, no DB save) |
| GET | `/admin/test/atm-iv-save?symbol=NIFTY50` | Test ATM IV calculation and save to DB |
| GET | `/admin/test/iron-condor-evaluate` | Test Iron Condor strategy evaluation |
| GET | `/admin/test/bull-put-spread-evaluate` | Test Bull Put Spread strategy evaluation |
| GET | `/admin/test/bear-call-spread-evaluate` | Test Bear Call Spread strategy evaluation |
| GET | `/admin/test/expiry-day-evaluate` | Test Expiry Day Strangle strategy evaluation |

### Response Format

**Success Response:**
```json
{
  "success": true,
  "data": { ... },
  "timestamp": "2026-07-14T20:56:00Z"
}
```

**Error Response:**
```json
{
  "success": false,
  "error": "Error message",
  "details": [ ... ],
  "timestamp": "2026-07-14T20:56:00Z"
}
```

---

## Reactive Flow (WebFlux)

### Core Reactive Patterns

#### 1. Parallel Data Fetching with Mono.zip()

```java
// Fetch multiple data sources in parallel
return Mono.zip(
    vixMonitorService.fetchVix(),
    optionChainService.fetchOptionChain(symbol),
    oiAnalyserService.analyzeOI(symbol),
    maxPainCalculatorService.calculateMaxPain(symbol)
)
.map(tuple -> {
    VixData vix = tuple.getT1();
    OptionChain chain = tuple.getT2();
    OIAnalysis oi = tuple.getT3();
    MaxPainResult maxPain = tuple.getT4();
    // Combine into MarketIntelligence
    return new MarketIntelligence(vix, chain, oi, maxPain, ...);
});
```

**Key Point:** All four operations run in parallel; Mono.zip() waits for all to complete.

#### 2. Dependent Reactive Calls with flatMap()

```java
// Chain dependent operations
return marketIntelligenceService.generateFreshIntelligence(symbol)
    .flatMap(intelligence -> {
        // Use intelligence to evaluate strategy
        return strategySelector.evaluateSelectedStrategy(intelligence);
    })
    .flatMap(result -> {
        // Use result to execute trade
        if (result.getIsExecutable()) {
            return paperTradingEngine.executeTrade(result);
        }
        return Mono.empty();
    });
```

**Key Point:** Each operation waits for the previous one to complete; data flows through the chain.

#### 3. Graceful Empty Handling with switchIfEmpty()

```java
// Try to get from cache; if not found, generate fresh
return redisTemplate.opsForValue().get(redisKey)
    .flatMap(cached -> {
        try {
            return Mono.just(objectMapper.readValue(cached, MarketIntelligence.class));
        } catch (Exception e) {
            return performGeneration(symbol);
        }
    })
    .switchIfEmpty(performGeneration(symbol));  // If cache miss, generate
```

**Key Point:** `switchIfEmpty()` handles the case where the Mono is empty (no value emitted).

#### 4. Error Handling with onErrorResume()

```java
// Single error boundary per reactive chain
return marketIntelligenceService.generateFreshIntelligence(symbol)
    .flatMap(intelligence -> strategySelector.evaluateSelectedStrategy(intelligence))
    .doOnError(e -> log.error("Error in trading cycle: {}", e.getMessage(), e))
    .onErrorResume(e -> {
        // Suppress error propagation to prevent ErrorCallbackNotImplemented
        log.debug("Trading cycle failed, returning empty");
        return Mono.empty();
    });
```

**Key Point:** `onErrorResume()` is the single error boundary; it prevents unhandled exceptions.

#### 5. Subscription and Triggering

```java
// Schedulers must call .subscribe() to trigger the reactive chain
@Scheduled(cron = "0 30 9 * * MON-FRI")
public void scheduledDailyEntry() {
    tradingOrchestrator.runTradingCycle("NIFTY50")
        .doOnSuccess(result -> log.info("Trading cycle completed: {}", result))
        .doOnError(e -> log.error("Trading cycle failed: {}", e.getMessage(), e))
        .subscribe(
            ignored -> {},  // onNext
            e -> log.error("Unhandled error: {}", e.getMessage(), e)  // onError
        );
}
```

**Key Point:** Without `.subscribe()`, the Mono is never executed (lazy evaluation).

### Reactive Composition Example

```
TradingOrchestrator.runTradingCycle("NIFTY50")
│
├─→ marketIntelligenceService.generateFreshIntelligence("NIFTY50")
│   │
│   ├─→ Mono.zip(
│   │   vixMonitorService.fetchVix(),
│   │   optionChainService.fetchOptionChain("NIFTY50"),
│   │   oiAnalyserService.analyzeOI("NIFTY50"),
│   │   maxPainCalculatorService.calculateMaxPain("NIFTY50")
│   │ )
│   │
│   └─→ MarketMetricsService.calculateMetrics()
│
├─→ strategySelector.evaluateSelectedStrategy(intelligence)
│   │
│   └─→ IronCondorStrategy.evaluate(intelligence)
│       │
│       ├─→ optionChainService.fetchOptionChain()
│       ├─→ marketMetricsService.calculateMetrics()
│       └─→ Return StrategyResult
│
├─→ paperTradingEngine.executeTrade(result)
│   │
│   ├─→ paperTradeRepository.save(trade)
│   └─→ telegramNotificationBot.sendAlert()
│
└─→ Return StrategyResult
```

### Non-Blocking I/O Benefits

1. **High Concurrency:** Single thread can handle thousands of concurrent requests
2. **Efficient Resource Usage:** No thread blocking; threads are released immediately
3. **Scalability:** Can handle more requests with fewer threads
4. **Responsiveness:** No thread starvation; all requests get fair CPU time

### Important Reactive Rules

1. **No `.block()` anywhere** — Ever. Blocks the entire thread pool.
2. **All methods return Mono<T> or Flux<T>** — Never return raw values.
3. **Use Mono.zip() for parallel** — Fetches multiple sources concurrently.
4. **Use flatMap() for dependent** — Chains operations that depend on previous results.
5. **Use switchIfEmpty()** — Handles empty Monos gracefully.
6. **Use onErrorResume()** — Single error boundary per chain.
7. **Wrap Optional<T> before Mono.zip()** — Empty Mono inside zip causes entire zip to never emit.
8. **Call .subscribe()** — Triggers the reactive chain (lazy evaluation).

---

## Design Patterns Used

### 1. Service Layer Pattern

**Purpose:** Encapsulate business logic, separate concerns

**Implementation:**
- Controllers call services (never repositories directly)
- Services call repositories
- Services expose two method variants:
  - **Read method:** Fetches/calculates, no save (REST-facing)
  - **Save method:** Calls read method, then persists (scheduler-facing)

**Example:**
```java
@Service
public class PaperTradeService {
    // READ method (REST-facing)
    public Mono<PaperTrade> getTradeById(UUID id) {
        return paperTradeRepository.findById(id);
    }
    
    // SAVE method (scheduler-facing)
    public Mono<PaperTrade> saveNewTrade(PaperTrade trade) {
        return paperTradeRepository.save(trade);
    }
}
```

### 2. Repository Pattern

**Purpose:** Abstract data access, enable testing

**Implementation:**
- R2DBC repositories extend `ReactiveCrudRepository<T, ID>`
- All database operations go through repositories
- Repositories return `Mono<T>` or `Flux<T>`

**Example:**
```java
@Repository
public interface PaperTradeRepository extends ReactiveCrudRepository<PaperTrade, UUID> {
    Mono<Boolean> existsByStrategyNameAndStatus(String strategyName, TradeStatus status);
    Flux<PaperTrade> findByStatus(TradeStatus status);
}
```

### 3. Strategy Pattern

**Purpose:** Encapsulate different trading strategies, enable runtime selection

**Implementation:**
- All strategies implement `TradingStrategy` interface
- `StrategySelector` picks applicable strategy based on market condition
- Each strategy has its own evaluation logic

**Example:**
```java
public interface TradingStrategy {
    String getName();
    boolean isApplicable(MarketCondition condition);
    Mono<StrategyResult> evaluate(MarketIntelligence intelligence);
}

@Service
public class IronCondorStrategy implements TradingStrategy {
    @Override
    public String getName() { return "IRON_CONDOR"; }
    
    @Override
    public boolean isApplicable(MarketCondition condition) {
        return condition == MarketCondition.SIDEWAYS;
    }
    
    @Override
    public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
        // Evaluation logic
    }
}
```

### 4. Scheduler Pattern

**Purpose:** Automate recurring tasks at scheduled times

**Implementation:**
- `@Scheduled` annotation on methods
- Cron expressions for time-based scheduling
- Fixed rate/delay for interval-based scheduling
- All schedulers have exclusive DB write access

**Example:**
```java
@Service
public class TradingScheduler {
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyEntry() {
        tradingOrchestrator.runTradingCycle("NIFTY50")
            .subscribe();
    }
}
```

### 5. Orchestrator Pattern

**Purpose:** Coordinate complex workflows across multiple services

**Implementation:**
- `TradingOrchestrator` coordinates trading cycles
- `StrategyOrchestrator` coordinates strategy evaluation
- Orchestrators compose multiple services into workflows

**Example:**
```java
@Service
public class TradingOrchestrator {
    public Mono<StrategyResult> runTradingCycle(String symbol) {
        return marketIntelligenceService.generateFreshIntelligence(symbol)
            .flatMap(intelligence -> strategySelector.evaluateSelectedStrategy(intelligence))
            .flatMap(result -> {
                if (result.getIsExecutable()) {
                    return paperTradingEngine.executeTrade(result);
                }
                return Mono.empty();
            });
    }
}
```

### 6. Factory Pattern

**Purpose:** Create entities with proper initialization

**Implementation:**
- Static factory methods on entities (e.g., `PaperTrade.createNew()`)
- Sets `isNew = true` for new entities
- Generates UUID for new entities

**Example:**
```java
public class PaperTrade implements Persistable<UUID> {
    @Transient
    private boolean isNew = false;
    
    public static PaperTrade createNew() {
        PaperTrade trade = new PaperTrade();
        trade.isNew = true;
        trade.id = UUID.randomUUID();
        return trade;
    }
}
```

### 7. Configuration Pattern

**Purpose:** Externalize configuration, enable environment-specific settings

**Implementation:**
- `application.yml` for all configuration
- `@ConfigurationProperties` for type-safe config binding
- Environment variables for sensitive data (API keys, tokens)

**Example:**
```yaml
trading-strategies:
  items:
  - name: IRON_CONDOR
    enabled: true
    max-vix: 20.0
    extra:
      target-delta: 0.18
```

```java
@ConfigurationProperties(prefix = "trading-strategies")
public class StrategiesConfig {
    private List<StrategyProperties> items;
    
    public StrategyProperties getStrategy(String name) {
        return items.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
}
```

### 8. Caching Pattern

**Purpose:** Reduce API calls, improve performance

**Implementation:**
- Redis for distributed caching
- TTL-based expiration
- Cache-aside pattern (check cache, if miss, fetch and cache)

**Example:**
```java
public Mono<MarketIntelligence> generateIntelligence(String symbol) {
    String redisKey = "intelligence:" + symbol;
    
    return redisTemplate.opsForValue().get(redisKey)
        .flatMap(cached -> Mono.just(objectMapper.readValue(cached, MarketIntelligence.class)))
        .switchIfEmpty(performGeneration(symbol))
        .flatMap(intelligence -> cacheIntelligence(symbol, intelligence));
}
```

### 9. Observer Pattern

**Purpose:** Notify multiple subscribers of events

**Implementation:**
- Telegram alerts for trading events
- Reactive Flux for tick data streaming
- Scheduler-based event generation

**Example:**
```java
@Service
public class TelegramNotificationBot {
    public Mono<Void> sendAlert(Alert alert) {
        return telegramClient.sendMessage(alert.getMessage());
    }
}
```

### 10. Template Method Pattern

**Purpose:** Define algorithm skeleton, let subclasses fill in details

**Implementation:**
- `TradingStrategy` interface defines evaluation template
- Each strategy implements its own evaluation logic
- `StrategySelector` calls the template method

---

## Extension Points

### Adding a New Strategy

**Steps:**

1. **Create strategy class** in `com.trademesh.strategy.{strategyname}`:
```java
@Service
public class MyStrategy implements TradingStrategy {
    @Override
    public String getName() { return "MY_STRATEGY"; }
    
    @Override
    public boolean isApplicable(MarketCondition condition) {
        return condition == MarketCondition.SIDEWAYS;
    }
    
    @Override
    public Mono<StrategyResult> evaluate(MarketIntelligence intelligence) {
        // Evaluation logic
    }
}
```

2. **Add configuration** to `application.yml`:
```yaml
trading-strategies:
  items:
  - name: MY_STRATEGY
    enabled: true
    allowed-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
    applicable-conditions: [SIDEWAYS]
    max-vix: 20.0
    min-vix: 12.0
    latest-entry-time: "13:00"
    min-credit: 30.0
    max-lots: 1
    stop-loss-percent: 100.0
    target-profit-percent: 50.0
    extra:
      # Strategy-specific parameters
```

3. **Register in StrategySelector** (if not auto-discovered):
```java
@Service
public class StrategySelector {
    private final MyStrategy myStrategy;
    
    public Mono<StrategyResult> evaluateSelectedStrategy(MarketIntelligence intelligence) {
        // Add logic to select MyStrategy based on condition
    }
}
```

### Adding a New Scheduler

**Steps:**

1. **Create scheduler class**:
```java
@Service
public class MyScheduler {
    @Scheduled(cron = "0 */5 9-15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void myScheduledTask() {
        // Check token availability
        if (!tokenTracker.isTokenAvailable()) {
            log.warn("Token unavailable, skipping task");
            return;
        }
        
        // Perform task
        myService.doSomething()
            .doOnError(e -> log.error("Error: {}", e.getMessage()))
            .onErrorResume(e -> Mono.empty())
            .subscribe();
    }
}
```

2. **Register in Spring** (auto-discovered via `@Service` or `@Component`)

### Adding a New Market Intelligence Source

**Steps:**

1. **Create service** to fetch data:
```java
@Service
public class MyIntelligenceService {
    public Mono<MyData> fetchData(String symbol) {
        // Fetch from external API
    }
}
```

2. **Integrate into MarketIntelligenceService**:
```java
public Mono<MarketIntelligence> generateFreshIntelligence(String symbol) {
    return Mono.zip(
        vixMonitorService.fetchVix(),
        optionChainService.fetchOptionChain(symbol),
        oiAnalyserService.analyzeOI(symbol),
        maxPainCalculatorService.calculateMaxPain(symbol),
        myIntelligenceService.fetchData(symbol)  // Add here
    )
    .map(tuple -> {
        // Combine all data
    });
}
```

### Adding a New REST Endpoint

**Steps:**

1. **Create controller**:
```java
@RestController
@RequestMapping("/my-endpoint")
@RequiredArgsConstructor
public class MyController {
    private final MyService myService;
    
    @GetMapping("/{id}")
    public Mono<ResponseEntity<MyData>> getData(@PathVariable String id) {
        return myService.getData(id)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
```

2. **Keep it read-only** — No database writes from controllers

### Adding a New Database Table

**Steps:**

1. **Create Liquibase changelog** in `src/main/resources/db/changelog/changes/`:
```yaml
databaseChangeLog:
  - changeSet:
      id: 014-create-my-table
      author: developer
      changes:
        - createTable:
            tableName: my_table
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
              - column:
                  name: name
                  type: VARCHAR(100)
```

2. **Register in master changelog** (`db.changelog-master.yaml`):
```yaml
databaseChangeLog:
  - include:
      file: classpath:db/changelog/changes/014-create-my-table.yaml
```

3. **Create entity** implementing `Persistable<UUID>`:
```java
@Data
@Table(name = "my_table")
public class MyEntity implements Persistable<UUID> {
    @Id
    private UUID id;
    
    @Transient
    @JsonIgnore
    private boolean isNew = false;
    
    public static MyEntity createNew() {
        MyEntity entity = new MyEntity();
        entity.isNew = true;
        entity.id = UUID.randomUUID();
        return entity;
    }
}
```

4. **Create repository**:
```java
@Repository
public interface MyEntityRepository extends ReactiveCrudRepository<MyEntity, UUID> {
}
```

---

## Deployment Architecture

### Development Environment

```
Local Machine
├─ Spring Boot Application (port 8080)
├─ PostgreSQL (port 5432)
└─ Redis (port 6379)
```

**Setup:**
```bash
# Start PostgreSQL
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:14

# Start Redis
docker run -d -p 6379:6379 redis:6

# Run application
./gradlew bootRun
```

### Production Environment (Recommended)

```
┌─────────────────────────────────────────────────────────────┐
│                    Load Balancer / Reverse Proxy             │
│                    (Nginx / HAProxy)                         │
└────────────────────────┬────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ TradeMesh    │  │ TradeMesh    │  │ TradeMesh    │
│ Instance 1   │  │ Instance 2   │  │ Instance 3   │
│ (port 8080)  │  │ (port 8080)  │  │ (port 8080)  │
└──────────────┘  └──────────────┘  └──────────────┘
        │                │                │
        └────────────────┼────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
┌──────────────────────────────────────────────────────┐
│         PostgreSQL Cluster (Primary + Replicas)      │
│         - Connection pooling (PgBouncer)             │
│         - Automated backups                          │
│         - Point-in-time recovery                     │
└──────────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────────┐
│         Redis Cluster (High Availability)            │
│         - Sentinel for failover                      │
│         - Persistence (RDB + AOF)                    │
│         - Replication                                │
└──────────────────────────────────────────────────────┘
```

### Deployment Considerations

1. **Stateless Application** — Each instance can be independently scaled
2. **Shared Database** — All instances access same PostgreSQL
3. **Shared Cache** — All instances access same Redis
4. **Scheduler Coordination** — Use distributed locking (Redis) to prevent duplicate scheduled job execution
5. **Health Checks** — Implement `/health` endpoint for load balancer
6. **Monitoring** — Log aggregation, metrics collection, distributed tracing
7. **Secrets Management** — Use environment variables for API keys, tokens, credentials

### Docker Deployment

**Dockerfile:**
```dockerfile
FROM openjdk:21-slim
WORKDIR /app
COPY build/libs/trade-mesh-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**docker-compose.yml:**
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:14
    environment:
      POSTGRES_DB: trade_mesh_db
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:6
    ports:
      - "6379:6379"

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_HOST: postgres
      DB_PORT: 5432
      DB_NAME: trade_mesh_db
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      REDIS_HOST: redis
      REDIS_PORT: 6379
      ZERODHA_API_KEY: ${ZERODHA_API_KEY}
      ZERODHA_API_SECRET: ${ZERODHA_API_SECRET}
      TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}
      TELEGRAM_CHAT_ID: ${TELEGRAM_CHAT_ID}
    depends_on:
      - postgres
      - redis

volumes:
  postgres_data:
```

---

## Technology Stack

### Backend Framework
- **Spring Boot 3.3.0** — Application framework
- **Spring WebFlux** — Reactive web framework
- **Spring Data R2DBC** — Reactive database access
- **Spring Data Redis Reactive** — Reactive Redis client
- **Spring Scheduling** — Scheduled task execution

### Database & Caching
- **PostgreSQL 14+** — Primary data store
- **R2DBC PostgreSQL Driver** — Reactive database driver
- **Liquibase** — Schema versioning and migrations
- **Redis 6+** — Caching and token storage
- **Lettuce** — Reactive Redis client

### External APIs
- **Zerodha Kite Connect SDK** — Market data and order execution
- **Telegram Bot API** — Alert notifications

### Utilities
- **Lombok** — Boilerplate reduction
- **MapStruct** — Object mapping
- **Jackson** — JSON serialization/deserialization
- **SpringDoc OpenAPI** — API documentation (Swagger UI)

### Testing
- **JUnit 5** — Unit testing
- **Reactor Test** — Reactive testing
- **TestContainers** — Integration testing with PostgreSQL

### Build & Deployment
- **Gradle 8.x** — Build tool
- **Java 21** — Runtime

---

## Glossary

### Trading Terms

| Term | Definition |
|---|---|
| **ATR** | Average True Range — 14-day average of true range (volatility measure) |
| **ATR%** | ATR as percentage of spot price |
| **DTE** | Days To Expiry — number of trading days until option expiry |
| **Greeks** | Delta, Gamma, Theta, Vega — option price sensitivities |
| **HV20** | 20-day Historical Volatility — annualized standard deviation of daily returns |
| **IV** | Implied Volatility — market's expectation of future volatility |
| **IVR** | IV Rank — current IV as percentile of 52-week range (0-100) |
| **IV/HV Ratio** | Current IV divided by HV20 — indicates if IV is high or low relative to history |
| **Max Pain** | Strike price where maximum options expire worthless (theoretical) |
| **OI** | Open Interest — number of outstanding option contracts |
| **OI Wall** | Strike with unusually high open interest (potential support/resistance) |
| **PCR** | Put/Call Ratio — total put OI divided by total call OI |
| **Spot Price** | Current price of underlying index (NIFTY50, NIFTYBANK) |
| **Strike** | Exercise price of an option contract |
| **Theta** | Rate of time decay — how much option loses value per day |
| **Vega** | Sensitivity to volatility changes |
| **VIX** | Volatility Index — market's expectation of 30-day volatility |

### Strategy Terms

| Term | Definition |
|---|---|
| **Bear Call Spread** | Sell OTM call, buy further OTM call — profits if market stays below short call |
| **Bull Put Spread** | Sell OTM put, buy further OTM put — profits if market stays above short put |
| **Call** | Right to buy at strike price (CE) |
| **Expiry Day Strangle** | Sell call and put at OI walls on expiry day — profits from theta decay |
| **Iron Condor** | Sell call spread and put spread — profits if market stays between short strikes |
| **Leg** | Individual option contract in a multi-leg strategy |
| **Long** | Buy position (own the contract) |
| **OTM** | Out-of-The-Money — option with no intrinsic value |
| **Put** | Right to sell at strike price (PE) |
| **Short** | Sell position (obligated to deliver) |
| **Spread** | Multi-leg strategy combining long and short positions |
| **Strangle** | Sell call and put at different strikes — profits from theta decay |
| **Wing Width** | Distance between short and long strikes in a spread |

### Market Conditions

| Condition | Definition |
|---|---|
| **EXPIRY_DAY** | Tuesday (NSE expiry day) — low DTE, high theta decay |
| **HIGH_VOLATILITY** | VIX > 20 — market uncertainty, avoid entry |
| **NO_TRADE** | Unfavorable conditions — skip entry |
| **SIDEWAYS** | Low volatility, neutral bias — suitable for Iron Condor |
| **TRENDING_BEARISH** | High IV/HV, high PCR — suitable for Bear Call Spread |
| **TRENDING_BULLISH** | High IV/HV, low PCR — suitable for Bull Put Spread |

### System Terms

| Term | Definition |
|---|---|
| **Backpressure** | Mechanism to handle slow consumers in reactive streams |
| **Flux** | Reactive stream that emits 0 to N items |
| **Mono** | Reactive stream that emits 0 or 1 item |
| **Reactive** | Non-blocking, asynchronous programming model |
| **R2DBC** | Reactive Relational Database Connectivity |
| **Redis** | In-memory data store for caching and token storage |
| **WebFlux** | Spring's reactive web framework |
| **WebSocket** | Bidirectional communication protocol for real-time data |

---

## Summary

TradeMesh is a **fully-reactive, event-driven options trading platform** that automates multi-leg strategies on NSE through:

1. **Real-time market intelligence** — VIX, OI, max pain, metrics
2. **Intelligent strategy selection** — Based on market condition
3. **Automated trade execution** — Paper trading simulation
4. **Position monitoring** — Mark-to-market every 60 seconds
5. **Risk management** — Position limits, daily loss limits
6. **Real-time alerts** — Telegram notifications

The architecture emphasizes:
- **Non-blocking I/O** for high concurrency
- **Service-oriented design** with clear separation of concerns
- **Scheduled automation** for consistent trading execution
- **Persistent storage** with schema versioning
- **Caching layer** for performance optimization
- **External integrations** for market data and notifications

The system is designed to scale horizontally with stateless application instances coordinating through shared PostgreSQL and Redis infrastructure.

