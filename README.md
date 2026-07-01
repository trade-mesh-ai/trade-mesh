# TradeMesh — Automated Options Trading Platform

**Version:** 0.0.1-SNAPSHOT | **Last Updated:** July 1, 2026 | **Market:** NSE (India) | **Timezone:** IST (Asia/Kolkata)

TradeMesh is a production-ready, reactive options trading platform for NSE that executes multi-leg options strategies (Iron Condor, Bull Put Spread, Bear Call Spread, Expiry Day Strangle) based on real-time market intelligence and risk management rules.

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Technology Stack](#technology-stack)
3. [Package Structure](#package-structure)
4. [Database Schema](#database-schema)
5. [Trading Strategies](#trading-strategies)
6. [Scheduled Jobs](#scheduled-jobs)
7. [REST API Endpoints](#rest-api-endpoints)
8. [Data Pipeline & Iron Condor Foundation](#data-pipeline--iron-condor-foundation)
9. [Architecture Patterns](#architecture-patterns)
10. [Key Bugs Fixed (June 29-30, 2026)](#key-bugs-fixed-june-29-30-2026)

---

## Project Overview

### Purpose

TradeMesh automates multi-leg options trading on NSE with:
- **Real-time market intelligence** (VIX, volatility, open interest, max pain)
- **Intelligent strategy selection** based on market conditions
- **Automated trade execution** with paper trading simulation
- **Position monitoring** with mark-to-market every 60 seconds
- **Risk management** with position limits and daily loss controls
- **Real-time alerts** via Telegram

### Architecture Summary

```
Zerodha Kite API (Market Data)
    ↓
Market Intelligence Layer (VIX, OI, Max Pain, Metrics)
    ↓
Strategy Evaluation (Iron Condor, Spreads, Expiry Day)
    ↓
Risk Management (Position Limits, Daily Loss Limits)
    ↓
Paper Trading Engine (Simulation)
    ↓
Execution Layer (Paper or Live via Zerodha)
    ↓
Position Monitoring (Mark-to-Market every 60 sec)
    ↓
Telegram Alerts & Daily Reports
```

### Key Characteristics

- **Reactive:** Spring WebFlux throughout; no `.block()` anywhere
- **R2DBC:** Reactive database access to PostgreSQL
- **Redis:** Token storage, option chain caching, tick price caching
- **Event-Driven:** Market intelligence refreshes every 5 minutes; trades execute at scheduled times
- **Read/Write Separation:** REST endpoints read-only; all DB writes through schedulers
- **Structured Logging:** SLF4J only; no System.out.println

---

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| **Java** | OpenJDK | 21 |
| **Framework** | Spring Boot | 3.3.0 |
| **Reactive Web** | Spring WebFlux | 3.3.0 |
| **Database** | PostgreSQL | 14+ |
| **Database Access** | R2DBC + Spring Data R2DBC | 1.5.x |
| **Schema Migrations** | Liquibase | 4.x |
| **Cache** | Redis | 6+ |
| **Redis Client** | Lettuce (Reactive) | 6.x |
| **Build Tool** | Gradle | 8.x |
| **Broker Integration** | Zerodha Kite Connect SDK | Custom (kiteconnect.jar) |
| **JSON Processing** | Jackson | 2.15.x |
| **Object Mapping** | MapStruct | 1.5.5 |
| **Boilerplate Reduction** | Lombok | 1.18.x |
| **API Documentation** | SpringDoc OpenAPI | 2.5.0 |
| **Testing** | JUnit 5, Reactor Test, TestContainers | Latest |

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
- **`LotSizeService`** — Lot size lookup for NIFTY50 (65), NIFTYBANK (30)
- **`PaperTradeLegsConverter`** — JSON converter for trade legs
- **`R2dbcConfig`** — R2DBC connection pool configuration
- **`ResilienceConfig`** — Retry and circuit breaker policies
- **`WebClientConfig`** — WebClient configuration for HTTP calls

#### `domain` — Core Domain Entities
- **`Instrument`** — Zerodha instrument master (token, symbol, strike, expiry, lot size)
- **`InstrumentRepository`** — R2DBC repository for instruments
- **`NiftyDailyPrice`** — NIFTY50 OHLC data (trade_date, open, high, low, close, volume)
- **`NiftyDailyPriceRepository`** — R2DBC repository for daily prices

#### `execution` — Trade Execution & Position Management
- **`MarkToMarketScheduler`** — Marks all open trades to market every 60 seconds (fixedRate)
- **`OrderExecutionService`** — Executes orders via Zerodha API
- **`PaperTrade`** — Simulated trade record (UUID id, symbol, strategy, status, legs, P&L)
- **`PaperTradeController`** — REST endpoints for paper trade queries
- **`PaperTradeRepository`** — R2DBC repository for paper trades
- **`PaperTradingEngine`** — Simulates trade execution and mark-to-market calculations
- **`TradeStatus`** — Enum: OPEN, CLOSED, CANCELLED

#### `intelligence` — Market Intelligence & Analysis
- **`IntelligenceScheduler`** — Refreshes market intelligence every 5 minutes (cron: `0 */5 9-15 ? * MON-FRI`)
- **`KiteHistoricalService`** — Fetches historical OHLC from Kite API
- **`MarketCondition`** — Enum: SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE
- **`MarketIntelligence`** — Aggregated market data (VIX, OI, max pain, condition, metrics)
- **`MarketIntelligenceController`** — REST endpoints for market intelligence
- **`MarketIntelligenceService`** — Generates complete market intelligence for a symbol
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

##### `intelligence.maxpain` — Max Pain Analysis
- **`MaxPainCalculator`** — Calculates theoretical max pain for call/put sides
- **`MaxPainService`** — Persists max pain snapshots every 30 minutes
- **`MaxPainHistory`** — Max pain record (symbol, expiry_date, max_pain_strike, spot_price, distance)

##### `intelligence.oi` — Open Interest Analysis
- **`OiAnalysis`** — OI wall detection (max_call_oi_strike, max_put_oi_strike, market_bias)
- **`OiAnalysisService`** — Analyzes option chain for OI walls
- **`OiSnapshot`** — OI snapshot record (symbol, max_call_strike, max_put_strike, data JSONB)

##### `intelligence.vix` — VIX Monitoring
- **`VixAlertService`** — Sends Telegram alerts when VIX crosses thresholds
- **`VixData`** — Current VIX value, level, change, safe-to-trade flag
- **`VixHistory`** — VIX snapshot record (vix_value, vix_level, recorded_at)
- **`VixMonitorService`** — Fetches current VIX from Kite API
- **`VixScheduler`** — Persists VIX every 15 minutes (cron: `0 */15 9-15 ? * MON-FRI`)

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

- **`zerodha.orders`** — Order execution
  - **`OrderExecutionService`** — Places orders via Kite API
  - **`OrderStatus`** — Order status tracking

- **`zerodha.ticker`** — WebSocket market data
  - **`KiteTickerService`** — WebSocket connection to KiteTicker
  - **`TickPrice`** — Tick price data (symbol, ltp, timestamp)
  - **`TickerController`** — Server-Sent Events endpoint for price streaming

---

## Database Schema

### Core Tables

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| **zerodha_sessions** | OAuth2 sessions | user_id (unique), access_token, expires_at |
| **instruments** | Master instrument list | instrument_token (PK), tradingsymbol, strike, expiry, lot_size |
| **strategies** | Strategy definitions | id (PK), name (unique), is_active, config_json (JSONB) |
| **orders** | Order records | id (PK), zerodha_order_id (unique), strategy_id (FK), status |
| **portfolio_positions** | Current positions | id (PK), tradingsymbol, quantity, average_price, pnl |

### Trading Tables

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| **paper_trades** | Paper trading records | id (UUID, PK), trade_date, symbol, strategy_name, status, legs (JSONB), entry_credit, realized_pnl, unrealized_pnl |
| **strategy_execution_logs** | Strategy execution events | id (UUID, PK), symbol, strategy_name, action (ENTRY/EXIT), reason, executed_at |

### Intelligence Tables

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| **nifty_daily_prices** | NIFTY50 OHLC | id (UUID, PK), trade_date (unique), open, high, low, close, volume |
| **iv_history** | Daily ATM IV | id (UUID, PK), symbol, trade_date (unique), atm_iv, call_iv, put_iv, spot_price, expiry_date |
| **vix_history** | VIX snapshots | id (BIGSERIAL, PK), vix_value, vix_level, recorded_at |
| **vix_alerts** | VIX threshold alerts | id (BIGSERIAL, PK), vix_value, vix_level, alert_message, telegram_sent, triggered_at |
| **oi_snapshots** | OI analysis snapshots | id (BIGSERIAL, PK), symbol, max_call_strike, max_put_strike, market_bias, data (JSONB), recorded_at |
| **max_pain_history** | Max pain snapshots | id (BIGSERIAL, PK), symbol, expiry_date, max_pain_strike, spot_price, distance_from_spot, calculated_at |
| **option_chain_snapshots** | Full option chain snapshots | id (BIGSERIAL, PK), symbol, expiry_date, spot_price, data (JSONB), created_at |

### Schema Management

- **Schema:** `trade_mesh`
- **Migrations:** Liquibase YAML changesets in `src/main/resources/db/changelog/changes/`
- **Current Changesets:** 13
  - 001: Create schema
  - 002: Create core tables (zerodha_sessions, instruments, strategies, orders, portfolio_positions)
  - 003: Create paper_trades
  - 004: Create strategy_execution_logs
  - 005: Create option_chain_snapshots
  - 006: Create vix_history
  - 007: Create vix_alerts
  - 008: Create oi_snapshots
  - 009: Create max_pain_history
  - 010: Create nifty_daily_prices
  - 011: Create iv_history
  - 012: Alter oi_snapshots data type
  - 013: Make oi_snapshots data nullable

---

## Trading Strategies

### 1. EXPIRY_DAY_STRANGLE

**Status:** ✅ Live validated June 30, 2026 | TARGET_HIT confirmed

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

**Configuration:**
```yaml
EXPIRY_DAY_STRANGLE:
  enabled: true
  allowed-days: [TUESDAY]
  max-vix: 18.0
  min-vix: 10.0
  latest-entry-time: "12:30"
  min-credit: 20.0
  max-lots: 1
  stop-loss-percent: 100.0
  target-profit-percent: 50.0
  extra:
    min-distance-atr-multiplier: 0.4
```

---

### 2. IRON_CONDOR

**Status:** ✅ Built and unit-tested | Awaiting first live trade

**Market Condition:** SIDEWAYS

**Entry Logic:**
- Sells both call and put spreads in sideways markets
- Strike selection: Delta-based (target delta 0.18) via Black-Scholes engine
- Wing width: 200 points
- Confidence scoring:
  - IVR (20-80 range): 15-30 points
  - IV/HV ratio (≥1.1): 10-20 points
  - ATR% (<0.6-1.0): 10-20 points
  - VIX (12-20 range): 5-15 points
  - PCR (0.8-1.2 balanced): 15 points
  - **Minimum score: 60/100** to execute
- DTE range: 3-15 days
- Minimum credit: 30 points (15% of wing width)

**Exit Conditions:**
- **TARGET_HIT:** 50% of credit collected
- **STOPLOSS_HIT:** 100% of credit collected
- **LEG_BREACH:** Any leg breaches its strike
- **FORCE_CLOSE_EOD:** 3:15 PM IST

**Dynamic Exit Adjustment:**
- Calm market (ATR% < 0.7): Tighter targets (90% of base)
- High volatility (ATR% > 1.0): Wider targets (110% of base)

**Configuration:**
```yaml
IRON_CONDOR:
  enabled: true
  allowed-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
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
    min-credit-pct: 0.15
    min-score: 60
    dte-min: 3
    dte-max: 15
    atr-calm-threshold: 0.7
    atr-high-threshold: 1.0
    calm-target-multiplier: 0.9
    calm-sl-multiplier: 0.8
    high-target-multiplier: 1.1
    high-sl-multiplier: 1.2
```

---

### 3. BULL_PUT_SPREAD

**Status:** ✅ Built | Paper-tested June 29

**Market Condition:** TRENDING_BULLISH

**Entry Logic:**
- Sells OTM put spread in bullish markets
- Strike selection: Delta-based (target delta 0.30)
- Wing width: 100 points
- Profits when market stays above short put strike
- DTE range: 3-15 days
- Minimum credit: 10 points (10% of wing width)

**Exit Conditions:**
- **TARGET_HIT:** 50% of credit collected
- **STOPLOSS_HIT:** 100% of credit collected
- **FORCE_CLOSE_EOD:** 3:15 PM IST

**Configuration:**
```yaml
BULL_PUT_SPREAD:
  enabled: true
  allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
  max-vix: 20.0
  min-vix: 10.0
  latest-entry-time: "13:00"
  min-credit: 20.0
  max-lots: 1
  stop-loss-percent: 100.0
  target-profit-percent: 50.0
  extra:
    dte-min: 3
    dte-max: 15
    target-delta: 0.30
    wing-width: 100
    min-credit-pct: 0.10
```

---

### 4. BEAR_CALL_SPREAD

**Status:** ✅ Built | Paper-tested June 29 | Multiple trades executed

**Market Condition:** TRENDING_BEARISH

**Entry Logic:**
- Sells OTM call spread in bearish markets
- Strike selection: Delta-based (target delta 0.30)
- Wing width: 100 points
- Profits when market stays below short call strike
- DTE range: 3-15 days
- Minimum credit: 10 points (10% of wing width)

**Exit Conditions:**
- **TARGET_HIT:** 50% of credit collected
- **STOPLOSS_HIT:** 100% of credit collected
- **FORCE_CLOSE_EOD:** 3:15 PM IST

**Configuration:**
```yaml
BEAR_CALL_SPREAD:
  enabled: true
  allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
  max-vix: 20.0
  min-vix: 10.0
  latest-entry-time: "13:00"
  min-credit: 20.0
  max-lots: 1
  stop-loss-percent: 100.0
  target-profit-percent: 50.0
  extra:
    dte-min: 3
    dte-max: 15
    target-delta: 0.30
    wing-width: 100
    min-credit-pct: 0.10
```

---

## Scheduled Jobs

### TradingScheduler

| Job | Cron | Time (IST) | Frequency | Action |
|-----|------|-----------|-----------|--------|
| **scheduledDailyEntry** | `0 30 9 * * MON-FRI` | 9:30 AM | Weekdays | Runs trading cycle entry for NIFTY50; StrategySelector picks strategy based on market condition |
| **scheduledDailyExit** | `0 15 15 * * MON-FRI` | 3:15 PM | Weekdays | Forces exit of all open positions |
| **scheduledDailyReport** | `0 45 15 * * MON-FRI` | 3:45 PM | Weekdays | Generates daily P&L report and sends via Telegram |

### IntelligenceScheduler

| Job | Cron | Time (IST) | Frequency | Action |
|-----|------|-----------|-----------|--------|
| **refreshMarketIntelligence** | `0 */5 9-15 ? * MON-FRI` | Every 5 min | Market hours | Refreshes market intelligence for NIFTY50 and NIFTYBANK; detects condition changes; sends Telegram alerts |

### MarkToMarketScheduler

| Job | Interval | Frequency | Action |
|-----|----------|-----------|--------|
| **markToMarketScheduled** | 60,000 ms (60 sec) | Market hours | Marks all open paper trades to market; updates unrealized P&L |

### VixScheduler (in IntelligenceScheduler)

| Job | Cron | Time (IST) | Frequency | Action |
|-----|------|-----------|-----------|--------|
| **persistVix** | `0 */15 9-15 ? * MON-FRI` | Every 15 min | Market hours | Persists VIX snapshot to database |

---

## REST API Endpoints

### Authentication

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/zerodha/login` | Redirect to Kite Connect OAuth2 login |
| GET | `/zerodha/callback?request_token=xxx` | OAuth2 callback; exchanges token and stores in Redis |
| GET | `/zerodha/token/status` | Check if Zerodha token is valid and available |

### Market Data

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/market/price/{symbol}` | Get latest cached price for symbol |
| GET | `/market/stream/{symbol}` | Server-Sent Events stream of live prices |
| GET | `/market/status` | WebSocket connection status |

### Market Intelligence

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/intelligence/{symbol}` | Complete market intelligence (VIX, OI, max pain, metrics, condition) |
| GET | `/intelligence/{symbol}/condition` | Market condition only (SIDEWAYS, TRENDING_BULLISH, etc.) |
| GET | `/intelligence/{symbol}/summary` | One-line summary of market intelligence |
| GET | `/intelligence/metrics/{symbol}` | Market metrics (HV20, ATR14, IVR, IV/HV, PCR) |
| GET | `/intelligence/vix` | Current VIX data (value, level, change, safe-to-trade) |
| GET | `/intelligence/maxpain` | Max pain data for current expiry |
| GET | `/intelligence/oi` | Open interest analysis (OI walls, market bias) |

### Strategy

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/strategy/evaluate/{symbol}` | Evaluate all strategies for symbol; returns best executable signal |
| GET | `/strategy/applicable/{symbol}` | List strategies applicable to current market condition |
| GET | `/strategy/status` | Status of all strategies (enabled/disabled) |

### Trading

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/trading/expiry-day/trigger` | Manually trigger expiry day entry (testing only) |
| POST | `/trading/run/{symbol}` | Manually trigger trading cycle for symbol |
| GET | `/trading/positions/open` | Get all currently open positions with live P&L refresh |
| POST | `/trading/monitor` | Manually trigger position monitoring for all open positions |
| GET | `/trading/report/today` | Get today's trading summary (total trades, wins, losses, P&L) |

### Paper Trading

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/paper/trades/open` | Get all open paper trades |
| GET | `/paper/trades/history` | Get all closed paper trades |
| GET | `/paper/trades/{id}` | Get specific paper trade by ID |
| GET | `/paper/trades/symbol/{symbol}` | Get trades for specific symbol |
| GET | `/paper/trades/strategy/{strategyName}` | Get trades for specific strategy |
| GET | `/paper/trades/pnl/weekly` | Get weekly P&L summary |
| GET | `/paper/trades/stats` | Get overall trading statistics |
| POST | `/paper/trades/{id}/close` | Manually close a paper trade |

### Admin (Internal Use)

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/admin/bootstrap/historical-prices?from=YYYY-MM-DD&to=YYYY-MM-DD` | Bootstrap historical NIFTY50 OHLC from Kite API (idempotent) |
| GET | `/admin/test/atm-iv?symbol=NIFTY50` | Test ATM IV calculation (read-only, no DB save) |
| GET | `/admin/test/atm-iv-save?symbol=NIFTY50` | Test ATM IV calculation and save to DB |
| GET | `/admin/test/iron-condor-evaluate` | Test Iron Condor strategy evaluation |
| GET | `/admin/test/bull-put-spread-evaluate` | Test Bull Put Spread strategy evaluation |
| GET | `/admin/test/bear-call-spread-evaluate` | Test Bear Call Spread strategy evaluation |
| GET | `/admin/test/expiry-day-evaluate` | Test Expiry Day Strangle strategy evaluation |

---

## Data Pipeline & Iron Condor Foundation

### Historical Data Foundation

**nifty_daily_prices:** 616 rows bootstrapped from Kite historical API
- Covers ~2.5 years of NIFTY50 daily OHLC data
- Used for HV20 (20-day historical volatility) calculation
- Bootstrapped via `POST /admin/bootstrap/historical-prices?from=2024-01-01&to=2024-12-31`

**iv_history:** Daily ATM IV collection at 9:25 AM
- Captures ATM IV for current expiry at market open
- Used for IVR (IV Rank) and IV/HV ratio calculations
- Unique constraint: (symbol, trade_date)

### Market Metrics Service

**MarketMetricsService** calculates:
- **HV20:** 20-day historical volatility from nifty_daily_prices
- **ATR14:** 14-day Average True Range
- **ATRPct:** ATR as percentage of spot price
- **IVR:** IV Rank (current IV percentile over 52-week range)
- **IV/HV Ratio:** Current IV divided by HV20
- **PCR:** Put/Call Ratio from option chain open interest

### Iron Condor Intelligence Pipeline

1. **Fetch Option Chain** → Get all strikes, call/put prices, IVs, OI
2. **Calculate Market Metrics** → HV20, ATR14, IVR, IV/HV, PCR
3. **Fetch VIX** → Current VIX level
4. **Calculate Confidence Score** → IVR (20-30 pts), IV/HV (10-20 pts), ATR% (10-20 pts), VIX (5-15 pts), PCR (15 pts)
5. **Validate Confidence** → Must be ≥60/100 to proceed
6. **Select Strikes** → Delta-based (target delta 0.18) for short legs
7. **Calculate P&L Matrix** → Max profit, max loss, breakeven, risk/reward ratio
8. **Adjust Exit Levels** → Based on ATR% volatility (calm/normal/high)
9. **Build Signal** → 4-leg signal with metadata

### Bootstrap Endpoint

**POST /admin/bootstrap/historical-prices**

Fetches historical NIFTY50 OHLC from Kite Connect and stores in database.

**Parameters:**
- `from` (optional): Start date (YYYY-MM-DD), default "2024-01-01"
- `to` (optional): End date (YYYY-MM-DD), default today

**Response:**
```json
{
  "saved": 250,
  "skipped": 0,
  "from": "2024-01-01",
  "to": "2024-12-31",
  "total": 250
}
```

**Idempotency:** Calling multiple times with same date range skips existing records.

---

## Architecture Patterns

### 1. Reactive Throughout

- **No `.block()` anywhere** — All operations are non-blocking
- **Mono/Flux throughout** — Reactive streams for all async operations
- **R2DBC for database** — Reactive database access
- **Lettuce for Redis** — Reactive Redis client
- **WebFlux for HTTP** — Reactive web framework

### 2. Entity Pattern

All entities follow the **Persistable<UUID>** pattern:

```java
@Data
@Entity
@Table(name = "paper_trades")
public class PaperTrade implements Persistable<UUID> {
    @Id
    private UUID id;
    
    @Transient
    private boolean isNew = false;  // Default: false (not new)
    
    // ... other fields
    
    @Override
    public boolean isNew() {
        return isNew;
    }
    
    // Factory method (not @PostLoad — JPA annotation doesn't work in R2DBC)
    public static PaperTrade createNew() {
        PaperTrade trade = new PaperTrade();
        trade.isNew = true;
        return trade;
    }
}
```

**Key Points:**
- `isNew` defaults to `false` (entity already exists in DB)
- Use `createNew()` factory method to create new entities
- Do NOT use `@PostLoad` — R2DBC doesn't support it

### 3. Database Writes: Schedulers Only

**Architectural Rule:** All DB writes happen exclusively through schedulers, never from REST endpoints.

**Pattern:**
```java
// REST Controller (read-only)
@GetMapping("/positions/open")
public Mono<ResponseEntity<List<PaperTrade>>> getOpenPositions() {
    return paperTradeRepository.findByStatus(TradeStatus.OPEN)
            .collectList()
            .map(ResponseEntity::ok);
}

// Scheduler (writes to DB)
@Scheduled(cron = "0 30 9 * * MON-FRI")
public void scheduledDailyEntry() {
    tradingOrchestrator.runTradingCycle("NIFTY50")
            .subscribe();  // Writes trades to DB
}
```

### 4. Service Layer: Read + Save Methods

Services expose two method variants:

```java
@Service
public class PaperTradeService {
    
    // READ method (REST-facing): fetches/calculates, no save
    public Mono<PaperTrade> getTradeById(UUID id) {
        return paperTradeRepository.findById(id);
    }
    
    // SAVE method (scheduler-facing): calls read method then saves
    public Mono<PaperTrade> saveNewTrade(PaperTrade trade) {
        return paperTradeRepository.save(trade);
    }
}
```

### 5. REST Endpoints: Read-Only Display

REST endpoints never persist to DB. They only:
- Fetch data from repositories
- Calculate derived values
- Return responses

All persistence happens through schedulers.

### 6. Token Guard: Check Before Kite API Calls

Before any Kite API call in schedulers, check token availability:

```java
@Scheduled(cron = "0 */5 9-15 ? * MON-FRI")
public void refreshMarketIntelligence() {
    if (!tokenTracker.isTokenAvailable()) {
        log.warn("Zerodha token unavailable. Skipping refresh.");
        return;
    }
    
    // Proceed with Kite API calls
    for (String symbol : SYMBOLS) {
        refreshIntelligence(symbol).subscribe();
    }
}
```

### 7. Structured Logging

- **Use SLF4J only** — No System.out.println
- **Log business events at INFO/DEBUG** — Entry signals, exits, errors
- **Log exceptions at ERROR** — Stack traces for debugging

```java
log.info("Iron Condor signal generated: {} | Credit: {} points", 
         signal.getReason(), legsResult.getTotalCredit());
log.error("Error evaluating strategy", e);
```

---

## Key Bugs Fixed (June 29-30, 2026)

### BUG 1 — Wrong Expiry Date Traded on Expiry Day

**Root Cause:**
`findNearestWeeklyExpiry()` used `isAfter(today)` which excluded today's expiry.

**Symptom:**
On Tuesday (expiry day), the strategy would trade next week's contracts instead of today's.

**Fix:**
Changed to `!isBefore(today)` to include today's expiry.

**Code Change:**
```java
// BEFORE (wrong)
LocalDate expiryDate = optionChain.getStrikes().stream()
    .map(OptionStrike::getExpiryDate)
    .filter(date -> date.isAfter(today))  // Excludes today!
    .min(Comparator.naturalOrder())
    .orElse(null);

// AFTER (correct)
LocalDate expiryDate = optionChain.getStrikes().stream()
    .map(OptionStrike::getExpiryDate)
    .filter(date -> !date.isBefore(today))  // Includes today
    .min(Comparator.naturalOrder())
    .orElse(null);
```

**Impact:**
Before fix: Strategy traded next week's contracts on expiry day (wrong)
After fix: Strategy correctly trades today's expiry contracts

**Validation:**
Live validated June 30, 2026 — strategy correctly picked today's expiry.

---

### BUG 2 — OI-Wall Strike Correction Was Insufficient and Arbitrary

**Root Cause:**
Flat 100-point `minDistance` + single fixed-offset widening (50 pts) was insufficient and arbitrary.

**Symptom:**
OI walls too close to spot price caused trades to fail or have insufficient safety margin.

**Fix:**
Implemented ATR-scaled safety boundary:
- `min-distance-atr-multiplier: 0.4` — ATR14 × 0.4 = minimum distance
- Principled fallback: If OI wall inside boundary, use boundary strike instead
- Hard skip: If OI wall on wrong side of spot (structural invalidity)

**Code Change:**
```java
// BEFORE (arbitrary)
double minDistance = 100.0;  // Flat 100 points
BigDecimal boundaryCall = spotPrice.add(BigDecimal.valueOf(minDistance + 50));  // +50 arbitrary widening

// AFTER (ATR-scaled)
double atr14 = metrics.getAtr14().doubleValue();
double atrMultiplier = 0.4;  // Configurable
BigDecimal minDistance = BigDecimal.valueOf(atr14 * atrMultiplier);
BigDecimal boundaryCall = roundUpToStep(spotPrice.add(minDistance), 50);

// Principled fallback
if (callStrike.compareTo(boundaryCall) < 0) {
    log.info("Call OI wall {} inside ATR-scaled safety boundary {} — falling back",
             callStrike, boundaryCall);
    callStrike = boundaryCall;
}
```

**Impact:**
Before fix: Arbitrary 100-point distance; insufficient safety margin
After fix: ATR-scaled distance; principled fallback; hard skip on structural invalidity

**Validation:**
Tested with multiple market conditions; safety boundary now adapts to volatility.

---

## Getting Started

### Prerequisites

- Java 21
- PostgreSQL 14+
- Redis 6+
- Zerodha Kite Connect API credentials

### Environment Variables

```bash
# Database
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=trade_mesh_db
export DB_USERNAME=postgres
export DB_PASSWORD=postgres

# Redis
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=

# Zerodha
export ZERODHA_API_KEY=your_api_key
export ZERODHA_API_SECRET=your_api_secret
export ZERODHA_REDIRECT_URL=http://localhost:8080/zerodha/callback

# Telegram
export TELEGRAM_BOT_TOKEN=your_bot_token
export TELEGRAM_CHAT_ID=your_chat_id
```

### Build & Run

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Access API
# Swagger UI: http://localhost:8080/swagger-ui.html
# API Docs: http://localhost:8080/v3/api-docs
```

### Bootstrap Historical Data

```bash
curl -X POST "http://localhost:8080/admin/bootstrap/historical-prices?from=2024-01-01&to=2024-12-31"
```

---

## Summary

TradeMesh is a **production-ready, reactive options trading platform** with:
- ✅ Four fully implemented trading strategies
- ✅ Comprehensive market intelligence pipeline
- ✅ Paper trading simulation with mark-to-market
- ✅ Real-time position monitoring
- ✅ Risk management and position limits
- ✅ Telegram alerting and daily reports
- ✅ Zerodha Kite Connect integration
- ✅ PostgreSQL + R2DBC + Redis architecture

The codebase is well-structured, follows reactive patterns throughout, and is ready for live trading with minimal additional work.
