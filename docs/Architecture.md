# TradeMesh — High-Level System Architecture

**TradeMesh** is a Spring WebFlux-based reactive trading platform for automated options strategies on NSE (National Stock Exchange). This document provides a high-level overview of the system architecture, design principles, and component interactions.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Architecture Principles](#architecture-principles)
3. [High-Level Architecture Diagram](#high-level-architecture-diagram)
4. [Core Components](#core-components)
5. [Data Flow](#data-flow)
6. [Technology Stack](#technology-stack)
7. [Deployment Architecture](#deployment-architecture)
8. [Scalability & Performance](#scalability--performance)

---

## System Overview

### Purpose
TradeMesh automates options trading strategies on NSE by:
- Monitoring market conditions (VIX, volatility, open interest)
- Evaluating trading strategies based on market intelligence
- Executing trades through Zerodha's Kite Connect API
- Managing positions and tracking P&L
- Sending real-time alerts via Telegram

### Key Features
- **Reactive Architecture** — Non-blocking I/O using Spring WebFlux
- **Multiple Strategies** — Iron Condor, Bull Put Spread, Bear Call Spread, Expiry Day Strangle
- **Real-Time Market Data** — WebSocket streaming via Zerodha KiteTicker
- **Paper Trading** — Simulate trades before live execution
- **Risk Management** — Position limits, daily loss limits, margin checks
- **Persistent Storage** — PostgreSQL with R2DBC for reactive access
- **Caching Layer** — Redis for token storage, price cache, option chain cache
- **Scheduled Jobs** — Automated entry/exit, market intelligence refresh, position monitoring

---

## Architecture Principles

### 1. **Reactive, Non-Blocking I/O**
- Uses Spring WebFlux for all HTTP operations
- R2DBC for reactive database access
- Lettuce for reactive Redis operations
- Eliminates thread blocking, enabling high concurrency

### 2. **Read/Write Separation**
- **REST Controllers** — Read-only, no database writes
- **Schedulers** — Exclusive DB write access via service save methods
- Ensures data consistency and prevents race conditions

### 3. **Service Layer Pattern**
- Services expose two method variants:
  - **Read Method** — Fetches/calculates data (REST-facing)
  - **Save Method** — Calls read method, then persists (scheduler-facing)
- Repositories accessed only through services, never directly from schedulers

### 4. **Event-Driven Execution**
- Market intelligence refreshes every 5 minutes
- Strategies evaluated based on current market conditions
- Trades executed at scheduled times (9:30 AM entry, 3:15 PM exit)
- Position monitoring every 60 seconds during market hours

### 5. **Structured Logging**
- SLF4J for all logging (no `System.out.println`)
- Business events logged at INFO/DEBUG level
- Exceptions logged at ERROR level
- Sensitive data masked in logs

### 6. **Schema Management**
- All database changes via Liquibase changesets
- No manual table modifications
- Version-controlled schema evolution

---

## High-Level Architecture Diagram

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
│  │ - nifty_daily_prices       │  │      │  └──────────────────────────┘   │
│  │ - vix_history              │  │      │                                  │
│  │ - iv_history               │  │      │  TTL: 5-60 minutes               │
│  │ - oi_snapshots             │  │      │  (Strategy-specific)             │
│  │ - max_pain_history         │  │      │                                  │
│  │ - option_chain_snapshots   │  │      │                                  │
│  └────────────────────────────┘  │      │                                  │
│                                   │      │                                  │
│  Schema: trade_mesh               │      │                                  │
│  Migrations: Liquibase            │      │                                  │
└──────────────────────────────────┘      └──────────────────────────────────┘
```

---

## Core Components

### 1. **REST Controllers** (Read-Only)
Expose HTTP endpoints for querying system state. No database writes.

| Controller | Responsibility |
|-----------|-----------------|
| `TradingController` | Trading status, open positions, daily reports |
| `StrategyController` | Strategy evaluation, applicable strategies |
| `MarketIntelligenceController` | Market intelligence, VIX, OI, max pain |
| `PaperTradeController` | Paper trade history, P&L, statistics |
| `ZerodhaAuthController` | OAuth2 login, token status |
| `AdminController` | Bootstrap data, test evaluations |

### 2. **Service Layer** (Business Logic)
Implements core trading logic, calculations, and data transformations.

| Service | Responsibility |
|---------|-----------------|
| `TradingOrchestrator` | Orchestrates complete trading cycle |
| `StrategyOrchestrator` | Evaluates strategies, selects applicable ones |
| `MarketIntelligenceService` | Aggregates market data (VIX, OI, max pain, condition) |
| `MarketMetricsService` | Calculates HV20, ATR14, IVR, IV/HV ratio, PCR |
| `PaperTradingEngine` | Simulates trade execution, mark-to-market |
| `RiskManager` | Enforces position limits, daily loss limits |
| `PortfolioService` | Aggregates portfolio data, P&L |
| `ZerodhaAuthService` | OAuth2 flow, token management |
| `OptionChainService` | Fetches and caches option chain data |

### 3. **Schedulers** (Automated Tasks)
Execute at scheduled times, have exclusive database write access.

| Scheduler | Frequency | Action |
|-----------|-----------|--------|
| `TradingScheduler` | 9:30 AM, 3:15 PM | Entry/exit for strategies |
| `IntelligenceScheduler` | Every 5 min (9 AM–3 PM) | Refresh market intelligence |
| `VixScheduler` | Every 5 min | Fetch VIX, persist every 15 min |
| `MarkToMarketScheduler` | Every 60 sec | Update open trade values |
| `TokenRefreshScheduler` | Daily 8 AM, 9 AM | Token health checks |
| `OptionChainSnapshotScheduler` | Every 5 min | Capture option chain snapshots |

### 4. **Repository Layer** (R2DBC)
Reactive data access to PostgreSQL.

| Repository | Entity |
|-----------|--------|
| `PaperTradeRepository` | Paper trade records |
| `InstrumentRepository` | Zerodha instruments |
| `NiftyDailyPriceRepository` | NIFTY50 daily OHLC |
| `StrategyExecutionLogRepository` | Strategy execution events |
| `VixHistoryRepository` | VIX snapshots |
| `IvHistoryRepository` | ATM IV history |

### 5. **External Integrations**

#### Zerodha Kite Connect
- **Authentication** — OAuth2 flow, token storage in Redis
- **Market Data** — WebSocket streaming via KiteTicker
- **Option Chain** — REST API for option chain data
- **Order Execution** — Place/cancel orders via REST API

#### Telegram
- **Alerts** — Entry signals, exit signals, system errors, VIX alerts
- **Reports** — Daily P&L reports

---

## Data Flow

### Trading Cycle (Entry → Exit)

```
1. MARKET OPEN (9:15 AM IST)
   │
   ├─→ IntelligenceScheduler (every 5 min)
   │   ├─→ Fetch VIX, OI, max pain
   │   ├─→ Calculate market metrics (HV20, ATR14, IVR, IV/HV, PCR)
   │   ├─→ Determine market condition (SIDEWAYS, TRENDING_BULLISH, etc.)
   │   └─→ Cache in Redis
   │
   ├─→ TradingScheduler (9:30 AM)
   │   ├─→ Fetch market intelligence from Redis
   │   ├─→ StrategyOrchestrator evaluates all strategies
   │   ├─→ Select applicable strategy based on market condition
   │   ├─→ Generate trade signal (legs, credit, max loss)
   │   ├─→ RiskManager validates position limits
   │   ├─→ PaperTradingEngine executes trade
   │   ├─→ Save PaperTrade record to PostgreSQL
   │   └─→ Send Telegram alert (ENTRY_SIGNAL)
   │
   ├─→ MarkToMarketScheduler (every 60 sec)
   │   ├─→ Fetch latest prices from Redis (KiteTicker)
   │   ├─→ Calculate current P&L for open trades
   │   ├─→ Check stop-loss and take-profit levels
   │   ├─→ Update PaperTrade record in PostgreSQL
   │   └─→ Send alert if SL/TP triggered
   │
   └─→ TradingScheduler (3:15 PM)
       ├─→ Force close all open positions
       ├─→ Calculate realized P&L
       ├─→ Update PaperTrade status to CLOSED
       ├─→ Send Telegram alert (EXIT_SIGNAL)
       └─→ Generate daily report

2. MARKET CLOSE (3:30 PM IST)
   │
   └─→ DailyReportService (3:45 PM)
       ├─→ Aggregate daily P&L
       ├─→ Calculate win rate, avg profit/loss
       └─→ Send Telegram report
```

### Market Intelligence Pipeline

```
KiteTicker WebSocket
    │
    ├─→ TickerService (processes ticks)
    │   └─→ Cache latest price in Redis (TTL: 60 sec)
    │
Zerodha REST API
    │
    ├─→ KiteHistoricalService (fetches OHLC)
    │   └─→ MarketMetricsService (calculates HV20, ATR14, IVR, IV/HV, PCR)
    │
    ├─→ VixMonitorService (fetches VIX)
    │   └─→ Cache in Redis (TTL: 5 min)
    │   └─→ VixScheduler persists every 15 min
    │
    ├─→ OptionChainService (fetches option chain)
    │   ├─→ Cache in Redis (TTL: 5 min)
    │   ├─→ OIAnalyserService (analyzes OI walls)
    │   ├─→ MaxPainCalculatorService (calculates max pain)
    │   └─→ OptionChainSnapshotScheduler persists every 5 min
    │
    └─→ MarketIntelligenceService (aggregates all data)
        └─→ Determine market condition
            ├─→ SIDEWAYS (low volatility, neutral PCR)
            ├─→ TRENDING_BULLISH (high IV/HV, bullish PCR)
            ├─→ TRENDING_BEARISH (high IV/HV, bearish PCR)
            ├─→ EXPIRY_DAY (Tuesday, low DTE)
            ├─→ HIGH_VOLATILITY (VIX > threshold)
            └─→ NO_TRADE (conditions unfavorable)
```

### Strategy Evaluation Pipeline

```
StrategyOrchestrator
    │
    ├─→ For each enabled strategy:
    │   │
    │   ├─→ Check if allowed day (MONDAY, TUESDAY, etc.)
    │   ├─→ Check if applicable condition (SIDEWAYS, TRENDING_BULLISH, etc.)
    │   ├─→ Fetch market intelligence from Redis
    │   ├─→ Check VIX range (min-vix ≤ VIX ≤ max-vix)
    │   ├─→ Check entry time (before latest-entry-time)
    │   │
    │   └─→ Strategy-specific evaluation:
    │       │
    │       ├─→ IRON_CONDOR:
    │       │   ├─→ Calculate confidence score (IVR, IV/HV, ATR%, VIX, PCR)
    │       │   ├─→ Check hard gates (VIX, ATR%, score, DTE)
    │       │   ├─→ Select strikes using delta-based approach (target delta = 0.18)
    │       │   ├─→ Calculate net credit and P&L matrix
    │       │   └─→ Adjust exit levels based on ATR%
    │       │
    │       ├─→ BULL_PUT_SPREAD / BEAR_CALL_SPREAD:
    │       │   ├─→ Select short strike (delta ≤ 0.30)
    │       │   ├─→ Select long strike (wing width = 100)
    │       │   └─→ Calculate net credit
    │       │
    │       └─→ EXPIRY_DAY_STRANGLE:
    │           ├─→ Select call strike (highest OI call)
    │           ├─→ Select put strike (highest OI put)
    │           └─→ Validate minimum distance from spot
    │
    └─→ Return StrategyResult (executable, signal, skip reason)
```

---

## Technology Stack

### Backend Framework
- **Spring Boot 3.3.0** — Application framework
- **Spring WebFlux** — Reactive web framework
- **Spring Data R2DBC** — Reactive database access
- **Spring Data Redis Reactive** — Reactive Redis client

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

## Deployment Architecture

### Development Environment
```
Local Machine
├─ Spring Boot Application (port 8080)
├─ PostgreSQL (port 5432)
└─ Redis (port 6379)
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
- **Stateless Application** — Each instance can be independently scaled
- **Shared Database** — All instances access same PostgreSQL
- **Shared Cache** — All instances access same Redis
- **Scheduler Coordination** — Use distributed locking (Redis) to prevent duplicate scheduled job execution
- **Health Checks** — Implement `/health` endpoint for load balancer

---

## Scalability & Performance

### Horizontal Scalability
- **Stateless Design** — Application instances don't maintain state
- **Distributed Schedulers** — Use Redis locks to coordinate scheduled jobs across instances
- **Connection Pooling** — R2DBC pool (2-10 connections) and Redis pool (8 connections)
- **Load Balancing** — Route requests across multiple instances

### Performance Optimizations
- **Reactive I/O** — Non-blocking operations eliminate thread blocking
- **Caching Strategy** — Redis caches:
  - Zerodha access tokens (TTL: 8 hours)
  - Option chain data (TTL: 5 minutes)
  - Tick prices (TTL: 60 seconds)
  - Market intelligence (TTL: 10 minutes)
- **Database Indexing** — Indexes on:
  - `paper_trades.status`, `paper_trades.created_at`
  - `strategy_execution_logs.strategy_name`, `strategy_execution_logs.timestamp`
  - `nifty_daily_prices.trade_date`
  - `vix_history.recorded_at`
- **Batch Operations** — Bulk inserts for historical data
- **Query Optimization** — Avoid N+1 queries, use projections

### Monitoring & Observability
- **Structured Logging** — SLF4J with JSON output for log aggregation
- **Metrics** — Spring Boot Actuator (optional: Micrometer + Prometheus)
- **Distributed Tracing** — Spring Cloud Sleuth (optional: Zipkin)
- **Health Checks** — Database, Redis, Zerodha API connectivity
- **Alerts** — Telegram notifications for critical events

### Bottlenecks & Mitigation
| Bottleneck | Mitigation |
|-----------|-----------|
| Zerodha API rate limits | Implement circuit breaker, cache responses |
| Database connection pool exhaustion | Monitor pool usage, increase pool size if needed |
| Redis memory limits | Implement TTL policies, monitor memory usage |
| WebSocket connection drops | Auto-reconnect with exponential backoff |
| High-frequency market data | Batch updates, aggregate ticks before processing |

---

## Summary

TradeMesh is a **reactive, event-driven trading platform** built on Spring WebFlux with clear separation between read operations (REST controllers) and write operations (schedulers). The architecture emphasizes:

1. **Non-blocking I/O** for high concurrency
2. **Service-oriented design** with business logic encapsulation
3. **Scheduled automation** for consistent trading execution
4. **Persistent storage** with schema versioning
5. **Caching layer** for performance optimization
6. **External integrations** for market data and notifications

The system is designed to scale horizontally, with stateless application instances coordinating through shared PostgreSQL and Redis infrastructure.
