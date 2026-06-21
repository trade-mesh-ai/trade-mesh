# ⚡ trade-mesh

> Algorithmic trading platform for Indian markets — built on Java 21, Spring Boot 3.3, and Spring WebFlux.  
> Designed for extensibility: plug in new brokers, strategies, and market streams without touching core logic.

---

## 🎯 Overview

**trade-mesh** is a production-grade, monolithic algorithmic trading platform targeting NIFTY and BankNIFTY options on NSE via Zerodha Kite Connect. It features a shared **Market Intelligence Layer** that calculates VIX, OI walls, Max Pain, PCR, and Greeks once and feeds all pluggable strategies — making the system adaptive to market conditions rather than blindly executing a single strategy.

The platform is also designed as a **SaaS rental product**: other traders can subscribe to use the platform, run strategies on their own Zerodha accounts, and benefit from the same intelligence layer.

---

## 🏗️ Architecture

```
Zerodha Integration Layer      (KiteTicker WebSocket + Kite REST API)
        ↓
Market Intelligence Layer      (VIX · OI · Max Pain · PCR · Greeks · Events)
        ↓
Market Condition Classifier    (SIDEWAYS · TRENDING · EXPIRY_DAY · NO_TRADE)
        ↓
Strategy Selector              (Auto-picks best strategy for current condition)
        ↓
Strategy Layer                 (Pluggable strategies — each uses shared intelligence)
        ↓
Risk Management Layer          (Position sizing · Stop loss · Margin · Weekly loss limit)
        ↓
Order Execution Layer          (Paper trading simulator · Zerodha live orders)
        ↓
Data + Alert Layer             (SQLite · Redis · Telegram alerts · Spring Scheduler)
        ↓
Dashboard                      (Positions · P&L · Strategy status · Intelligence view)
```

---

## 📦 Project Structure

```
trade-mesh/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── application.yml
├── application-paper.yml
└── src/main/java/com/trademesh/
    ├── zerodha/
    │   ├── auth/           # Auth service, token refresh scheduler
    │   ├── ticker/         # KiteTicker WebSocket, tick publisher
    │   └── orders/         # Order service, order mapper
    ├── intelligence/
    │   ├── MarketIntelligenceService.java      # Orchestrates all intelligence
    │   ├── MarketConditionClassifier.java      # Outputs market condition enum
    │   ├── vix/            # VIX monitor, volatility regime detector
    │   ├── oi/             # OI analyser, institutional wall finder
    │   ├── maxpain/        # Max pain calculator
    │   ├── pcr/            # Put call ratio tracker
    │   ├── events/         # RBI, Fed, Budget event calendar
    │   └── greeks/         # Delta, Theta, Vega, Gamma engine
    ├── strategy/
    │   ├── TradingStrategy.java                # Pluggable strategy interface
    │   ├── StrategySelector.java               # Picks strategy from condition
    │   ├── expiryday/      # Thursday-only options selling
    │   ├── thetaspread/    # Wednesday entry, 2-night max risk
    │   └── bullput/        # Directional bias, OI confirmed
    ├── risk/
    │   ├── RiskManager.java
    │   ├── PositionSizer.java
    │   ├── StopLossEnforcer.java
    │   ├── MarginChecker.java
    │   └── WeeklyLossTracker.java
    ├── execution/
    │   ├── BrokerAdapter.java                  # Pluggable broker interface
    │   ├── PaperTradingEngine.java
    │   ├── OrderTracker.java
    │   └── PnLCalculator.java
    ├── portfolio/
    │   ├── PositionTracker.java
    │   ├── TradeHistoryService.java
    │   └── WeeklyReportService.java
    ├── alert/
    │   ├── TelegramAlertService.java
    │   └── AlertMessage.java
    ├── scheduler/
    │   ├── MondayEntryScheduler.java
    │   ├── ThursdayExitScheduler.java
    │   └── MonitorScheduler.java
    └── domain/
        ├── MarketIntelligence.java
        ├── TradeSignal.java
        ├── OptionChain.java
        └── Position.java
```

---

## 🚀 Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 (Virtual Threads) |
| Framework | Spring Boot 3.3 |
| Reactive | Spring WebFlux |
| Build | Gradle (Kotlin DSL) |
| Database | SQLite → PostgreSQL (Phase 2) |
| Cache | Redis (Lettuce reactive client) |
| Broker | Zerodha Kite Connect (WebSocket + REST) |
| Alerts | Telegram Bot API |
| Scheduler | Spring Scheduler |
| Docs | SpringDoc OpenAPI (Swagger) |

---

## 📊 Supported Strategies

All strategies share the same Market Intelligence Layer — nothing is calculated twice.

| Strategy | When activated | Risk level | Overnight risk |
|---|---|---|---|
| Expiry Day Selling | Thursday only, sideways market | Low | ✅ Zero |
| Theta Decay Spread | Wednesday entry, low VIX | Medium | ⚠️ 2 nights |
| Bull Put Spread | Clear OI bullish bias | Medium | ⚠️ Few nights |
| OI Based Buying | High VIX, strong trend | Medium-High | ✅ Zero (intraday) |

### Adding a new strategy
Implement one interface — the core engine picks it up automatically:

```java
@Component
public class MyNewStrategy implements TradingStrategy {

    @Override
    public boolean isApplicable(MarketCondition condition) {
        return condition == MarketCondition.SIDEWAYS;
    }

    @Override
    public Mono<TradeSignal> execute(MarketIntelligence intelligence) {
        // your logic here
    }
}
```

---

## 🧠 Market Intelligence Layer

The heart of trade-mesh. Runs continuously and produces a single `MarketIntelligence` object shared across all strategies.

| Component | What it calculates |
|---|---|
| VIX Monitor | Volatility regime — LOW / NORMAL / HIGH / EXTREME |
| OI Analyser | Institutional call/put walls, support/resistance |
| Max Pain Calculator | Strike where maximum option buyers lose money |
| PCR Calculator | Put call ratio — market sentiment indicator |
| Event Calendar | RBI dates, US Fed dates, budget days — auto skip |
| Greeks Engine | Delta, Theta, Vega, Gamma for open positions |

**Market condition output:**

```
SIDEWAYS            → Low VIX, no strong trend, OI walls clear
TRENDING_BULLISH    → Strong upward move, PCR bullish
TRENDING_BEARISH    → Strong downward move, PCR bearish
EXPIRY_DAY          → Thursday — maximum theta decay opportunity
HIGH_VOLATILITY     → VIX > 20, consider buying strategies
NO_TRADE            → Event risk, extreme VIX, unclear signals
```

---

## ⚙️ Getting Started

### Prerequisites

```
Java 21+
Gradle 8+
Redis (running locally)
Zerodha trading account
Zerodha Kite Connect subscription (₹500/month)
```

### Run Redis (WSL on Windows):

```bash
sudo service redis-server start
redis-cli ping   # should return PONG
```

### Configure credentials:

```yaml
# application-local.yml (gitignored — never commit this)
zerodha:
  api-key: your_api_key_here
  api-secret: your_api_secret_here
  redirect-url: http://localhost:8080/zerodha/callback

telegram:
  bot-token: your_bot_token_here
  chat-id: your_chat_id_here
```

### Run the application:

```bash
./gradlew bootRun --args='--spring.profiles.active=paper'
```

### Authenticate with Zerodha:

```
Open browser → http://localhost:8080/zerodha/login
Login with Zerodha credentials
Token stored in Redis automatically
Valid for 1 trading day
```

---

## 📅 Development Roadmap

### Phase 1 — Foundation (Month 1-2)
- [x] Project setup — Spring Boot + Gradle
- [ ] Zerodha auth + KiteTicker WebSocket
- [ ] Market Intelligence Layer
- [ ] Paper trading engine
- [ ] Expiry Day strategy (first strategy)
- [ ] SQLite persistence
- [ ] Telegram alerts
- [ ] Basic dashboard

### Phase 2 — Live Trading (Month 3-4)
- [ ] Live order execution via Zerodha
- [ ] Risk engine — stop loss, position sizing
- [ ] Additional strategies — Theta Spread, Bull Put
- [ ] PostgreSQL migration
- [ ] Backtest engine
- [ ] Mobile alerts

### Phase 3 — AI Layer (Month 5-6)
- [ ] Claude AI integration — market analysis
- [ ] Sentiment engine — news processing
- [ ] OI based buying strategy
- [ ] Second broker — Upstox adapter

### Phase 4 — SaaS / Rental (Month 7-8)
- [ ] Multi-tenant architecture
- [ ] Stripe billing integration
- [ ] Tenant portal
- [ ] Strategy marketplace

### Phase 5 — Scale (Month 9+)
- [ ] Kubernetes deployment
- [ ] Additional brokers
- [ ] Global markets
- [ ] AutoML strategy optimizer

---

## 💰 Cost Structure

| Item | Cost |
|---|---|
| Zerodha Kite Connect API | ₹500/month |
| Anthropic API (Claude AI) | ~₹400/month |
| Redis (local WSL) | ₹0 |
| SQLite | ₹0 |
| Everything else | ₹0 |
| **Total Phase 1** | **₹500-900/month** |

---

## 🔌 Extensibility

trade-mesh is designed from day one to extend without modifying core logic:

| Extension point | How to extend |
|---|---|
| New broker | Implement `BrokerAdapter` interface |
| New strategy | Implement `TradingStrategy` interface |
| New market stream | Implement `MarketFeed` interface |
| New alert channel | Implement `AlertService` interface |

---

## ⚠️ Disclaimer

This platform is for educational and personal use only. Algorithmic trading involves significant financial risk. Past performance of any strategy does not guarantee future results. Always paper trade before going live. Never risk more than you can afford to lose.

---

## 👤 Author

**Kuldeep Singh**  
Associate Principal Engineer — LTIMindtree  
GitHub: [kuldeepsingh123](https://github.com/kuldeepsingh123)  
LinkedIn: [kuldeepsingh-engineer](https://linkedin.com/in/kuldeepsingh-engineer)  
Org: [microminds-ai](https://github.com/microminds-ai) · [trade-mesh-ai](https://github.com/trade-mesh-ai)

---

*Built with ☕ Java, ⚡ Spring WebFlux, and 🧠 Claude AI*