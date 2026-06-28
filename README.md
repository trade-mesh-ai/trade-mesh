# TradeMesh — Automated Options Trading Engine

TradeMesh is a **Spring WebFlux-based reactive trading engine** for automated options trading on the NSE (National Stock Exchange) via Zerodha Kite Connect. It implements sophisticated market intelligence analysis, strategy evaluation, and paper trading simulation with real-time Telegram alerts.

**Key Technologies:**
- Spring WebFlux (reactive, non-blocking)
- R2DBC + PostgreSQL (reactive database access)
- Redis (token storage, price caching, intelligence caching)
- Zerodha Kite Connect API (market data, authentication)
- Telegram Bot API (real-time alerts)
- Liquibase (schema migrations)

---

## 1. Package Structure & Responsibilities

### `com.trademesh` (Root)
**Main application entry point**
- `TradeMeshApplication` — Spring Boot application starter

### `com.trademesh.config`
**Application configuration and converters**
- `ApplicationConstants` — Market symbols, tokens, Redis key prefixes, cache TTLs
- `JacksonConfig` — JSON serialization/deserialization configuration
- `JdbcConfig` — JDBC DataSource for Liquibase (separate from R2DBC)
- `R2dbcConfig` — R2DBC custom converters registration
- `WebClientConfig` — WebClient builder for HTTP calls (Zerodha, Telegram)
- `ResilienceConfig` — Resilience4j circuit breaker and retry policies
- `PaperTradeLegsConverter` — JSONB ↔ List<TradeLeg> converters for R2DBC

### `com.trademesh.zerodha.auth`
**Zerodha OAuth2 authentication and token management**
- `ZerodhaAuthService` — OAuth2 flow: login URL generation, token exchange, Redis storage
- `ZerodhaAuthController` — REST endpoints: `/zerodha/login`, `/zerodha/callback`
- `TokenRefreshScheduler` — Daily token health checks (08:00 AM, 09:00 AM IST)
- `TokenAvailabilityTracker` — Deduplicates token-missing warnings across schedulers
- `TokenExpiryWatcher` — Monitors token expiry and triggers re-auth alerts
- `ZerodhaConfig` — Configuration properties (API key, secret, base URL, TTL)
- `SensitiveDataMasker` — Masks tokens in logs for security

### `com.trademesh.zerodha.ticker`
**Real-time market data via KiteTicker WebSocket**
- `ZerodhaTickerService` — WebSocket connection, subscription, tick processing
- `TickerConnectionManager` — Connection lifecycle management (connect/disconnect/reconnect)
- `MarketDataController` — REST endpoints for tick data queries
- `TickData` — DTO for individual tick (price, volume, timestamp)

### `com.trademesh.zerodha.options`
**Option chain data fetching and caching**
- `OptionChainService` — Fetches option chain from Zerodha, caches in Redis
- `OptionChain` — Complete option chain snapshot (spot price, strikes, expiry)
- `OptionStrike` — Individual strike with call/put LTP, IV, Greeks
- `OptionChainRepository` — R2DBC repository for option_chain_snapshots table

### `com.trademesh.domain`
**Core domain entities**
- `Instrument` — Master list of tradeable instruments (stocks, options, indices)
- `InstrumentRepository` — R2DBC repository for instruments table

### `com.trademesh.intelligence`
**Market intelligence aggregation and condition classification**
- `MarketIntelligenceService` — Aggregates VIX, OI, max pain; classifies market condition; caches in Redis
- `MarketIntelligence` — Complete intelligence snapshot (VIX, OI analysis, max pain, spot price, condition)
- `MarketCondition` — Enum: EXPIRY_DAY, SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH, HIGH_VOLATILITY, NO_TRADE
- `SignalService` — Generates trading signals from market intelligence
- `TradingSignal` — Signal DTO (symbol, strategy, legs, expected credit, confidence)
- `IntelligenceScheduler` — Periodic intelligence generation (every 5 minutes during market hours)
- `MarketIntelligenceController` — REST endpoints for intelligence queries
- `MarketMetrics` — DTO containing calculated volatility and technical indicators (HV20, ATR14, IVR, IV/HV ratio, ATR percentile)
- `MarketMetricsService` — Service for calculating comprehensive market metrics (HV20, ATR14, IVR, IV/HV ratio, ATR percentile)
- `NiftyDailyPriceService` — Service for fetching and managing daily OHLC price data
- `KiteHistoricalService` — Service for fetching historical candle data from Zerodha Kite API

#### `com.trademesh.intelligence.vix`
**VIX (Volatility Index) monitoring**
- `VixMonitorService` — Fetches current VIX from Zerodha, classifies level (LOW, NORMAL, HIGH, EXTREME)
- `VixData` — VIX snapshot (current value, level, change %)
- `VixHistory` — Historical VIX records (persisted to PostgreSQL)
- `VixAlert` — VIX threshold breach alerts (persisted to PostgreSQL)

#### `com.trademesh.intelligence.oi`
**Open Interest (OI) analysis for market bias detection**
- `OIAnalyserService` — Analyzes option chain OI to detect call/put walls and market bias
- `OIAnalysis` — OI analysis result (max call/put strikes, walls, market bias)
- `OISnapshot` — Historical OI snapshots (persisted to PostgreSQL)

#### `com.trademesh.intelligence.maxpain`
**Max Pain calculation for strike prediction**
- `MaxPainCalculatorService` — Calculates max pain strike from option chain
- `MaxPainResult` — Max pain calculation result (strike, distance from spot)
- `MaxPainHistory` — Historical max pain records (persisted to PostgreSQL)

### `com.trademesh.strategy`
**Strategy framework and orchestration**
- `TradingStrategy` — Interface for all trading strategies
- `StrategySelector` — Selects applicable strategy based on market condition
- `StrategyEngine` — Evaluates selected strategy and returns result
- `StrategyOrchestrator` — Orchestrates strategy evaluation and trade execution
- `StrategyResult` — Strategy evaluation result (executable flag, signal, skip reason)
- `TradeSignal` — Trade signal with legs, credit, confidence
- `TradeLeg` — Individual trade leg (strike, action, option type, quantity)
- `StrategyConfig` — Strategy configuration (VIX range, entry time, stop loss %, target %)
- `StrategyController` — REST endpoints for strategy queries
- `StrategyExecutionLog` — Audit log of strategy execution events (persisted to PostgreSQL)

#### `com.trademesh.strategy.expiryday`
**Expiry Day Strangle Strategy (ENABLED)**
- `ExpiryDayStrategy` — Sells both call and put at OI walls on expiry day (Thursday)
  - Safety checks: VIX threshold, OI walls, entry time limit
  - Strike selection from OI analysis
  - Trade leg building with premium calculation
  - Confidence assessment based on credit collected

#### `com.trademesh.strategy.config`
**Strategy configuration management**
- `StrategiesConfig` — Loads all strategies from application.yml
- `StrategyProperties` — Individual strategy config (enabled, allowed days, VIX range, entry time, credit, stop loss %, target %)

#### `com.trademesh.strategy.enums`
**Strategy-related enumerations**
- `Action` — SELL, BUY
- `OptionType` — CE (Call), PE (Put)
- `LegType` — ENTRY, EXIT
- `SignalType` — ENTER, EXIT
- `Confidence` — LOW, MEDIUM, HIGH

### `com.trademesh.execution`
**Paper trading execution and position management**
- `PaperTradingEngine` — Executes trades from signals, marks to market, closes trades
- `PaperTrade` — Entity representing a simulated trade (implements Persistable<UUID>)
  - Tracks entry/exit, legs (JSONB), credit, P&L, status
  - Uses `isNew` flag for R2DBC INSERT vs UPDATE
- `PaperTradeRepository` — R2DBC repository for paper_trades table
- `PaperTradeController` — REST endpoints for trade queries
- `TradeStatus` — Enum: OPEN, CLOSED, CANCELLED
- `MarkToMarketScheduler` — Periodic position valuation (every 60 seconds during market hours)
- `OrderExecutionService` — Placeholder for live order execution (currently paper trading only)

### `com.trademesh.trading`
**Trading orchestration and scheduling**
- `TradingScheduler` — Scheduled tasks for entry, monitoring, exit, reporting
  - Thursday 9:30 AM: Expiry day entry
  - Every 5 minutes (9 AM - 2 PM): Position monitoring
  - Thursday 3:15 PM: Forced position exit
  - Thursday 3:45 PM: Daily P&L report
- `TradingOrchestrator` — Orchestrates trading cycles (intelligence → strategy → execution)
- `TradingController` — REST endpoints for trading operations
- `TradingOrchestrator` — Runs complete trading cycle: intelligence generation → strategy selection → trade execution
- `DailyReportService` — Generates and sends daily P&L reports via Telegram
- `MarketCalendarService` — Checks if market is open (weekdays, 9:15 AM - 3:30 PM IST)
- `StrategyExecutionLog` — Audit log of strategy execution decisions

### `com.trademesh.portfolio`
**Portfolio and position tracking**
- `PortfolioService` — Aggregates current positions and calculates portfolio metrics
- `PortfolioSummary` — Portfolio snapshot (total P&L, exposure, margin utilization)
- `PositionSummary` — Individual position summary (symbol, quantity, average price, P&L)

### `com.trademesh.risk`
**Risk management and position limits**
- `RiskManager` — Enforces position size, daily loss limits, sector exposure limits
- `RiskLimitsConfig` — Risk configuration (max position size, daily loss %, max sector exposure)

### `com.trademesh.alert`
**Alert dispatching (Telegram, email, webhook)**
- `TelegramNotificationBot` — Sends alerts via Telegram Bot API
- `TelegramClient` — Low-level Telegram API client (WebClient-based)
- `TelegramProperties` — Telegram configuration (bot token, chat ID)
- `Alert` — Alert DTO (type, symbol, message, timestamp)
- `AlertType` — Enum: PAPER_TRADE, VIX_ALERT, TOKEN_EXPIRY, POSITION_CLOSED

### `com.trademesh.common`
**Shared utilities and exception handling**
- `common.dto` — Common DTOs (responses, error messages)
- `common.exception` — Custom exceptions (AuthenticationException, StrategyException, etc.)
- `common.handler` — Global exception handler for REST endpoints
- `common.resilience` — Resilience4j configuration (circuit breakers, retries)

---

## 2. Architectural Patterns

### 2.1 Reactive Architecture (Spring WebFlux)
- **Non-blocking I/O:** All database queries, HTTP calls, and Redis operations use reactive streams (Mono/Flux)
- **Backpressure handling:** Sinks.many().multicast() for tick stream with onBackpressureBuffer
- **Composition:** Mono.zip() for parallel operations, flatMap() for sequential chaining

**Example:**
```java
// Parallel intelligence generation
Mono.zip(
    vixMonitorService.fetchCurrentVix(),
    optionChainService.fetchOptionChain(symbol)
)
.flatMap(tuple -> {
    var vixData = tuple.getT1();
    var optionChain = tuple.getT2();
    // Further processing...
})
```

### 2.2 R2DBC Reactive Database Access
- **Connection pooling:** Configured in application.yml (initial-size: 2, max-size: 10)
- **Custom converters:** PaperTradeLegsConverter handles JSONB ↔ List<TradeLeg> conversion
- **Persistable<UUID> pattern:** Entities implement Persistable to control INSERT vs UPDATE
  - `isNew` flag set to true on creation, false after @PostLoad
  - Allows R2DBC to determine operation type automatically

**Example:**
```java
@Table("paper_trades")
public class PaperTrade implements Persistable<UUID> {
    @Id
    private UUID id;
    
    @Transient
    @JsonIgnore
    private boolean isNew = true;
    
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
```

### 2.3 Service Layer Pattern
**Two-method variant for persistence:**
- **Read method (REST-facing):** Fetches/calculates data, no save
- **Save method (scheduler-facing):** Calls read method, then persists

**Example:**
```java
// REST endpoint — read only
public Mono<MarketIntelligence> generateIntelligence(String symbol) {
    return performGeneration(symbol)
        .flatMap(intelligence -> cacheInRedis(intelligence));
}

// Scheduler — read + save
public Mono<Void> persistIntelligence(String symbol) {
    return generateIntelligence(symbol)
        .flatMap(intelligence -> repository.save(intelligence));
}
```

### 2.4 Token Guard Pattern (TokenAvailabilityTracker)
**Prevents log spam when Zerodha token is missing:**
- Tracks first warning per scheduler using ConcurrentHashMap<String, AtomicBoolean>
- Deduplicates concurrent calls with compare-and-set
- Resets tracking when token becomes available
- Suppresses stack traces on subsequent warnings

**Usage:**
```java
authService.getAccessToken()
    .switchIfEmpty(Mono.error(new IllegalStateException("No token")))
    .onErrorResume(e -> {
        if (tokenTracker.trackTokenMissing("SchedulerName")) {
            log.warn("Zerodha not authenticated. Skipping until login.");
        }
        return Mono.empty();
    });
```

### 2.5 R2DBC Converter Pattern
**Custom converters for complex types:**
- `@WritingConverter`: List<TradeLeg> → Json (JSONB)
- `@ReadingConverter`: Json (JSONB) → List<TradeLeg>
- Registered in R2dbcConfig bean

**Example:**
```java
@WritingConverter
public static class PaperTradeLegsWritingConverter implements Converter<List<TradeLeg>, Json> {
    public Json convert(List<TradeLeg> source) {
        String jsonString = objectMapper.writeValueAsString(source);
        return Json.of(jsonString);
    }
}
```

### 2.6 Strategy Pattern
- **TradingStrategy interface:** All strategies implement evaluate(MarketIntelligence)
- **StrategySelector:** Filters by applicability, applies priority-based selection
- **StrategyEngine:** Evaluates selected strategy and returns StrategyResult
- **Extensible:** New strategies added by implementing TradingStrategy and registering as @Component

### 2.7 Scheduler Pattern
- **@Scheduled tasks:** Cron-based execution (market hours aware)
- **Reactive chains:** Mono.fromRunnable() for non-blocking execution
- **Error handling:** doOnError() + subscribe() with error callback
- **Market calendar checks:** Verify market is open before executing trades

---

## 3. Key Configurations

### 3.1 application.yml Structure

#### Server
```yaml
server:
  port: 8080
```

#### Spring Core (R2DBC, DataSource, Liquibase, Redis)
```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/trade_mesh_db?schema=trade_mesh
    pool:
      initial-size: 2
      max-size: 10
      max-idle-time: 30m
  
  datasource:  # JDBC for Liquibase only
    url: jdbc:postgresql://localhost:5432/trade_mesh_db
    driver-class-name: org.postgresql.Driver
  
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.yaml
    default-schema: trade_mesh
  
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 30s
```

#### Zerodha Kite Connect
```yaml
zerodha:
  kite:
    api-key: ${ZERODHA_API_KEY}
    api-secret: ${ZERODHA_API_SECRET}
    redirect-url: http://localhost:8080/zerodha/callback
    base-url: https://api.kite.trade
    access-token-ttl-hours: 8
    instruments-buffer-size-mb: 20MB
  
  token-check-interval-ms: 300000  # 5 minutes
  
  ticker:
    nifty50-token: 256265
    niftybank-token: 260105
    price-cache-ttl-seconds: 60
    health-check-interval-ms: 30000
    max-reconnect-alerts: 10
```

#### Telegram
```yaml
telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  chat-id: ${TELEGRAM_CHAT_ID}
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
    lot-sizes:
      NIFTY50: 75
      NIFTYBANK: 30
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
```

#### Risk Management
```yaml
risk:
  max-position-size-per-symbol: 5
  daily-loss-limit-percent: 2.0
  max-sector-exposure: 50000
  min-margin-buffer-percent: 10
```

### 3.2 Liquibase Changelog Naming Convention
**Format:** `NNN-description.yaml` (zero-padded 3-digit number)

**Current changesets (latest: 009):**
1. `001-create-schema.yaml` — Creates trade_mesh schema
2. `002-create-tables.yaml` — zerodha_sessions, instruments, strategies, orders, portfolio_positions
3. `003-create-paper-trades.yaml` — paper_trades table with indexes
4. `004-create-strategy-execution-logs.yaml` — strategy_execution_logs table with indexes
5. `005-create-option-chain-snapshots.yaml` — option_chain_snapshots table
6. `006-create-vix-history.yaml` — vix_history table
7. `007-create-vix-alerts.yaml` — vix_alerts table
8. `008-create-oi-snapshots.yaml` — oi_snapshots table
9. `009-create-max-pain-history.yaml` — max_pain_history table

**Next changeset:** `010-*.yaml`

### 3.3 Redis Usage
**Key prefixes and TTLs:**
- `zerodha:access_token` — Zerodha OAuth token (TTL: 8 hours)
- `price:{symbol}` — Latest tick price (TTL: 60 seconds)
- `market:intelligence:{symbol}` — Market intelligence snapshot (TTL: 5 minutes)
- `market:maxpain:{symbol}` — Max pain calculation (TTL: 15 minutes)
- `optionchain:{symbol}` — Option chain snapshot (TTL: 3 minutes)

---

## 4. EXPIRY_DAY_STRANGLE Strategy

**Status:** ENABLED (configured in application.yml)

**Trigger:** Thursday (expiry day) with EXPIRY_DAY market condition

**Key Classes:**
- `ExpiryDayStrategy` — Main strategy implementation
- `StrategySelector` — Selects this strategy for EXPIRY_DAY condition
- `TradingScheduler.scheduledExpiryDayEntry()` — Triggers at 9:30 AM Thursday

**Strategy Logic:**

1. **Safety Checks:**
   - VIX must be ≤ 18.0 (configured max-vix)
   - OI walls must be clear (max call/put strikes available)
   - Entry time must be before 12:30 PM (configured latest-entry-time)

2. **Strike Selection:**
   - Call strike: Max OI call strike + widening if too close to spot
   - Put strike: Max OI put strike - widening if too close to spot
   - Minimum distance from spot: 100 points (configured min-distance-from-spot)

3. **Trade Leg Building:**
   - Call leg: SELL at max OI call strike
   - Put leg: SELL at max OI put strike
   - Quantity: 1 lot (configured max-lots)
   - Limit price: LTP from option chain

4. **Credit Calculation:**
   - Total credit = Call LTP + Put LTP
   - Minimum credit: 20 points (configured min-credit)

5. **Confidence Assessment:**
   - HIGH: Credit ≥ 40 points (2× min-credit)
   - MEDIUM: Credit < 40 points

6. **Exit Triggers:**
   - Stop loss: If current value > entry credit × 2 (100% loss)
   - Target: If current value < entry credit × 0.5 (50% profit)

**Example Configuration:**
```yaml
- name: EXPIRY_DAY_STRANGLE
  enabled: true
  allowed-days: [TUESDAY]  # Note: Scheduled for THU, but config says TUESDAY
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

## 5. Zerodha Kite Connect Integration

### 5.1 Authentication Flow

1. **User initiates login:**
   ```
   GET /zerodha/login
   ```
   Returns Kite login URL

2. **User authenticates on Kite:**
   ```
   https://kite.zerodha.com/connect/login?api_key=xxx&v=3
   ```

3. **Kite redirects with request_token:**
   ```
   GET /zerodha/callback?request_token=xxx&action=login
   ```

4. **Backend exchanges request_token for access_token:**
   ```
   POST https://api.kite.trade/session/token
   Body: api_key=xxx&request_token=xxx&checksum=SHA256(api_key+request_token+api_secret)
   ```

5. **Access token stored in Redis:**
   ```
   Key: zerodha:access_token
   TTL: 8 hours
   ```

**Key Classes:**
- `ZerodhaAuthService` — Handles OAuth flow, token storage
- `ZerodhaAuthController` — REST endpoints
- `TokenRefreshScheduler` — Daily health checks (08:00 AM, 09:00 AM IST)

### 5.2 WebClient Setup
**Configured in WebClientConfig:**
- Base URL: https://api.kite.trade
- Buffer size: 20 MB (for /instruments CSV response)
- Timeout: 30 seconds
- Retry policy: 3 retries with exponential backoff

### 5.3 KiteTicker WebSocket
**Real-time market data:**
- Connects using access token from Redis
- Subscribes to NIFTY 50 (token 256265) and NIFTY BANK (token 260105)
- Subscription mode: FULL (all tick data)
- Auto-reconnect: Enabled (max 5 retries, 5-second interval)

**Tick Processing:**
- Emits to reactive Flux for downstream consumers
- Stores latest price in Redis (key: `price:{symbol}`, TTL: 60 seconds)

**Key Classes:**
- `ZerodhaTickerService` — WebSocket management
- `TickerConnectionManager` — Connection lifecycle
- `TickData` — Tick DTO

---

## 6. Telegram Integration

### 6.1 Alert Sending
**Flow:**
1. Strategy generates signal → Trade executed
2. PaperTradingEngine creates Alert object
3. TelegramNotificationBot formats message (HTML)
4. TelegramClient sends via Telegram Bot API

### 6.2 Alert Types
- `PAPER_TRADE` — Trade entry/exit
- `VIX_ALERT` — VIX threshold breach
- `TOKEN_EXPIRY` — Zerodha token expiry warning
- `POSITION_CLOSED` — Position closed (stop loss/target)

### 6.3 Message Formatting
**Entry Alert:**
```
📄 Paper Trade ENTERED

Strategy: EXPIRY_DAY_STRANGLE
Symbol: NIFTY50
Entry Time: 2026-06-28T09:35:00

• SELL CE @ 22300 (1L)
• SELL PE @ 22100 (1L)

Credit Collected: 45.50 pts
Max Loss: 45.50 pts
Confidence: HIGH
```

**Exit Alert:**
```
✅ Paper Trade CLOSED

Strategy: EXPIRY_DAY_STRANGLE
Symbol: NIFTY50
Exit Reason: TARGET_HIT

P&L: 22.75 pts (₹ 1706.25)
Duration: 2h 15m
Exit Time: 2026-06-28T11:50:00
```

**Key Classes:**
- `TelegramNotificationBot` — Alert dispatcher
- `TelegramClient` — Low-level API client
- `TelegramProperties` — Configuration (bot token, chat ID)

---

## 7. Database Tables

### zerodha_sessions
Stores Zerodha OAuth sessions (access tokens)
- `id` (BIGSERIAL, PK)
- `user_id` (VARCHAR, UNIQUE)
- `access_token` (TEXT)
- `created_at` (TIMESTAMPTZ, default NOW())
- `expires_at` (TIMESTAMPTZ)

### instruments
Master list of tradeable instruments fetched from Zerodha
- `instrument_token` (BIGINT, PK)
- `exchange_token` (BIGINT)
- `tradingsymbol` (VARCHAR)
- `name` (VARCHAR)
- `exchange` (VARCHAR)
- `segment` (VARCHAR)
- `instrument_type` (VARCHAR)
- `expiry` (DATE)
- `strike` (NUMERIC)
- `tick_size` (NUMERIC)
- `lot_size` (INTEGER)
- `last_updated` (TIMESTAMPTZ, default NOW())

### strategies
Trading strategy definitions
- `id` (BIGSERIAL, PK)
- `name` (VARCHAR, UNIQUE)
- `description` (TEXT)
- `is_active` (BOOLEAN, default false)
- `config_json` (JSONB)
- `created_at` (TIMESTAMPTZ, default NOW())
- `updated_at` (TIMESTAMPTZ, default NOW())

### orders
Order records placed via Zerodha
- `id` (BIGSERIAL, PK)
- `zerodha_order_id` (VARCHAR, UNIQUE)
- `strategy_id` (BIGINT, FK → strategies)
- `tradingsymbol` (VARCHAR)
- `exchange` (VARCHAR)
- `transaction_type` (VARCHAR)
- `order_type` (VARCHAR)
- `product` (VARCHAR)
- `quantity` (INTEGER)
- `price` (NUMERIC)
- `trigger_price` (NUMERIC)
- `status` (VARCHAR)
- `placed_at` (TIMESTAMPTZ, default NOW())
- `updated_at` (TIMESTAMPTZ, default NOW())

### portfolio_positions
Current portfolio positions
- `id` (BIGSERIAL, PK)
- `tradingsymbol` (VARCHAR)
- `exchange` (VARCHAR)
- `product` (VARCHAR)
- `quantity` (INTEGER, default 0)
- `average_price` (NUMERIC)
- `last_price` (NUMERIC)
- `pnl` (NUMERIC)
- `updated_at` (TIMESTAMPTZ, default NOW())

### paper_trades
Paper (simulated) trade records
- `id` (UUID, PK)
- `trade_date` (DATE)
- `symbol` (VARCHAR)
- `strategy_name` (VARCHAR)
- `status` (VARCHAR) — OPEN, CLOSED, CANCELLED
- `legs` (JSONB) — List<TradeLeg>
- `entry_credit` (NUMERIC)
- `current_value` (NUMERIC)
- `realized_pnl` (NUMERIC)
- `unrealized_pnl` (NUMERIC)
- `entry_time` (TIMESTAMPTZ)
- `exit_time` (TIMESTAMPTZ)
- `exit_reason` (VARCHAR)
- `created_at` (TIMESTAMPTZ, default NOW())
- `updated_at` (TIMESTAMPTZ, default NOW())
- **Indexes:** status, symbol, strategy_name, trade_date, (trade_date, symbol)

### strategy_execution_logs
Audit log for strategy execution events
- `id` (UUID, PK)
- `symbol` (VARCHAR)
- `strategy_name` (VARCHAR)
- `market_condition` (VARCHAR)
- `action` (VARCHAR)
- `reason` (TEXT)
- `vix_level` (NUMERIC)
- `executed_at` (TIMESTAMPTZ)
- `created_at` (TIMESTAMPTZ, default NOW())
- **Indexes:** symbol, strategy_name, market_condition, executed_at, (symbol, executed_at)

### option_chain_snapshots
Historical option chain data
- `id` (BIGSERIAL, PK)
- `symbol` (VARCHAR)
- `expiry_date` (DATE)
- `spot_price` (DECIMAL)
- `data` (JSONB)
- `created_at` (TIMESTAMP, default CURRENT_TIMESTAMP)
- **Indexes:** symbol, (symbol, expiry_date), created_at

### vix_history
Historical VIX data
- `id` (BIGSERIAL, PK)
- `vix_value` (DECIMAL)
- `vix_level` (VARCHAR)
- `change_percent` (DECIMAL)
- `recorded_at` (TIMESTAMP, default CURRENT_TIMESTAMP)
- **Indexes:** recorded_at, vix_level, vix_value

### vix_alerts
VIX threshold breach alerts
- `id` (BIGSERIAL, PK)
- `vix_value` (DECIMAL)
- `vix_level` (VARCHAR)
- `alert_message` (TEXT)
- `telegram_sent` (BOOLEAN, default false)
- `triggered_at` (TIMESTAMP, default CURRENT_TIMESTAMP)
- **Indexes:** triggered_at, vix_level, telegram_sent

### oi_snapshots
Open Interest analysis snapshots
- `id` (BIGSERIAL, PK)
- `symbol` (VARCHAR)
- `max_call_strike` (DECIMAL)
- `max_put_strike` (DECIMAL)
- `top_call_walls` (TEXT)
- `top_put_walls` (TEXT)
- `market_bias` (VARCHAR)
- `data` (JSONB)
- `recorded_at` (TIMESTAMP, default CURRENT_TIMESTAMP)
- **Indexes:** symbol, (symbol, recorded_at), recorded_at, market_bias

### max_pain_history
Max pain calculation snapshots
- `id` (BIGSERIAL, PK)
- `symbol` (VARCHAR)
- `expiry_date` (DATE)
- `max_pain_strike` (DECIMAL)
- `spot_price` (DECIMAL)
- `distance_from_spot` (DECIMAL)
- `distance_percent` (DECIMAL)
- `calculated_at` (TIMESTAMP, default CURRENT_TIMESTAMP)
- **Indexes:** symbol, expiry_date, calculated_at, (symbol, calculated_at DESC)

---

## 8. Running the Application

### Prerequisites
- Java 17+
- PostgreSQL 12+
- Redis 6+
- Zerodha Kite Connect API credentials
- Telegram Bot token and chat ID

### Environment Variables
```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=trade_mesh_db
export DB_USERNAME=postgres
export DB_PASSWORD=postgres

export REDIS_HOST=localhost
export REDIS_PORT=6379

export ZERODHA_API_KEY=your_api_key
export ZERODHA_API_SECRET=your_api_secret
export ZERODHA_REDIRECT_URL=http://localhost:8080/zerodha/callback

export TELEGRAM_BOT_TOKEN=your_bot_token
export TELEGRAM_CHAT_ID=your_chat_id
```

### Build & Run
```bash
./gradlew clean build
./gradlew bootRun
```

### API Endpoints
- **Authentication:** `GET /zerodha/login`, `GET /zerodha/callback`
- **Market Data:** `GET /market-data/ticks`, `GET /market-data/intelligence/{symbol}`
- **Trading:** `GET /trading/positions`, `POST /trading/execute`
- **Paper Trades:** `GET /paper-trades`, `GET /paper-trades/{id}`
- **Strategies:** `GET /strategies`, `GET /strategies/{name}/evaluate`
- **Swagger UI:** `http://localhost:8080/swagger-ui.html`

---

## 9. Logging

**Structured logging with SLF4J:**
- Business events: INFO/DEBUG level
- Exceptions: ERROR level
- Avoid System.out.println

**Log levels (application.yml):**
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

## 10. Testing

**Unit tests:** Business logic (strategy evaluation, OI analysis, max pain calculation)
**Integration tests:** Controllers, repositories, Zerodha API integration
**Coverage target:** High coverage for trading logic

---

## 11. Development Notes

- **Reactive-first:** All I/O operations are non-blocking
- **No blocking calls:** Avoid .block() in production code
- **Error handling:** Use onErrorResume() for graceful degradation
- **Logging:** Use structured logging with context (symbol, strategy, etc.)
- **Testing:** Mock external services (Zerodha, Telegram) in unit tests
- **Database:** All schema changes via Liquibase changesets
- **Redis:** Use for caching, not persistence (PostgreSQL is source of truth)

---

## 12. Future Enhancements

- [ ] Live trading mode (currently paper trading only)
- [ ] Additional strategies (Theta Spread, Bull Put Spread, Bear Call Spread)
- [ ] Advanced risk management (sector exposure, margin utilization)
- [ ] Performance analytics dashboard
- [ ] Multi-user support with role-based access
- [ ] Email and webhook alerts
- [ ] Historical backtest engine

---

**Last Updated:** June 28, 2026
**Version:** 1.0.0
