# TradeMesh — Project Context

**Last Updated:** June 29, 2026  
**Version:** 0.0.1-SNAPSHOT  
**Market:** NSE (India) | **Timezone:** IST (Asia/Kolkata)

---

## TradeMesh Overview

**Purpose:** TradeMesh is an automated options trading platform for NSE (National Stock Exchange) that executes multi-leg options strategies (Iron Condor, Bull Put Spread, Bear Call Spread, Expiry Day Strangle) based on real-time market intelligence and risk management rules.

**Current Vision:** Build a production-ready, reactive trading system that:
- Monitors market conditions (VIX, volatility, open interest) continuously
- Evaluates trading strategies based on market intelligence
- Executes trades through Zerodha's Kite Connect API
- Manages positions with real-time mark-to-market and risk controls
- Provides paper trading simulation before live execution
- Sends real-time alerts via Telegram

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

## Current Architecture

### High-Level Flow

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

### Major Modules

| Module | Responsibility |
|--------|-----------------|
| **Zerodha Integration** | OAuth2 authentication, WebSocket market data (KiteTicker), REST API for option chains, order execution |
| **Market Intelligence** | VIX monitoring, OI analysis, max pain calculation, market metrics (HV20, ATR14, IVR, IV/HV, PCR), market condition classification |
| **Strategy Engine** | Strategy selection, evaluation pipeline, multi-leg signal generation |
| **Trading Orchestration** | Scheduled entry/exit, trading cycle coordination, daily reporting |
| **Execution** | Paper trading simulation, mark-to-market calculations, position tracking |
| **Risk Management** | Position limits, daily loss limits, margin checks |
| **Portfolio Management** | Position aggregation, P&L tracking |
| **Alerting** | Telegram notifications for entries, exits, errors, VIX alerts |

### Architectural Principles

1. **Reactive (Spring WebFlux)** — Non-blocking I/O throughout
2. **Read/Write Separation** — REST controllers read-only; schedulers handle all DB writes
3. **Service Layer Pattern** — Services expose read and save method variants
4. **Event-Driven** — Market intelligence refreshes every 5 minutes; trades execute at scheduled times
5. **Structured Logging** — SLF4J only; no System.out.println
6. **Schema Versioning** — All DB changes via Liquibase changesets

---

## Implemented Features

### ✅ Core Trading Strategies

| Strategy | Status | Entry | Exit | Symbols |
|----------|--------|-------|------|---------|
| **Iron Condor** | ✅ Enabled | 9:30 AM (Mon/Wed/Thu/Fri) | 3:15 PM | NIFTY50 |
| **Bull Put Spread** | ✅ Enabled | 9:30 AM (Mon/Wed/Thu/Fri) | 3:15 PM | NIFTY50, NIFTYBANK |
| **Bear Call Spread** | ✅ Enabled | 9:30 AM (Mon/Wed/Thu/Fri) | 3:15 PM | NIFTY50, NIFTYBANK |
| **Expiry Day Strangle** | ✅ Enabled | 9:30 AM (Tuesday) | 3:15 PM | NIFTY50, NIFTYBANK |

### ✅ Market Intelligence

- **VIX Monitoring** — Real-time VIX fetch, threshold alerts, 15-min persistence
- **Open Interest Analysis** — OI wall detection, market bias classification
- **Max Pain Calculation** — Theoretical max pain snapshots every 30 minutes
- **Market Metrics** — HV20, ATR14, IVR, IV/HV ratio, PCR calculations
- **Market Condition Classification** — SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE
- **ATM IV Tracking** — Daily ATM IV capture at market open

### ✅ Execution & Position Management

- **Paper Trading Engine** — Full trade simulation with mark-to-market
- **Position Monitoring** — Real-time P&L updates every 60 seconds
- **Stop-Loss & Take-Profit** — Automatic exit on SL/TP triggers
- **Risk Management** — Position limits, daily loss limits, margin checks
- **Portfolio Tracking** — Aggregated P&L, position summaries

### ✅ Zerodha Integration

- **OAuth2 Authentication** — Token exchange, Redis storage, expiry monitoring
- **WebSocket Market Data** — KiteTicker for live price streaming
- **Option Chain Fetching** — REST API integration with caching
- **Order Execution** — Order placement via Kite API (paper mode active)

### ✅ REST API

- **Market Data** — `/market/price/{symbol}`, `/market/stream/{symbol}`, `/market/status`
- **Market Intelligence** — `/intelligence/{symbol}`, `/intelligence/vix`, `/intelligence/oi`, `/intelligence/maxpain`
- **Strategy** — `/strategy/evaluate/{symbol}`, `/strategy/applicable/{symbol}`, `/strategy/status`
- **Trading** — `/trading/positions/open`, `/trading/report/today`, `/trading/run/{symbol}`
- **Paper Trades** — `/paper/trades/open`, `/paper/trades/history`, `/paper/trades/pnl/weekly`, `/paper/trades/stats`
- **Admin** — `/admin/bootstrap/historical-prices`, `/admin/test/iron-condor-evaluate`

### ✅ Alerting & Reporting

- **Telegram Notifications** — Entry signals, exit signals, system errors, VIX alerts
- **Daily Reports** — P&L summary, win rate, average profit/loss

---

## In Progress

None currently. All major features are implemented and enabled.

---

## Planned Features

Based on codebase analysis, the following features are clearly planned but not yet implemented:

1. **Live Trading Mode** — Currently in paper trading mode; live execution via Zerodha pending
2. **Advanced Risk Controls** — Sector exposure limits, correlation-based position sizing
3. **Strategy Backtesting** — Historical performance analysis framework
4. **Multi-Account Support** — Currently single-user; multi-account architecture planned
5. **Advanced Alerting** — SMS alerts, email reports, webhook integrations
6. **Performance Analytics** — Detailed strategy performance metrics, win/loss analysis
7. **Distributed Scheduler Coordination** — Redis-based locking for multi-instance deployments

---

## Public REST APIs

### Authentication
- `GET /zerodha/login` — Redirect to Kite Connect login
- `GET /zerodha/callback?request_token=xxx` — OAuth2 callback
- `GET /zerodha/token/status` — Check token validity

### Market Data
- `GET /market/price/{symbol}` — Latest cached price
- `GET /market/stream/{symbol}` — Server-Sent Events price stream
- `GET /market/status` — WebSocket connection status

### Market Intelligence
- `GET /intelligence/{symbol}` — Complete market intelligence
- `GET /intelligence/{symbol}/condition` — Market condition only
- `GET /intelligence/{symbol}/summary` — One-line summary
- `GET /intelligence/metrics/{symbol}` — Market metrics
- `GET /intelligence/vix` — Current VIX data
- `GET /intelligence/maxpain` — Max pain data
- `GET /intelligence/oi` — Open interest analysis

### Strategy
- `GET /strategy/evaluate/{symbol}` — Evaluate strategies
- `GET /strategy/applicable/{symbol}` — Applicable strategies
- `GET /strategy/status` — All strategy status

### Trading
- `POST /trading/expiry-day/trigger` — Manual expiry day entry
- `POST /trading/run/{symbol}` — Manual trading cycle
- `GET /trading/positions/open` — Open positions
- `GET /trading/report/today` — Daily report

### Paper Trading
- `GET /paper/trades/open` — Open trades
- `GET /paper/trades/history` — Closed trades
- `GET /paper/trades/{id}` — Specific trade
- `GET /paper/trades/symbol/{symbol}` — Trades by symbol
- `GET /paper/trades/strategy/{strategyName}` — Trades by strategy
- `GET /paper/trades/pnl/weekly` — Weekly P&L
- `GET /paper/trades/stats` — Overall statistics
- `POST /paper/trades/{id}/close` — Manual close

### Admin
- `POST /admin/bootstrap/historical-prices?from=YYYY-MM-DD&to=YYYY-MM-DD` — Bootstrap historical data
- `GET /admin/test/atm-iv?symbol=NIFTY50` — Test ATM IV calculation
- `GET /admin/test/iron-condor-evaluate` — Test Iron Condor evaluation

---

## Database

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
| `iv_history` | Daily ATM IV | symbol, trade_date (unique), atm_iv, call_iv, put_iv |
| `oi_snapshots` | OI analysis snapshots | symbol, max_call_oi_strike, max_put_oi_strike, recorded_at |
| `max_pain_history` | Max pain snapshots | symbol, call_max_pain, put_max_pain, recorded_at |
| `option_chain_snapshots` | Full option chain snapshots | symbol, expiry_date, data (JSONB), created_at |

### Schema Management

- **Schema:** `trade_mesh`
- **Migrations:** Liquibase YAML changesets in `src/main/resources/db/changelog/`
- **Current Changesets:** 12 (schema, core tables, paper trades, execution logs, option chains, VIX, OI, max pain, NIFTY daily prices, IV history)

---

## Important Domain Objects

### Core Entities

| Entity | Purpose |
|--------|---------|
| `PaperTrade` | Simulated trade record with legs, P&L, status |
| `TradeSignal` | Strategy output: legs, credit, max loss, confidence |
| `TradeLeg` | Individual leg: instrument, action, strike, quantity |
| `MarketIntelligence` | Aggregated market data: VIX, OI, max pain, condition, safety |
| `MarketMetrics` | Calculated metrics: HV20, ATR14, IVR, IV/HV, PCR |
| `OptionChain` | Option chain data: strikes, call/put prices, Greeks, OI |
| `Instrument` | Zerodha instrument master: token, symbol, strike, expiry |

### Enums

| Enum | Values |
|------|--------|
| `MarketCondition` | SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, EXPIRY_DAY, HIGH_VOLATILITY, NO_TRADE |
| `TradeStatus` | OPEN, CLOSED, CANCELLED |
| `SignalType` | ENTER, EXIT, ADJUST |
| `Confidence` | LOW, MEDIUM, HIGH |
| `VixLevel` | CALM, NORMAL, ELEVATED, PANIC |

### DTOs

- `StrategyResult` — Strategy evaluation result with executable flag, signal, skip reason
- `StrategyConfig` — Strategy configuration from YAML
- `PortfolioSummary` — Aggregated portfolio P&L
- `PositionSummary` — Individual position summary
- `VixData` — Current VIX value, level, change, safe-to-trade flag
- `OIAnalysis` — OI wall analysis with max call/put OI strikes
- `MaxPainResult` — Calculated max pain for call/put sides

---

## Architectural Decisions

### 1. Reactive Architecture (Spring WebFlux)
- **Decision:** Use Spring WebFlux for all HTTP operations
- **Rationale:** Non-blocking I/O enables high concurrency; critical for real-time market data processing
- **Implementation:** R2DBC for reactive database access, Lettuce for reactive Redis

### 2. Read/Write Separation
- **Decision:** REST controllers are read-only; all DB writes happen through schedulers
- **Rationale:** Prevents race conditions, ensures data consistency, simplifies transaction management
- **Implementation:** Services expose read() and save() method variants

### 3. Broker Abstraction
- **Decision:** Zerodha integration isolated in `zerodha` package
- **Rationale:** Enables future broker integrations without affecting core logic
- **Implementation:** `ZerodhaAuthService`, `ZerodhaTickerService`, `OptionChainService`

### 4. Strategy Pattern
- **Decision:** `TradingStrategy` interface with multiple implementations
- **Rationale:** Easy to add new strategies without modifying core logic
- **Implementation:** `IronCondorStrategy`, `BullPutSpreadStrategy`, `BearCallSpreadStrategy`, `ExpiryDayStrategy`

### 5. DTO Usage
- **Decision:** Extensive use of DTOs for data transfer
- **Rationale:** Decouples API contracts from internal entities; enables MapStruct mapping
- **Implementation:** `TradeSignal`, `StrategyResult`, `MarketIntelligence`, etc.

### 6. Repository Pattern
- **Decision:** R2DBC repositories for all data access
- **Rationale:** Reactive, type-safe database access; Spring Data integration
- **Implementation:** `PaperTradeRepository`, `InstrumentRepository`, `VixHistoryRepository`, etc.

### 7. Event-Driven Scheduling
- **Decision:** Scheduled jobs for market intelligence refresh and trade execution
- **Rationale:** Consistent, time-based execution; decoupled from HTTP requests
- **Implementation:** `@Scheduled` methods in `TradingScheduler`, `IntelligenceScheduler`, `VixScheduler`

### 8. Caching Strategy
- **Decision:** Redis for token storage, option chain cache, tick price cache
- **Rationale:** Reduces API calls, improves performance, enables distributed deployments
- **Implementation:** Lettuce reactive client with TTL-based expiration

### 9. Schema Versioning
- **Decision:** All DB changes via Liquibase changesets
- **Rationale:** Version-controlled schema evolution, reproducible deployments
- **Implementation:** YAML changesets in `src/main/resources/db/changelog/`

---

## Current Technical Debt

### 1. Live Trading Mode Not Implemented
- **Status:** Paper trading only; live execution pending
- **Impact:** Cannot execute real trades; testing limited to simulation
- **Effort:** Medium (requires order execution integration, risk validation)

### 2. Single-Instance Scheduler Coordination
- **Status:** No distributed locking for scheduled jobs
- **Impact:** Multi-instance deployments will execute duplicate jobs
- **Effort:** Low (add Redis-based locking to schedulers)

### 3. Limited Error Handling in Zerodha Integration
- **Status:** Basic exception handling; no circuit breaker for API failures
- **Impact:** API failures may cascade; no graceful degradation
- **Effort:** Medium (implement circuit breaker, retry logic)

### 4. Incomplete Test Coverage
- **Status:** Unit tests for business logic; limited integration tests
- **Impact:** Regression risk; difficult to validate end-to-end flows
- **Effort:** High (add integration tests for all major flows)

### 5. No Backtesting Framework
- **Status:** No historical performance analysis
- **Impact:** Cannot validate strategy performance before live trading
- **Effort:** High (requires historical data processing, P&L simulation)

### 6. Tight Coupling in Strategy Evaluation
- **Status:** Strategy logic mixed with market intelligence fetching
- **Impact:** Difficult to test strategies in isolation
- **Effort:** Medium (extract market data fetching to separate layer)

### 7. Limited Monitoring & Observability
- **Status:** Structured logging only; no metrics, tracing, or dashboards
- **Impact:** Difficult to diagnose production issues
- **Effort:** Medium (add Micrometer metrics, distributed tracing)

---

## Next Recommended Milestones

### Phase 1: Stabilization & Testing (2-3 weeks)
1. **Add comprehensive integration tests** for all major flows (strategy evaluation, trade execution, position monitoring)
2. **Implement circuit breaker** for Zerodha API failures
3. **Add distributed scheduler locking** for multi-instance deployments
4. **Validate paper trading accuracy** against live market data

### Phase 2: Live Trading Preparation (3-4 weeks)
1. **Implement live trading mode** with order execution via Zerodha
2. **Add pre-trade risk validation** (position limits, daily loss limits, margin checks)
3. **Implement order tracking & reconciliation** with Zerodha
4. **Add comprehensive audit logging** for all trades

### Phase 3: Monitoring & Observability (2-3 weeks)
1. **Add Micrometer metrics** for strategy performance, execution latency, API response times
2. **Implement distributed tracing** (Spring Cloud Sleuth + Zipkin)
3. **Add health checks** for database, Redis, Zerodha API connectivity
4. **Create operational dashboards** (Grafana)

### Phase 4: Advanced Features (4-6 weeks)
1. **Implement backtesting framework** for historical performance analysis
2. **Add multi-account support** for managing multiple trading accounts
3. **Implement advanced risk controls** (sector exposure, correlation-based sizing)
4. **Add webhook integrations** for external alerts and notifications

---

## Summary

TradeMesh is a **production-ready, reactive trading platform** with four fully implemented options strategies, comprehensive market intelligence, and paper trading simulation. The architecture emphasizes non-blocking I/O, clear separation of concerns, and event-driven execution. Current focus is on stabilization, testing, and preparation for live trading. The codebase is well-structured with clear module boundaries, making it suitable for future enhancements and broker integrations.
