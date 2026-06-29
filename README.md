# TradeMesh — Automated Options Trading Platform

**TradeMesh** is a Spring WebFlux-based reactive trading platform for automated options strategies on NSE (National Stock Exchange). It integrates with Zerodha's Kite Connect API for live market data and order execution, uses PostgreSQL for persistence, and Redis for caching and token management.

**Current Date:** June 29, 2026 | **Market:** NSE (India) | **Timezone:** IST (Asia/Kolkata)

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Architecture Overview](#architecture-overview)
3. [Package Structure](#package-structure)
4. [Database Schema](#database-schema)
5. [Scheduled Jobs](#scheduled-jobs)
6. [REST API Endpoints](#rest-api-endpoints)
7. [Trading Strategies](#trading-strategies)
8. [Configuration](#configuration)
9. [Architecture Patterns](#architecture-patterns)
10. [Data Pipelines](#data-pipelines)

---

## Quick Start

### Prerequisites

- **Java 17+**
- **PostgreSQL 14+**
- **Redis 6+**
- **Zerodha Account** with Kite Connect API credentials

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

# Telegram (optional)
export TELEGRAM_BOT_TOKEN=your_bot_token
export TELEGRAM_CHAT_ID=your_chat_id
```

### Build & Run

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Access Swagger UI
http://localhost:8080/swagger-ui.html
```

---

## Architecture Overview

TradeMesh follows a **reactive, event-driven architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────┐
│                    REST Controllers                          │
│  (Read-only, no DB writes)                                  │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│                    Services                                  │
│  (Business logic, calculations, read operations)            │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│              Repositories (R2DBC)                            │
│  (Data access layer, reactive)                              │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│         PostgreSQL + Redis                                   │
│  (Persistence + Caching)                                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    Schedulers                                │
│  (Automated tasks, DB writes only)                          │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
            Services (save methods)
                       │
                       ▼
            Repositories (write operations)
```

### Key Principles

- **REST endpoints are read-only** — no database writes from controllers
- **All DB writes happen through schedulers** — via service save methods
- **Reactive (Spring WebFlux)** — non-blocking I/O throughout
- **R2DBC for PostgreSQL** — reactive database access
- **Redis for caching** — token storage, price cache, option chain cache
- **Structured logging** — no `System.out.println`, use SLF4J

---

## Package Structure

### `com.trademesh`

**Root package** — Application entry point and core configuration.

- **`TradeMeshApplication`** — Spring Boot application main class

### `com.trademesh.config`

**Configuration & Constants** — Application-wide settings and utilities.

| Class | Responsibility |
|-------|-----------------|
| `ApplicationConstants` | Market hours, symbols (NIFTY50, NIFTYBANK), instrument tokens |
| `JacksonConfig` | JSON serialization/deserialization configuration |
| `JdbcConfig` | JDBC DataSource for Liquibase migrations |
| `R2dbcConfig` | R2DBC connection pool for reactive database access |
| `WebClientConfig` | WebClient bean for HTTP requests (20MB buffer for Kite instruments) |
| `ResilienceConfig` | Circuit breaker and retry policies |
| `PaperTradeLegsConverter` | Converts JSONB trade legs to/from database |

### `com.trademesh.domain`

**Core Domain Entities** — Master data and reference tables.

| Class | Responsibility |
|-------|-----------------|
| `Instrument` | Zerodha instrument master (token, symbol, strike, expiry) |
| `InstrumentRepository` | R2DBC repository for instruments |
| `NiftyDailyPrice` | NIFTY50 daily OHLC data (for HV20 calculation) |
| `NiftyDailyPriceRepository` | R2DBC repository for daily prices |

### `com.trademesh.intelligence`

**Market Intelligence & Analysis** — VIX, OI, max pain, market metrics.

| Class | Responsibility |
|-------|-----------------|
| `MarketIntelligence` | Aggregated market data (VIX, OI, max pain, condition, safety) |
| `MarketIntelligenceService` | Generates complete market intelligence for a symbol |
| `MarketIntelligenceController` | REST endpoints for market intelligence |
| `MarketMetrics` | Calculated metrics (HV20, ATR14, IVR, IV/HV ratio, PCR) |
| `MarketMetricsService` | Calculates HV20, ATR14, IVR, IV/HV ratio, ATR percentile, PCR |
| `MarketCondition` | Enum: SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE |
| `SignalDirection` | Enum: BULLISH, BEARISH, NEUTRAL |
| `TradingSignal` | Signal data (strategy, legs, credit, max loss) |
| `SignalService` | Generates trading signals from market intelligence |
| `KiteHistoricalService` | Fetches historical OHLC from Kite Connect API |
| `NiftyDailyPriceService` | Manages NIFTY50 daily price data (read & save methods) |
| `IntelligenceScheduler` | Refreshes market intelligence every 5 minutes (9:15 AM–3:30 PM IST) |

#### `com.trademesh.intelligence.vix`

**VIX Monitoring** — Volatility index tracking and alerts.

| Class | Responsibility |
|-------|-----------------|
| `VixData` | Current VIX value, level, change, safe-to-trade flag |
| `VixMonitorService` | Fetches current VIX from Zerodha API (cached 5 min) |
| `VixHistory` | Historical VIX records (persisted every 15 min) |
| `VixAlert` | VIX threshold crossing alerts |
| `VixAlertService` | Detects VIX threshold crossings, sends Telegram alerts |
| `VixScheduler` | Fetches VIX every 5 min, persists every 15 min, logs stats every 30 min |
| `VixController` | REST endpoint: `GET /intelligence/vix` |

#### `com.trademesh.intelligence.oi`

**Open Interest Analysis** — OI walls, support/resistance levels.

| Class | Responsibility |
|-------|-----------------|
| `OIAnalysis` | OI wall analysis (max call OI strike, max put OI strike) |
| `OIWall` | Strike-level OI data |
| `OIAnalyserService` | Analyzes option chain for OI walls, persists snapshots |
| `OIAnalysisScheduler` | Captures OI snapshots every 15 minutes |
| `OIController` | REST endpoints for OI analysis |

#### `com.trademesh.intelligence.maxpain`

**Max Pain Calculation** — Theoretical max pain levels.

| Class | Responsibility |
|-------|-----------------|
| `MaxPainResult` | Calculated max pain for call/put sides |
| `MaxPainCalculatorService` | Calculates max pain from option chain, persists snapshots |
| `MaxPainScheduler` | Captures max pain snapshots every 30 minutes |
| `MaxPainController` | REST endpoints for max pain data |

#### `com.trademesh.intelligence.iv`

**Implied Volatility Tracking** — ATM IV history for Iron Condor.

| Class | Responsibility |
|-------|-----------------|
| `IvHistory` | Daily ATM IV record (symbol, trade_date, atm_iv, call_iv, put_iv) |
| `IvHistoryRepository` | R2DBC repository for IV history |
| `IvHistoryService` | Calculates ATM IV (read method), saves ATM IV (save method) |
| `ImpliedVolatilityCalculator` | Black-Scholes IV and delta calculations |
| `IvHistoryScheduler` | Captures ATM IV at 9:25 AM IST on weekdays |

### `com.trademesh.strategy`

**Strategy Framework** — Strategy selection, evaluation, execution.

| Class | Responsibility |
|-------|-----------------|
| `TradingStrategy` | Interface for all trading strategies |
| `StrategyConfig` | Strategy configuration (max lots, SL%, TP%, VIX range, allowed days) |
| `StrategyProperties` | YAML-bound strategy properties from `application.yml` |
| `StrategySelector` | Selects applicable strategy for current market condition |
| `StrategyOrchestrator` | Orchestrates strategy evaluation pipeline |
| `StrategyEngine` | Executes strategy evaluation logic |
| `StrategyResult` | Strategy evaluation result (executable, signal, skip reason) |
| `TradeSignal` | Trade signal (legs, credit, max loss, confidence) |
| `TradeLeg` | Individual trade leg (instrument, action, strike, quantity) |
| `StrategyController` | REST endpoints: evaluate, applicable, status |

#### `com.trademesh.strategy.expiryday`

**Expiry Day Strangle Strategy** — Sells both call & put at OI walls on expiry day.

| Class | Responsibility |
|-------|-----------------|
| `ExpiryDayStrategy` | Implements EXPIRY_DAY_STRANGLE strategy |
| `ExpiryDayConfig` | Configuration for expiry day strategy |

**Status:** ✅ **ENABLED** (Tuesday expiry day, 9:30 AM entry, 3:15 PM exit)

**Configuration:**
```yaml
- name: EXPIRY_DAY_STRANGLE
  enabled: true
  allowed-days: [TUESDAY]
  applicable-conditions: [EXPIRY_DAY]
  max-vix: 18.0
  min-vix: 10.0
  latest-entry-time: "12:30"
  min-credit: 20.0
  min-distance-from-spot: 100
  max-lots: 1
  stop-loss-percent: 100.0
  target-profit-percent: 50.0
```

#### `com.trademesh.strategy.spread`

**Spread Strategies** — Bull Put Spread and Bear Call Spread for directional trades.

| Class | Responsibility |
|-------|-----------------|
| `BullPutSpreadStrategy` | Implements BULL_PUT_SPREAD strategy (bullish markets) |
| `BearCallSpreadStrategy` | Implements BEAR_CALL_SPREAD strategy (bearish markets) |

**Bull Put Spread Status:** ✅ **ENABLED** (Mon/Wed/Thu/Fri, 9:30 AM entry, 3:15 PM exit)

**Configuration:**
```yaml
- name: BULL_PUT_SPREAD
  enabled: true
  allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
  applicable-conditions: [TRENDING_BULLISH]
  max-vix: 20.0
  min-vix: 10.0
  latest-entry-time: "13:00"
  min-credit: 20.0
  max-lots: 1
  stop-loss-percent: 200.0
  target-profit-percent: 50.0
  extra:
    target-delta: 0.30
    wing-width: 100
    strike-step: 50
    min-credit-pct: 0.10
```

**Bear Call Spread Status:** ✅ **ENABLED** (Mon/Wed/Thu/Fri, 9:30 AM entry, 3:15 PM exit)

**Configuration:**
```yaml
- name: BEAR_CALL_SPREAD
  enabled: true
  allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
  applicable-conditions: [TRENDING_BEARISH]
  max-vix: 20.0
  min-vix: 10.0
  latest-entry-time: "13:00"
  min-credit: 20.0
  max-lots: 1
  stop-loss-percent: 200.0
  target-profit-percent: 50.0
  extra:
    target-delta: 0.30
    wing-width: 100
    strike-step: 50
    min-credit-pct: 0.10
```

#### `com.trademesh.strategy.ironcondor`

**Iron Condor Strategy** — Sells call & put spreads in sideways markets.

| Class | Responsibility |
|-------|-----------------|
| `IronCondorStrategy` | Implements IRON_CONDOR strategy with delta-based strike selection |
| `IronCondorSignal` | Iron Condor signal (short/long strikes, credit, P&L matrix) |

**Status:** ✅ **ENABLED** (Mon/Wed/Thu/Fri, 9:30 AM entry, 3:15 PM exit)

**Configuration:**
```yaml
- name: IRON_CONDOR
  enabled: true
  allowed-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
  applicable-conditions: [SIDEWAYS]
  max-vix: 20.0
  min-vix: 12.0
  latest-entry-time: "13:00"
  min-credit: 30.0
  max-lots: 1
  stop-loss-percent: 200.0
  target-profit-percent: 50.0
  extra:
    target-delta: 0.18
    wing-width: 200
    strike-step: 50
    min-credit-pct: 0.15
    min-score: 60
    ivr-min: 20.0
    ivr-max: 80.0
    iv-hv-ratio-min: 1.1
    atr-pct-max: 1.0
    dte-min: 1
    dte-max: 15
    atr-calm-threshold: 0.7
    atr-high-threshold: 1.0
    calm-target-multiplier: 0.9
    calm-sl-multiplier: 0.8
    high-target-multiplier: 1.1
    high-sl-multiplier: 1.2
    vix-panic-threshold: 25.0
    force-close-time: "15:15"
```

**Iron Condor Evaluation Pipeline:**
1. Check if enabled
2. Fetch current VIX (hard gate: VIX ≤ max-vix)
3. Calculate market metrics (HV20, ATR14, IVR, IV/HV ratio, PCR)
4. Check ATR% (hard gate: ATR% ≤ atr-pct-max)
5. Calculate confidence score (IVR, IV/HV, ATR%, VIX, PCR)
6. Check confidence score (hard gate: score ≥ min-score)
7. Fetch option chain
8. Check DTE (hard gate: dte-min ≤ DTE ≤ dte-max)
9. Select strikes using delta-based approach (target-delta = 0.18)
10. Calculate net credit and validate (credit ≥ min-credit-pct × wing-width)
11. Calculate P&L matrix (max profit, max loss, breakevens, risk/reward)
12. Adjust exit levels based on ATR% (calm/normal/high volatility)
13. Build trade legs and return signal

#### `com.trademesh.strategy.config`

**Strategy Configuration** — Unified strategy config loading.

| Class | Responsibility |
|-------|-----------------|
| `StrategiesConfig` | Loads all strategies from `application.yml` |

### `com.trademesh.trading`

**Trading Orchestration** — Main trading cycle, scheduling, reporting.

| Class | Responsibility |
|-------|-----------------|
| `TradingOrchestrator` | Orchestrates complete trading cycle (intelligence → strategy → execution) |
| `TradingScheduler` | Scheduled entry/exit for EXPIRY_DAY_STRANGLE and IRON_CONDOR |
| `TradingController` | REST endpoints: manual triggers, open positions, daily report |
| `MarketCalendarService` | Checks if market is open (9:15 AM–3:30 PM IST, Mon–Fri) |
| `DailyReportService` | Generates daily P&L report, sends via Telegram |
| `StrategyExecutionLog` | Logs strategy execution events (entry, exit, P&L) |
| `StrategyExecutionLogRepository` | R2DBC repository for execution logs |
| `AdminController` | Admin endpoints: bootstrap historical prices, test strategy evaluation |

### `com.trademesh.execution`

**Trade Execution & Paper Trading** — Order execution, position tracking, mark-to-market.

| Class | Responsibility |
|-------|-----------------|
| `PaperTradingEngine` | Simulates trade execution (entry, exit, mark-to-market) |
| `PaperTrade` | Paper trade record (legs, entry credit, P&L, status) |
| `PaperTradeRepository` | R2DBC repository for paper trades |
| `TradeStatus` | Enum: OPEN, CLOSED, CANCELLED |
| `OrderExecutionService` | Executes orders via Zerodha API (live mode) |
| `MarkToMarketScheduler` | Updates open trade values every 60 seconds (during market hours) |
| `PaperTradeController` | REST endpoints: open trades, history, P&L, stats |

### `com.trademesh.portfolio`

**Portfolio Management** — Position tracking, P&L summary.

| Class | Responsibility |
|-------|-----------------|
| `PortfolioService` | Aggregates portfolio data (positions, P&L) |
| `PortfolioSummary` | Portfolio summary (total P&L, exposure, margin) |
| `PositionSummary` | Individual position summary (symbol, quantity, P&L) |

### `com.trademesh.risk`

**Risk Management** — Position limits, daily loss limits, margin checks.

| Class | Responsibility |
|-------|-----------------|
| `RiskManager` | Enforces risk limits before trade execution |
| `RiskLimitsConfig` | Risk configuration (max position size, daily loss limit, sector exposure) |

**Configuration:**
```yaml
risk:
  max-position-size-per-symbol: 5
  daily-loss-limit-percent: 2.0
  max-sector-exposure: 50000
  min-margin-buffer-percent: 10
  position-sizing:
    risk-percent: 1.0
    max-quantity: 10
```

### `com.trademesh.zerodha`

**Zerodha Integration** — API authentication, market data, order execution.

#### `com.trademesh.zerodha.auth`

**Authentication & Token Management** — OAuth2, token storage, expiry monitoring.

| Class | Responsibility |
|-------|-----------------|
| `ZerodhaAuthService` | OAuth2 flow, token exchange, Redis storage |
| `ZerodhaAuthController` | REST endpoints: login, callback, token status |
| `ZerodhaConfig` | Zerodha API configuration (API key, secret, URLs) |
| `TokenAvailabilityTracker` | Tracks token availability across schedulers |
| `TokenRefreshScheduler` | Daily token health check (8:00 AM, 9:00 AM IST) |
| `TokenExpiryWatcher` | Monitors token expiry every 5 minutes, sends alerts |
| `SensitiveDataMasker` | Masks sensitive data in logs |

#### `com.trademesh.zerodha.ticker`

**Live Market Data (WebSocket)** — KiteTicker WebSocket connection, price streaming.

| Class | Responsibility |
|-------|-----------------|
| `ZerodhaTickerService` | Manages KiteTicker WebSocket connection, streams ticks |
| `TickerService` | Subscribes to instruments, processes ticks |
| `TickerConnectionManager` | Auto-connect on startup, health check every 30 sec, reconnect logic |
| `TickData` | Tick data (symbol, last price, high, low, volume, timestamp) |
| `MarketDataController` | REST endpoints: latest price, SSE stream, connection status |

#### `com.trademesh.zerodha.options`

**Option Chain Data** — Fetching, caching, snapshots.

| Class | Responsibility |
|-------|-----------------|
| `OptionChain` | Option chain data (symbol, expiry, spot, strikes) |
| `OptionStrike` | Strike-level data (strike, call/put LTP, IV, OI, Greeks) |
| `OptionChainService` | Fetches option chain from Kite API, caches in Redis |
| `OptionChainSnapshot` | Persisted option chain snapshot (JSONB) |
| `OptionChainSnapshotRepository` | R2DBC repository for snapshots |
| `OptionChainSnapshotScheduler` | Captures option chain snapshots every 5 minutes |
| `KiteQuotesResponse` | Kite API response wrapper |
| `KiteInstrumentsResponse` | Kite instruments CSV response |

#### `com.trademesh.zerodha.orders`

**Order Management** — Order placement, status tracking.

| Class | Responsibility |
|-------|-----------------|
| `OrderService` | Places orders via Kite API |
| `OrderRequest` | Order request (symbol, quantity, price, order type) |

### `com.trademesh.alert`

**Alerting System** — Telegram notifications.

| Class | Responsibility |
|-------|-----------------|
| `Alert` | Alert message (type, symbol, message) |
| `AlertType` | Enum: ENTRY_SIGNAL, EXIT_SIGNAL, SYSTEM_ERROR, VIX_ALERT |
| `TelegramNotificationBot` | Sends alerts via Telegram Bot API |
| `TelegramClient` | HTTP client for Telegram API |
| `TelegramProperties` | Telegram configuration (bot token, chat ID) |

### `com.trademesh.common`

**Shared Utilities** — Exception handling, DTOs, resilience patterns.

#### `com.trademesh.common.exception`

| Class | Responsibility |
|-------|-----------------|
| `TradeMeshException` | Base exception for all TradeMesh errors |
| `DatabaseException` | Database operation failures |
| `ExecutionException` | Trade execution failures |
| `RiskException` | Risk limit violations |
| `CacheException` | Redis/cache operation failures |
| `ApiException` | External API failures (Zerodha, Telegram) |

#### `com.trademesh.common.dto`

| Class | Responsibility |
|-------|-----------------|
| `SuccessResponse<T>` | Generic success response wrapper |
| `ErrorResponse` | Error response with details |
| `ErrorDetail` | Individual error detail |

#### `com.trademesh.common.handler`

| Class | Responsibility |
|-------|-----------------|
| `GlobalExceptionHandler` | Spring exception handler for REST endpoints |

#### `com.trademesh.common.resilience`

| Class | Responsibility |
|-------|-----------------|
| `CircuitBreaker` | Circuit breaker pattern implementation |
| `DeadLetterQueue` | Failed message queue for retry logic |

---

## Database Schema

All tables are in the `trade_mesh` schema. Schema changes are managed via **Liquibase** changesets.

### Core Tables

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `zerodha_sessions` | OAuth2 sessions | user_id, access_token, expires_at |
| `instruments` | Master instrument list | instrument_token, tradingsymbol, strike, expiry |
| `strategies` | Strategy definitions | name, is_active, config_json |
| `orders` | Order records | zerodha_order_id, tradingsymbol, status |
| `portfolio_positions` | Current positions | tradingsymbol, quantity, average_price, pnl |

### Trading Tables

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `paper_trades` | Paper trading records | id (UUID), symbol, strategy_name, status, entry_credit, realized_pnl |
| `strategy_execution_logs` | Strategy execution events | strategy_name, symbol, action (ENTRY/EXIT), timestamp |

### Intelligence Tables

| Table | Purpose | Key Columns |
|-------|---------|-------------|
| `nifty_daily_prices` | NIFTY50 OHLC | trade_date (unique), open, high, low, close, volume |
| `vix_history` | VIX snapshots | vix_value, vix_level, recorded_at |
| `vix_alerts` | VIX threshold alerts | alert_message, triggered_at |
| `iv_history` | Daily ATM IV | symbol, trade_date (unique), atm_iv, call_iv, put_iv, spot_price |
| `oi_snapshots` | OI analysis snapshots | symbol, max_call_oi_strike, max_put_oi_strike, recorded_at |
| `max_pain_history` | Max pain snapshots | symbol, call_max_pain, put_max_pain, recorded_at |
| `option_chain_snapshots` | Full option chain snapshots | symbol, expiry_date, data (JSONB), created_at |

---

## Scheduled Jobs

All times are in **IST (Asia/Kolkata)** timezone. Market hours: **9:15 AM – 3:30 PM, Monday–Friday**.

### Trading Cycle

| Job | Cron | Frequency | Symbols | Action |
|-----|------|-----------|---------|--------|
| **Expiry Day Entry** | `0 30 9 * * TUE` | Tuesday 9:30 AM | NIFTY50, NIFTYBANK | Run EXPIRY_DAY_STRANGLE strategy |
| **Iron Condor Entry** | `0 30 9 * * MON,WED,THU,FRI` | Mon/Wed/Thu/Fri 9:30 AM | NIFTY50 | Run IRON_CONDOR strategy |
| **Expiry Day Exit** | `0 15 15 * * TUE` | Tuesday 3:15 PM | NIFTY50, NIFTYBANK | Force close all open positions |
| **Iron Condor Exit** | `0 15 15 * * MON,WED,THU,FRI` | Mon/Wed/Thu/Fri 3:15 PM | NIFTY50 | Force close all open positions |
| **Daily Report (Expiry)** | `0 45 15 * * TUE` | Tuesday 3:45 PM | — | Generate & send daily P&L report |
| **Daily Report (Regular)** | `0 45 15 * * MON,WED,THU,FRI` | Mon/Wed/Thu/Fri 3:45 PM | — | Generate & send daily P&L report |

### Market Intelligence

| Job | Cron | Frequency | Action |
|-----|------|-----------|--------|
| **Market Intelligence Refresh** | `0 */5 9-15 ? * MON-FRI` | Every 5 min (9 AM–3 PM) | Refresh VIX, OI, max pain, market condition |
| **VIX Fetch** | `0 */5 9-15 ? * MON-FRI` | Every 5 min (9 AM–3 PM) | Fetch current VIX, check threshold crossings |
| **VIX Persist** | `0 */15 9-15 ? * MON-FRI` | Every 15 min (9 AM–3 PM) | Save VIX snapshot to database |
| **VIX Statistics** | Fixed rate 30 min | Every 30 min | Log VIX statistics, persistence tracking |
| **ATM IV Capture** | `0 25 9 * * MON-FRI` | 9:25 AM (market open) | Capture ATM IV for NIFTY50 |
| **OI Analysis** | `0 */15 9-15 ? * MON-FRI` | Every 15 min (9 AM–3 PM) | Analyze OI walls, persist snapshots |
| **Max Pain** | `0 */30 9-15 ? * MON-FRI` | Every 30 min (9 AM–3 PM) | Calculate max pain, persist snapshots |
| **Option Chain Snapshots** | Fixed rate 5 min | Every 5 min | Capture full option chain snapshots |

### Position Management

| Job | Cron | Frequency | Action |
|-----|------|-----------|--------|
| **Mark-to-Market** | Fixed rate 60 sec | Every 60 sec (during market hours) | Update open trade values, check SL/TP |

### Authentication & Token Management

| Job | Cron | Frequency | Action |
|-----|------|-----------|--------|
| **Token Health Check** | `0 0 8 * * *` | Daily 8:00 AM | Verify token is present in Redis |
| **Pre-Market Token Check** | `0 0 9 * * MON-FRI` | Weekdays 9:00 AM | Final token check before market open |
| **Token Expiry Watcher** | Fixed rate 5 min | Every 5 min | Monitor token expiry, send alerts |
| **KiteTicker Health Check** | Fixed delay 30 sec | Every 30 sec | Check WebSocket connection, reconnect if needed |

---

## REST API Endpoints

All endpoints return JSON. Base URL: `http://localhost:8080`

### Authentication (`/zerodha`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/zerodha/login` | Redirect to Kite Connect login page |
| `GET` | `/zerodha/callback?request_token=xxx` | OAuth2 callback, exchange token |
| `GET` | `/zerodha/token/status` | Check if valid token is in Redis |

### Market Data (`/market`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/market/price/{symbol}` | Get latest cached price (Redis) |
| `GET` | `/market/stream/{symbol}` | Server-Sent Events live price stream |
| `GET` | `/market/status` | KiteTicker WebSocket connection status |

### Market Intelligence (`/intelligence`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/intelligence/{symbol}` | Complete market intelligence (VIX, OI, max pain, condition) |
| `GET` | `/intelligence/{symbol}/condition` | Market condition only |
| `GET` | `/intelligence/{symbol}/summary` | One-line intelligence summary |
| `GET` | `/intelligence/metrics/{symbol}` | Market metrics (HV20, ATR14, IVR, IV/HV, PCR) |
| `GET` | `/intelligence/vix` | Current VIX data |
| `GET` | `/intelligence/maxpain` | Max pain data |
| `GET` | `/intelligence/oi` | Open interest analysis |
| `GET` | `/intelligence/optionchain` | Option chain data |

### Strategy (`/strategy`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/strategy/evaluate/{symbol}` | Evaluate strategies for symbol |
| `GET` | `/strategy/applicable/{symbol}` | Get applicable strategies for current condition |
| `GET` | `/strategy/status` | Status of all available strategies |

### Trading (`/trading`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/trading/expiry-day/trigger` | Manually trigger expiry day entry |
| `POST` | `/trading/run/{symbol}` | Manually trigger trading cycle for symbol |
| `GET` | `/trading/positions/open` | Get all currently open positions |
| `POST` | `/trading/monitor` | Manually trigger position monitoring |
| `GET` | `/trading/report/today` | Today's trading summary and report |

### Paper Trading (`/paper/trades`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/paper/trades/open` | All currently open paper trades |
| `GET` | `/paper/trades/history` | All closed paper trades (history) |
| `GET` | `/paper/trades/{id}` | Specific paper trade by ID |
| `GET` | `/paper/trades/symbol/{symbol}` | All trades for a symbol |
| `GET` | `/paper/trades/strategy/{strategyName}` | All trades for a strategy |
| `GET` | `/paper/trades/pnl/weekly` | Weekly P&L summary with daily breakdown |
| `GET` | `/paper/trades/stats` | Overall paper trading statistics |
| `POST` | `/paper/trades/{id}/close` | Manually close a paper trade |

### Admin (`/admin`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `POST` | `/admin/bootstrap/historical-prices?from=YYYY-MM-DD&to=YYYY-MM-DD` | Bootstrap historical NIFTY50 prices |
| `GET` | `/admin/test/atm-iv?symbol=NIFTY50` | Test ATM IV calculation (no DB save) |
| `GET` | `/admin/test/iron-condor-evaluate` | Test Iron Condor evaluation (no DB save) |

---

## Trading Strategies

### 1. EXPIRY_DAY_STRANGLE

**Status:** ✅ **ENABLED**

**Concept:** Sells both call and put options at OI walls on Thursday expiry day for maximum theta decay with zero overnight risk.

**Entry Conditions:**
- Day: **Tuesday** (NSE weekly expiry)
- Time: **9:30 AM IST**
- Market Condition: **EXPIRY_DAY**
- VIX Range: **10.0 – 18.0**
- Entry Deadline: **12:30 PM**

**Strike Selection:**
- **Call Strike:** Highest OI call strike (adjusted if too close to spot)
- **Put Strike:** Highest OI put strike (adjusted if too close to spot)
- **Minimum Distance from Spot:** 100 points (NIFTY50), 50 points (NIFTYBANK)

**Exit Conditions:**
- **Time:** **3:15 PM IST** (forced close before market close)
- **Profit Target:** 50% of credit collected
- **Stop Loss:** 100% of credit collected

**Configuration:**
```yaml
EXPIRY_DAY_STRANGLE:
  enabled: true
  allowed-days: [TUESDAY]
  applicable-conditions: [EXPIRY_DAY]
  max-vix: 18.0
  min-vix: 10.0
  latest-entry-time: "12:30"
  min-credit: 20.0
  min-distance-from-spot: 100
  max-lots: 1
  stop-loss-percent: 100.0
  target-profit-percent: 50.0
```

---

### 2. IRON CONDOR

**Status:** ✅ **ENABLED**

**Concept:** Sells both call and put spreads in sideways markets for theta decay. Uses delta-based strike selection (target delta = 0.18) for consistent OTM positioning.

**Entry Conditions:**
- **Days:** Monday, Wednesday, Thursday, Friday (NOT Tuesday — expiry day)
- **Time:** **9:30 AM IST**
- **Market Condition:** **SIDEWAYS**
- **VIX Range:** **12.0 – 20.0**
- **Entry Deadline:** **1:00 PM**

**Confidence Score Calculation:**
- **IVR (IV Rank):** 20–50% = 20 pts, 50–65% = 30 pts, 65–80% = 15 pts
- **IV/HV Ratio:** ≥1.3 = 20 pts, ≥1.1 = 10 pts
- **ATR%:** <0.6 = 15 pts, <0.8 = 20 pts, <1.0 = 10 pts, >1.0 = -20 pts
- **VIX:** 12–16 = 15 pts, 16–20 = 5 pts, >20 = -50 pts
- **PCR:** 0.8–1.2 = 15 pts
- **Minimum Score:** 60 points

**Strike Selection (Delta-Based):**
1. Find short call strike: first strike above spot with delta ≤ 0.18
2. Find short put strike: first strike below spot with delta ≤ 0.18
3. Long call strike = short call + wing width (200 points)
4. Long put strike = short put − wing width (200 points)

**Exit Levels (Adjusted by ATR%):**
- **Calm Market (ATR% < 0.7):** Target = 45% of credit, SL = 80% of credit
- **Normal Market (0.7 ≤ ATR% ≤ 1.0):** Target = 50% of credit, SL = 100% of credit
- **High Volatility (ATR% > 1.0):** Target = 55% of credit, SL = 120% of credit

**Hard Gates:**
- VIX ≤ 20.0
- ATR% ≤ 1.0
- Confidence Score ≥ 60
- DTE: 1–15 days
- Net Credit ≥ 15% of wing width

**Exit Conditions:**
- **Time:** **3:15 PM IST** (forced close)
- **Profit Target:** Adjusted based on ATR%
- **Stop Loss:** Adjusted based on ATR%
- **Force Close Time:** **3:15 PM IST**

**Configuration:**
```yaml
IRON_CONDOR:
  enabled: true
  allowed-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
  applicable-conditions: [SIDEWAYS]
  max-vix: 20.0
  min-vix: 12.0
  latest-entry-time: "13:00"
  min-credit: 30.0
  max-lots: 1
  stop-loss-percent: 200.0
  target-profit-percent: 50.0
  extra:
    target-delta: 0.18
    wing-width: 200
    strike-step: 50
    min-credit-pct: 0.15
    min-score: 60
    ivr-min: 20.0
    ivr-max: 80.0
    iv-hv-ratio-min: 1.1
    atr-pct-max: 1.0
    dte-min: 1
    dte-max: 15
    atr-calm-threshold: 0.7
    atr-high-threshold: 1.0
    calm-target-multiplier: 0.9
    calm-sl-multiplier: 0.8
    high-target-multiplier: 1.1
    high-sl-multiplier: 1.2
    vix-panic-threshold: 25.0
    force-close-time: "15:15"
```

---

### 3. BULL PUT SPREAD

**Status:** ✅ **ENABLED**

**Concept:** Sells OTM put spread in bullish markets to collect premium. Profits when market stays above short put strike.

**Entry Conditions:**
- **Days:** Monday, Wednesday, Thursday, Friday
- **Time:** **9:30 AM IST**
- **Market Condition:** **TRENDING_BULLISH**
- **VIX Range:** **10.0 – 20.0**
- **Entry Deadline:** **1:00 PM**

**Strike Selection (Delta-Based):**
1. Find short put strike: first strike below spot with delta ≤ 0.30
2. Long put strike = short put − wing width (100 points)

**Exit Conditions:**
- **Time:** **3:15 PM IST** (forced close)
- **Profit Target:** 50% of credit collected
- **Stop Loss:** 200% of credit collected

**Configuration:**
```yaml
BULL_PUT_SPREAD:
  enabled: true
  allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
  applicable-conditions: [TRENDING_BULLISH]
  max-vix: 20.0
  min-vix: 10.0
  latest-entry-time: "13:00"
  min-credit: 20.0
  max-lots: 1
  stop-loss-percent: 200.0
  target-profit-percent: 50.0
  extra:
    target-delta: 0.30
    wing-width: 100
    strike-step: 50
    min-credit-pct: 0.10
```

---

### 4. BEAR CALL SPREAD

**Status:** ✅ **ENABLED**

**Concept:** Sells OTM call spread in bearish markets to collect premium. Profits when market stays below short call strike.

**Entry Conditions:**
- **Days:** Monday, Wednesday, Thursday, Friday
- **Time:** **9:30 AM IST**
- **Market Condition:** **TRENDING_BEARISH**
- **VIX Range:** **10.0 – 20.0**
- **Entry Deadline:** **1:00 PM**

**Strike Selection (Delta-Based):**
1. Find short call strike: first strike above spot with delta ≤ 0.30
2. Long call strike = short call + wing width (100 points)

**Exit Conditions:**
- **Time:** **3:15 PM IST** (forced close)
- **Profit Target:** 50% of credit collected
- **Stop Loss:** 200% of credit collected

**Configuration:**
```yaml
BEAR_CALL_SPREAD:
  enabled: true
  allowed-days: [MONDAY, WEDNESDAY, THURSDAY, FRIDAY]
  applicable-conditions: [TRENDING_BEARISH]
  max-vix: 20.0
  min-vix: 10.0
  latest-entry-time: "13:00"
  min-credit: 20.0
  max-lots: 1
  stop-loss-percent: 200.0
  target-profit-percent: 50.0
  extra:
    target-delta: 0.30
    wing-width: 100
    strike-step: 50
    min-credit-pct: 0.10
```

---

### Disabled Strategies

| Strategy | Status | Reason |
|----------|--------|--------|
| (None) | ✅ All Enabled | All strategies are now implemented and enabled |

---

## Configuration

### `application.yml` Structure

#### Server
```yaml
server:
  port: 8080
```

#### Spring Core (R2DBC, DataSource, Liquibase, Redis)
```yaml
spring:
  application:
    name: trade-mesh
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/trade_mesh_db?schema=trade_mesh
    username: postgres
    password: postgres
    pool:
      initial-size: 2
      max-size: 10
      max-idle-time: 30m
  datasource:
    url: jdbc:postgresql://localhost:5432/trade_mesh_db
    username: postgres
    password: postgres
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.yaml
  data:
    redis:
      host: localhost
      port: 6379
      password: ""
      timeout: 30s
```

#### Zerodha / Kite Connect
```yaml
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
```

#### Market Intelligence
```yaml
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
```

#### Trading Engine
```yaml
trading:
  mode: paper  # paper | live
  expiry-day: THU
  symbols:
    - NIFTY50
    - NIFTYBANK
  mark-to-market-interval-ms: 60000
  paper:
    enabled: true
    lot-size:
      NIFTY50: 75
      NIFTYBANK: 30
```

#### Strategies
```yaml
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
      min-distance-from-spot: 100
      max-lots: 1
      stop-loss-percent: 100.0
      target-profit-percent: 50.0
    
    - name: IRON_CONDOR
      enabled: true
      allowed-days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
      applicable-conditions: [SIDEWAYS]
      max-vix: 20.0
      min-vix: 12.0
      latest-entry-time: "13:00"
      min-credit: 30.0
      max-lots: 1
      stop-loss-percent: 200.0
      target-profit-percent: 50.0
      extra:
        target-delta: 0.18
        wing-width: 200
        # ... (see full config above)
```

#### Risk Management
```yaml
risk:
  max-position-size-per-symbol: 5
  daily-loss-limit-percent: 2.0
  max-sector-exposure: 50000
  min-margin-buffer-percent: 10
  position-sizing:
    risk-percent: 1.0
    max-quantity: 10
```

#### Logging
```yaml
logging:
  level:
    root: INFO
    com.trademesh: DEBUG
    org.springframework.r2dbc: DEBUG
    reactor.netty: INFO
    io.netty: INFO
    liquibase: INFO
```

---

## Architecture Patterns

### 1. Reactive (Spring WebFlux)

**All I/O is non-blocking:**
- Database queries return `Mono<T>` or `Flux<T>`
- HTTP requests use `WebClient` (reactive)
- Redis operations are reactive
- Schedulers use `Schedulers.boundedElastic()` for blocking operations

**Example:**
```java
return marketMetricsService.calculateMetrics("NIFTY50")
    .flatMap(metrics -> optionChainService.fetchOptionChain("NIFTY50"))
    .map(chain -> buildSignal(metrics, chain))
    .doOnSuccess(signal -> log.info("Signal generated"))
    .onErrorResume(e -> Mono.just(defaultSignal));
```

### 2. Service Layer Pattern

**Two method variants for data that needs persistence:**

```java
// READ method (REST-facing, no save)
public Mono<MarketMetrics> calculateMetrics(String symbol) {
    // Fetch data, calculate, return
}

// SAVE method (scheduler-facing, calls read then saves)
public Mono<MarketMetrics> calculateAndSaveMetrics(String symbol) {
    return calculateMetrics(symbol)
        .flatMap(metrics -> repository.save(metrics));
}
```

### 3. REST Endpoints are Read-Only

**Controllers never write to the database:**
```java
@RestController
@RequestMapping("/trading")
public class TradingController {
    @GetMapping("/positions/open")
    public Mono<ResponseEntity<List<PaperTrade>>> getOpenPositions() {
        // READ ONLY — no DB writes
        return repository.findByStatus(TradeStatus.OPEN).collectList();
    }
}
```

**All writes happen through schedulers:**
```java
@Component
public class TradingScheduler {
    @Scheduled(cron = "0 30 9 * * TUE")
    public void scheduledEntry() {
        // Calls service.save() which writes to DB
        tradingOrchestrator.runTradingCycle("NIFTY50")
            .subscribe();
    }
}
```

### 4. Entity Pattern (R2DBC)

**Entities implement `Persistable<ID>`:**
```java
@Table("paper_trades")
public class PaperTrade implements Persistable<UUID> {
    @Id
    private UUID id;
    
    private String symbol;
    private TradeStatus status;
    
    @Override
    public UUID getId() { return id; }
    
    @Override
    public boolean isNew() { return id == null; }
}
```

### 5. Scheduler/Service/Controller Separation

```
┌─────────────────────────────────────────┐
│         REST Controller                  │
│  (Read-only, no DB writes)              │
│  GET /trading/positions/open            │
└──────────────┬──────────────────────────┘
               │
               ▼
        ┌──────────────┐
        │   Service    │
        │  (Read only) │
        └──────────────┘
               │
               ▼
        ┌──────────────┐
        │ Repository   │
        │  (Read only) │
        └──────────────┘

┌─────────────────────────────────────────┐
│         Scheduler                        │
│  (Automated, DB writes)                 │
│  @Scheduled(cron = "...")               │
└──────────────┬──────────────────────────┘
               │
               ▼
        ┌──────────────┐
        │   Service    │
        │  (Save mode) │
        └──────────────┘
               │
               ▼
        ┌──────────────┐
        │ Repository   │
        │  (Write)     │
        └──────────────┘
```

### 6. Structured Logging

**No `System.out.println`, use SLF4J:**
```java
@Slf4j
public class TradingScheduler {
    public void scheduledEntry() {
        log.info("🚀 Starting trading cycle entry");
        log.debug("Market condition: {}", condition);
        log.warn("VIX exceeds threshold: {}", vix);
        log.error("Trade execution failed: {}", e.getMessage(), e);
    }
}
```

---

## Data Pipelines

### 1. Nifty Daily Prices Pipeline

**Purpose:** Maintain historical NIFTY50 OHLC data for HV20 calculation.

**Flow:**
```
┌─────────────────────────────────────────────────────────────┐
│ 1. Bootstrap (Manual)                                       │
│    POST /admin/bootstrap/historical-prices?from=...&to=... │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 2. KiteHistoricalService.fetchHistoricalOHLC()             │
│    Fetches OHLC from Kite API (1-minute candles)           │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 3. NiftyDailyPriceService.saveIfNotExists()                │
│    Saves to nifty_daily_prices table (idempotent)          │
└──────────────┬──────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 4. MarketMetricsService.calculateMetrics()                 │
│    Fetches last 21 days, calculates HV20, ATR14            │
└──────────────────────────────────────────────────────────────┘
```

**Data Retention:** All historical data retained indefinitely.

---

### 2. IV History Pipeline

**Purpose:** Track daily ATM implied volatility for Iron Condor strategy.

**Flow:**
```
┌──────────────────────────────────────────────────────────────┐
│ 1. IvHistoryScheduler.captureAtmIv()                        │
│    Runs at 9:25 AM IST (market open)                        │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 2. IvHistoryService.calculateAtmIv()                        │
│    Fetches option chain, finds ATM strike                   │
│    Calculates IV using Black-Scholes                        │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 3. IvHistoryService.calculateAndSaveAtmIv()                │
│    Saves to iv_history table (symbol, trade_date unique)   │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 4. MarketMetricsService.calculateMetrics()                 │
│    Fetches last 365 days of IV history                      │
│    Calculates IVR (IV Rank)                                 │
└──────────────────────────────────────────────────────────────┘
```

**Data Retention:** Last 365 days for IVR calculation.

---

### 3. MarketMetrics Calculation Flow

**Purpose:** Calculate comprehensive market metrics for strategy evaluation.

**Flow:**
```
┌──────────────────────────────────────────────────────────────┐
│ MarketMetricsService.calculateMetrics(symbol)              │
└──────────────┬───────────────────────────────────────────────┘
               │
        ┌──────┴──────┬──────────┬──────────┬──────────┐
        │             │          │          │          │
        ▼             ▼          ▼          ▼          ▼
   Last 21 Days  Last 60 Days  IV History Current IV  Option Chain
   (HV20, ATR14) (ATR%)        (IVR)      (ATM IV)    (PCR)
        │             │          │          │          │
        └──────┬──────┴──────────┴──────────┴──────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ buildMetrics()                                              │
│ - HV20 = daily std dev * sqrt(252) * 100                   │
│ - ATR14 = average of last 14 true ranges                   │
│ - ATR% = ATR14 / spot price * 100                          │
│ - IVR = (current IV - min IV) / (max IV - min IV) * 100    │
│ - IV/HV = current IV / HV20                                │
│ - PCR = total put OI / total call OI                       │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ Return MarketMetrics                                        │
│ (HV20, ATR14, IVR, IV/HV, PCR, ATR%, spot price)          │
└──────────────────────────────────────────────────────────────┘
```

**Calculation Details:**

**HV20 (20-day Historical Volatility):**
```
1. Calculate 20 log returns: ln(close[i] / close[i-1])
2. Calculate mean of log returns
3. Calculate sample variance (divide by 19)
4. Daily std dev = sqrt(variance)
5. HV20 = daily std dev * sqrt(252) * 100
```

**ATR14 (14-day Average True Range):**
```
1. Calculate True Range for each day:
   TR = max(high - low, abs(high - prev_close), abs(low - prev_close))
2. ATR14 = average of last 14 TR values
```

**IVR (IV Rank):**
```
IVR = (current IV - min IV over 365 days) / (max IV - min IV) * 100
Range: 0–100 (0 = lowest IV, 100 = highest IV)
```

**IV/HV Ratio:**
```
IV/HV = current ATM IV / HV20
Interpretation:
  < 1.0 = IV lower than historical (underpriced)
  > 1.0 = IV higher than historical (overpriced)
  > 1.3 = significantly overpriced (good for selling)
```

**PCR (Put-Call Ratio):**
```
PCR = total put OI / total call OI
Range: 0.5–2.0 (balanced: 0.8–1.2)
  < 0.8 = bullish sentiment
  > 1.2 = bearish sentiment
```

---

### 4. Iron Condor Pipeline

**Purpose:** Complete flow from market data to trade execution.

**Flow:**
```
┌──────────────────────────────────────────────────────────────┐
│ 1. TradingScheduler.scheduledIronCondorEntry()             │
│    Cron: 0 30 9 * * MON,WED,THU,FRI (9:30 AM)             │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 2. TradingOrchestrator.runTradingCycle("NIFTY50")          │
│    Orchestrates complete trading cycle                      │
└──────────────┬───────────────────────────────────────────────┘
               │
        ┌──────┴──────────────────────────────────────┐
        │                                              │
        ▼                                              ▼
┌──────────────────────────────┐  ┌──────────────────────────────┐
│ 3a. Generate Market          │  │ 3b. Fetch Current VIX        │
│     Intelligence             │  │     (hard gate: VIX ≤ 20)    │
│ - Fetch option chain         │  │                              │
│ - Analyze OI walls           │  │                              │
│ - Calculate max pain         │  │                              │
│ - Classify market condition  │  │                              │
└──────────────┬───────────────┘  └──────────────┬───────────────┘
               │                                  │
               └──────────────┬───────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────┐
│ 4. MarketMetricsService.calculateMetrics()                 │
│    Calculate HV20, ATR14, IVR, IV/HV, PCR                  │
│    (hard gate: ATR% ≤ 1.0)                                 │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 5. IronCondorStrategy.calculateConfidenceScore()           │
│    Score: IVR (20 pts) + IV/HV (20 pts) + ATR% (20 pts)   │
│           + VIX (15 pts) + PCR (15 pts) = max 100 pts      │
│    (hard gate: score ≥ 60)                                 │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 6. OptionChainService.fetchOptionChain()                   │
│    Fetch current option chain from Kite API                │
│    (hard gate: DTE 1–15 days)                              │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 7. IronCondorStrategy.selectStrikesAndBuildSignal()        │
│    - Find short call: first strike above spot, delta ≤ 0.18│
│    - Find short put: first strike below spot, delta ≤ 0.18 │
│    - Long call = short call + 200 points                   │
│    - Long put = short put − 200 points                     │
│    - Calculate net credit                                  │
│    - Validate credit ≥ 15% of wing width                  │
│    - Calculate P&L matrix (max profit, max loss, BE)       │
│    - Adjust exit levels based on ATR%                      │
│    (hard gate: credit ≥ 30 points)                         │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 8. StrategyOrchestrator.orchestrate()                      │
│    Returns StrategyResult with signal                       │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 9. TradingOrchestrator.executeStrategy()                   │
│    If executable:                                           │
│    - Create paper trade record                             │
│    - Place orders (paper or live)                          │
│    - Log execution event                                   │
│    - Send Telegram alert                                  │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 10. MarkToMarketScheduler (every 60 sec)                   │
│     - Fetch current prices                                 │
│     - Update trade values                                  │
│     - Check SL/TP triggers                                 │
│     - Close if triggered                                   │
└──────────────┬───────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────┐
│ 11. TradingScheduler.scheduledIronCondorExit()             │
│     Cron: 0 15 15 * * MON,WED,THU,FRI (3:15 PM)           │
│     Force close all open positions                         │
└──────────────────────────────────────────────────────────────┘
```

**Hard Gates (Must Pass):**
1. ✅ VIX ≤ 20.0
2. ✅ ATR% ≤ 1.0
3. ✅ Confidence Score ≥ 60
4. ✅ DTE: 1–15 days
5. ✅ Net Credit ≥ 30 points

**Confidence Score Breakdown:**
- **IVR:** 20–50% = 20 pts, 50–65% = 30 pts, 65–80% = 15 pts
- **IV/HV:** ≥1.3 = 20 pts, ≥1.1 = 10 pts
- **ATR%:** <0.6 = 15 pts, <0.8 = 20 pts, <1.0 = 10 pts, >1.0 = -20 pts
- **VIX:** 12–16 = 15 pts, 16–20 = 5 pts, >20 = -50 pts
- **PCR:** 0.8–1.2 = 15 pts

**Exit Level Adjustment (Based on ATR%):**
- **Calm (ATR% < 0.7):** Target = 45% of credit, SL = 80% of credit
- **Normal (0.7 ≤ ATR% ≤ 1.0):** Target = 50% of credit, SL = 100% of credit
- **High Vol (ATR% > 1.0):** Target = 55% of credit, SL = 120% of credit

---

## Development & Testing

### Running Tests

```bash
# Unit tests
./gradlew test

# Integration tests
./gradlew integrationTest

# All tests
./gradlew test integrationTest
```

### Debug Endpoints

```bash
# Test ATM IV calculation (no DB save)
curl http://localhost:8080/admin/test/atm-iv?symbol=NIFTY50

# Test Iron Condor evaluation (no DB save)
curl http://localhost:8080/admin/test/iron-condor-evaluate

# Bootstrap historical prices
curl -X POST "http://localhost:8080/admin/bootstrap/historical-prices?from=2024-01-01&to=2024-12-31"
```

### Swagger UI

Access API documentation at: `http://localhost:8080/swagger-ui.html`

---

## Troubleshooting

### Token Expiry

**Symptom:** All API calls fail with "No Zerodha access token"

**Solution:**
1. Check token status: `GET /zerodha/token/status`
2. Re-authenticate: `GET /zerodha/login`
3. Check Redis: `redis-cli get zerodha:access_token`

### WebSocket Disconnection

**Symptom:** Live prices not updating, `/market/price/{symbol}` returns 404

**Solution:**
1. Check connection: `GET /market/status`
2. Check logs for "KiteTicker WebSocket is DISCONNECTED"
3. Verify token is valid
4. Check network connectivity to `wss://ws.kite.trade`

### Database Connection Issues

**Symptom:** "Connection refused" errors

**Solution:**
1. Verify PostgreSQL is running: `psql -U postgres -d trade_mesh_db`
2. Check R2DBC URL in `application.yml`
3. Verify database exists: `createdb trade_mesh_db`
4. Run Liquibase migrations: `./gradlew liquibaseUpdate`

### Redis Connection Issues

**Symptom:** "Redis connection refused"

**Solution:**
1. Verify Redis is running: `redis-cli ping`
2. Check Redis host/port in `application.yml`
3. Verify Redis password (if set)

---

## License

TradeMesh is proprietary software. All rights reserved.

---

## Support

For issues, questions, or feature requests, contact the development team.

**Last Updated:** June 29, 2026
