# TradeMesh — Architecture Reference

**This document describes verified current architecture as of July 20, 2026. For decision rationale, see [docs/architecture/decisions/](decisions/). For session-by-session implementation history, see [docs/architecture/CURRENT_STATE.md](CURRENT_STATE.md).**

---

## 1. System Overview

TradeMesh is a reactive options trading platform for NSE (National Stock Exchange, India) that automates multi-leg options strategies based on real-time market intelligence and risk management rules. The system currently operates in **paper-trading mode only** — all trades are simulated without real capital or broker orders.

**Four active strategies:**
- **Iron Condor** — Sideways market, two-sided income strategy (short call + short put with long hedges)
- **Bull Put Spread** — Bullish market, directional income strategy (short put + long put)
- **Bear Call Spread** — Bearish market, directional income strategy (short call + long call)
- **Expiry Day Strangle** — Expiry day only, naked strangle (short call + short put, no hedges)

**Market focus:** NIFTY50 index options on NSE. All times in IST (Asia/Kolkata, UTC+5:30).

---

## 2. Technology Stack

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
| **Boilerplate Reduction** | Lombok | 1.18.x |
| **Testing** | JUnit 5, Reactor Test, TestContainers | Latest |

---

## 3. Package Structure

Actual directory structure verified from source:

- **`com.trademesh`** — Root package
  - **`alert`** — Telegram notifications (Alert, AlertType, TelegramClient, TelegramNotificationBot, TelegramProperties)
  - **`common`** — Shared utilities (DTOs, exceptions, handlers, resilience patterns)
  - **`config`** — Application configuration (JacksonConfig, R2dbcConfig, LotSizeService, PaperTradeLegsConverter, ResilienceConfig, WebClientConfig)
  - **`domain`** — Core domain entities (Instrument, NiftyDailyPrice and their repositories)
  - **`execution`** — Paper trading engine (PaperTrade entity, PaperTradingEngine, MarkToMarketScheduler, OrderExecutionService [legacy, zero callers], TradeStatus enum)
  - **`intelligence`** — Market intelligence services (MarketCondition enum, MarketIntelligence, MarketIntelligenceService, MarketMetrics, MarketMetricsService, SignalService, TradingSignal, KiteHistoricalService, NiftyDailyPriceService, IntelligenceScheduler, plus subdirectories for IV, Max Pain, OI, VIX analysis)
    - **`intelligence.iv`** — IV analysis (IvHistoryScheduler)
    - **`intelligence.maxpain`** — Max Pain analysis (MaxPainScheduler)
    - **`intelligence.oi`** — OI analysis (OIAnalysisScheduler)
    - **`intelligence.vix`** — VIX analysis (VixScheduler)
  - **`portfolio`** — Position aggregation (PortfolioService, PortfolioSummary, PositionSummary)
  - **`risk`** — Risk management (RiskManager [DB-backed concurrent position limit only], RiskLimitsConfig)
   - **`scheduler`** — Scheduled tasks (package exists but is effectively dead — a leftover artifact from a past refactor where TradingScheduler was moved to com.trademesh.trading to resolve a Spring bean conflict; the old location left behind as a comment-only stub rather than deleted)
   - **`strategy`** — Strategy framework (StrategySelector, StrategyEngine, StrategyOrchestrator, TradingStrategy interface, TradeSignal, TradeLeg, StrategyResult, plus subdirectories for expiryday, ironcondor, spread strategies)
   - **`trading`** — Trading orchestration (TradingOrchestrator, TradingScheduler, DailyReportService, MarketCalendarService, StrategyExecutionLog)
  - **`zerodha`** — Zerodha Kite integration (auth, options, orders, ticker subdirectories)
    - **`zerodha.options`** — Options data (OptionChainSnapshotScheduler)

---

## 4. Runtime Flow — Entry

**Single entry chokepoint:** `PaperTradingEngine.executeTrade(StrategyResult result)`

**Entry path:**

1. **TradingScheduler.scheduledDailyEntry()** (9:30 AM IST, weekdays)
   - Checks `TokenAvailabilityTracker.isTokenAvailable()` — skips with WARN if token missing
   - Calls `TradingOrchestrator.runTradingCycle("NIFTY50")`

2. **TradingOrchestrator.runTradingCycle(symbol)**
   - Generates fresh market intelligence via `MarketIntelligenceService.generateFreshIntelligence(symbol)` (bypasses 5-min cache)
   - Calls `StrategySelector.evaluateSelectedStrategy(intelligence)`

3. **StrategySelector.evaluateSelectedStrategy(intelligence)**
   - Performs direct lookup (not priority-based arbitration — see ADR-001):
     - If `MarketCondition.NO_TRADE` or `HIGH_VOLATILITY` → returns skip result
     - Otherwise → filters strategies by `isApplicable(condition)` → structurally guaranteed ≤1 match
   - Calls `strategy.evaluate(intelligence)` on the selected strategy
   - Returns `StrategyResult` with signal or skip reason

4. **Strategy.evaluate(intelligence)** (one of four: ExpiryDayStrategy, IronCondorStrategy, BullPutSpreadStrategy, BearCallSpreadStrategy)
   - Evaluates market conditions against strategy-specific entry gates
   - Computes confidence score (if applicable)
   - Builds `TradeSignal` with legs, credit, sizing
   - Returns `StrategyResult` with `isExecutable=true/false`

5. **TradingOrchestrator.runTradingCycle()** (continued)
   - Checks `RiskManager.isWithinConcurrentPositionLimit()` (max 3 open positions)
   - Calls `PaperTradingEngine.executeTrade(result)`

6. **PaperTradingEngine.executeTrade(StrategyResult result)** — **THE ENTRY CHOKEPOINT**
   - Checks `existsByStrategyNameAndStatus(strategyName, OPEN)` — skips if already open
   - Creates `PaperTrade` entity from signal
   - Saves to PostgreSQL
   - Sends Telegram alert
   - Returns the created trade

**Confirmed:** This is the **only** path that creates a trade. One caller: `TradingOrchestrator.runTradingCycle()`.

---

## 5. Runtime Flow — Exit

**Three exit paths (all three now with concurrency-safety guard as of 2026-07-15):**

1. **Generic exit:** `PaperTradingEngine.closeTrade(PaperTrade trade, String reason)`
   - Re-fetches trade from DB first (guards against concurrent MTM cycle)
   - Updates status to CLOSED, sets exitTime and exitReason
   - Saves to PostgreSQL
   - Sends Telegram alert

2. **Iron Condor strategy-specific exit:** `PaperTradingEngine.closeIronCondorTrade(PaperTrade trade)`
   - Re-fetches trade from DB first (concurrency guard added 2026-07-15)
   - Updates status to CLOSED, sets exitTime and realizedPnl
   - Saves to PostgreSQL
   - Sends Telegram alert (via `formatIronCondorExitAlert()`)

3. **Spread strategy-specific exit:** `PaperTradingEngine.closeSpreadTrade(PaperTrade trade)`
   - Re-fetches trade from DB first (concurrency guard added 2026-07-15)
   - Updates status to CLOSED, sets exitTime and realizedPnl
   - Saves to PostgreSQL
   - Sends Telegram alert (via `formatExitAlert()`)

**Exit triggers:**
- **DTE_EXIT** — Days-to-expiry threshold reached (strategy-specific: Iron Condor 2 days, Bull Put Spread 1 day, Bear Call Spread 1 day; Expiry Day Strangle uses FORCE_CLOSE_EOD exclusively, not DTE_EXIT)
- **LEG_BREACH** — A leg's LTP breached its strike price (Iron Condor and spreads only; Expiry Day excluded)
- **TARGET_HIT** — Current value ≥ target profit percent
- **STOP_LOSS** — Current value ≤ stop loss percent
- **FORCE_CLOSE_EOD** — Expiry Day Strangle only, forced exit at 3:15 PM IST

**Known limitation:** All three close paths use check-then-act (fetch, check condition, update) rather than atomic conditional update. This is **currently safe only because Spring's scheduler runs single-threaded by default** in this deployment. This is an accepted characteristic, not hidden — documented in CURRENT_STATE.md as a known gap that becomes a live race the moment a thread pool is added for any reason.

**Live price data (as of 2026-07-20):** `calculateIronCondorPnl()`, `calculateSpreadPnl()`, and `fetchCurrentPrices()` all source current option-chain data via `OptionChainService.fetchOptionChain(symbol)` — a cache-first-with-live-Kite-fallback mechanism (try Redis, and on a genuine cache miss, fetch fresh from Kite directly, then re-cache). Previously these three methods read Redis raw and fell back to a stale value on *any* cache miss, never attempting a live fetch — this was the confirmed root cause of a real P&L-corruption incident (2026-07-17). Each method's fallback-to-last-known-value behavior is preserved as the final safety net, now only reached if the live fetch itself also fails (no token, Kite down).

**Multi-lot exit-threshold scaling (fixed 2026-07-20):** `shouldExitIronCondor()`'s and `shouldExitSpread()`'s STOP_LOSS/TARGET_HIT thresholds are derived from `trade.getLegs().get(0).getQuantity()` (the real lot count) multiplied against `trade.getEntryCredit()` — previously these thresholds were computed against the unscaled, per-single-lot `entryCredit` while being compared against a quantity-scaled `unrealizedPnl`, meaning every multi-lot trade's exit thresholds fired roughly `1/lots` too tight in both directions since `max-lots` was first raised above 1 (2026-07-15). See "Data Model" below for the underlying convention this bug violated.

---

## 6. Risk Architecture

**Only real, live-wired, system-wide risk check:**
- `RiskManager.isWithinConcurrentPositionLimit()` — DB-backed, max 3 concurrent open positions (configurable via `risk.max-concurrent-open-positions`)

**Position sizing (separate mechanism, not a risk-limit check):**
- Capital-based: `LotSizeService.getPositionSize(maxLossPerLot)` — `lots = floor((accountCapital × riskPercent) / maxLossPerLot)`, floored at 1, capped at `max-lots` config
- VIX-scaled: VIX > 18 → 0.5x multiplier; VIX ≤ 18 → 1.0x
- Confidence-scaled (Iron Condor only): `multiplier = clamp(0.5 + (score - minScore) / (2 × (100 - minScore)), 0.5, 1.0)`
- Real NIFTY50 lot size: **65** (confirmed via exact arithmetic match against real trade data; a stale, wrong hardcoded constant of `75` in `PaperTradingEngine.LOT_SIZES` was found and corrected 2026-07-20 — it fed directly into `shouldExitSpread()`'s stop-loss calculation)

**Known gaps (logged, not blocking):**
- No daily loss limit — the dead, broken code that once existed in `RiskManager` (zero callers, an incorrect formula) was **deleted entirely** during the 2026-07-15 design review, not merely left in place. A real daily-loss circuit breaker, if ever built, should be DB-backed like the concurrent-position check, not resurrected from the deleted code.
- No capital-at-risk budget across simultaneously open positions (only position count cap)
- No sector exposure check — same as above, the dead no-op implementation was deleted, not left dormant

**In progress — three-gate risk architecture redesign (locked 2026-07-26, Phase 1 implemented 2026-07-26):** A full redesign of entry/sizing/exit incorporating ATR-percentile-based dynamic risk has been designed and locked, with a 4-phase rollout. Phase 1 (persistence plumbing — see `PaperTrade Entity` below for the new fields) is done; the mechanism described in this section (capital-based/VIX-scaled/confidence-scaled sizing, fixed-percentage exits) is **still exactly what actually runs today** — Phase 1 added no new behavior. Full design spec and phase-by-phase detail: `CURRENT_STATE.md`, "Locked Three-Gate Risk Architecture Redesign" section.

---

## 7. Strategy Selection & Market Regime Logic

**See ADR-001: One Strategy Per Market Regime** for full rationale.

**Market condition classification (six values):**
- `SIDEWAYS` — Low VIX (<17), neutral OI bias → **Iron Condor**
- `TRENDING_BULLISH` — Bullish OI bias, upward momentum → **Bull Put Spread**
- `TRENDING_BEARISH` — Bearish OI bias, downward momentum → **Bear Call Spread**
- `EXPIRY_DAY` — Tuesday (NIFTY50 weekly/monthly expiry) → **Expiry Day Strangle**
- `HIGH_VOLATILITY` — VIX > 20 → **No trade** (all strategies skipped)
- `NO_TRADE` — Extreme VIX (>25) or event risk → **No trade** (all strategies skipped)

**Selection mechanism:**
- Direct lookup, not priority-based arbitration (see ADR-001)
- One regime maps to exactly one strategy by design
- If that strategy fails its own entry criteria (confidence score below minimum, or any other gate), no trade is taken — no fallback to a different strategy

---

## 8. Data Model

### PaperTrade Entity

**Fields (all stored in PostgreSQL, `paper_trades` table):**
- `id` (UUID) — Primary key
- `tradeDate` (LocalDate) — Entry date, set once at creation, never updated
- `symbol` (String) — Trading symbol (e.g., "NIFTY50")
- `strategyName` (String) — Strategy name (e.g., "IRON_CONDOR")
- `status` (TradeStatus enum) — OPEN, CLOSED, or CANCELLED
- `legs` (List<TradeLeg>, JSONB) — Multi-leg strategy definition
- `entryCredit` (BigDecimal) — Premium collected at entry, in points *(this doc previously listed this and the next three fields as `Double`; corrected here — see the 2026-07-21 BigDecimal migration, `.clinerules` "Field Types" section)*
- `currentValue` (BigDecimal) — Current mark-to-market value, in points
- `realizedPnl` (BigDecimal) — P&L after closure, in points (only when CLOSED)
- `unrealizedPnl` (BigDecimal) — Current P&L, in points (only when OPEN)
- `maxLoss` (BigDecimal, nullable) — Worst-case loss in rupees, set once at creation from `signal.getMaxLoss()`, used for portfolio-level aggregate risk budgeting (added 2026-07-22)
- `entryTime` (LocalDateTime) — Entry timestamp
- `exitTime` (LocalDateTime) — Exit timestamp (only when CLOSED)
- `exitReason` (String) — Reason for exit (e.g., "TARGET_HIT", "STOP_LOSS", "DTE_EXIT")
- `createdAt`, `updatedAt` (LocalDateTime) — Audit timestamps
- `stopDistancePoints`, `targetThreshold`, `plannedRiskPerLot` (BigDecimal, nullable), `atrPercentile`, `confidenceScore` (Integer, nullable), `riskSnapshot` (String/TEXT, nullable, JSON) — added 2026-07-26, Phase 1 of the three-gate risk architecture redesign (migration `017`). Populated at entry using today's unadjusted formulas; **not yet read by any exit logic** — see "Risk Architecture" section above and `CURRENT_STATE.md`'s "Locked Three-Gate Risk Architecture Redesign" section for the full design and phased rollout. Zero behavior change in Phase 1 — these fields exist purely for data capture ahead of Phase 2+.

**P&L storage convention:** All P&L fields stored in **points, not rupees**. Rupee conversion happens only at display/alert time: `rupeeAmount = pnlPoints × lotSizeService.getLotSize(symbol)`.

**Critical convention, violated once and now fixed (2026-07-20):** `entryCredit` and `currentValue` on `PaperTrade` are stored as **per-single-lot** values — the premium for one lot, never multiplied by the actual quantity held. `unrealizedPnl`/`realizedPnl`, by contrast, **are quantity-scaled** — every leg's contribution multiplies by `leg.getQuantity()` before summing. Any code comparing these two kinds of value directly (e.g., an exit threshold derived from `entryCredit`, compared against `unrealizedPnl`) **must explicitly multiply the per-lot value by the real lot count first** — this is exactly the mismatch that caused `shouldExitIronCondor()`/`shouldExitSpread()`'s STOP_LOSS/TARGET_HIT thresholds to fire too tight by a factor of `lots` for two months. Future code touching these fields should treat this as a standing hazard, not a one-off bug.

### TradeStatus Enum

Three values only (no partial-fill or pending states):
- `OPEN` — Trade is currently active and being monitored
- `CLOSED` — Trade has been exited and P&L is realized
- `CANCELLED` — Trade was cancelled before execution

**Known gap:** No partial-fill or pending states. This is a known limitation that blocks real live-order design work (see ADR-002 and Architectural Gaps below).

### TradeLeg

**Fields:**
- `instrument` (String) — Trading symbol (e.g., "NIFTY26JUN22300CE")
- `action` (Action enum) — BUY or SELL
- `optionType` (OptionType enum) — CE (Call) or PE (Put)
- `strikePrice` (Double) — Strike price
- `expiryDate` (LocalDate) — Expiry date
- `quantity` (Integer) — Quantity in lots (not individual contracts)
- `limitPrice` (Double) — Limit price for order execution
- `legType` (LegType enum) — ENTRY or HEDGE

**LegType enum values:**
- `ENTRY` — Primary income-generating leg
- `HEDGE` — Protective leg to limit downside (defined but currently unreferenced in strategy code — verified via grep, zero references found)

---

## 9. Paper-to-Live Transition — Current State and Known Gaps

**No execution abstraction exists today.** See ADR-002 and ADR-005 for full rationale.

**Current state:**
- `PaperTradingEngine` — Executes trades immediately, records fills as instantaneous, updates position state synchronously
- `OrderExecutionService` — Legacy, targets obsolete `TradingSignal` model (single-instrument signals), incompatible with current multi-leg `TradeSignal` model, zero callers, pending deletion

**Design decision (ADR-005):** Paper and live will be **distinct execution adapters** behind a shared signal/evaluation layer — never a single engine with a paper/live flag toggle. This makes failure modes visible in the code structure.

**Concrete prerequisites before live-order design can begin (per ADR-002):**

1. **Consolidate exit-path work** — Currently three separate close implementations (`closeTrade()`, `closeIronCondorTrade()`, `closeSpreadTrade()`) with inconsistent safety guards. Recommended: make `closeTrade()` the sole exit writer, eliminate the two strategy-specific variants.

2. **Extend TradeStatus and TradeLeg for partial fills** — Current model assumes instantaneous, guaranteed fills (true in paper, false in live). Need:
   - New TradeStatus values: PENDING, PARTIALLY_FILLED
   - Per-leg identity, order reference, and fill-tracking (currently TradeLeg is a write-once JSONB blob)

3. **Design ExecutionAdapter against real broker constraints** — Only after the above two are done. Scope narrowly to the two real chokepoints that exist today: entry (`placeEntry`) and exit (`placeExit`). Do not build speculative interfaces before live requirements are known.

---

## 10. Testing & Evidence Culture

**Test count:** As of the most recent verified test run (2026-07-20), **371 tests pass** (verified via `grep -c "<testcase" build/test-results/test/*.xml | awk -F: '{sum+=$2} END {print sum}'`).

**Test coverage added 2026-07-20** for the five exit/entry-path methods previously confirmed to have zero dedicated coverage — the exact methods that produced multiple real production bugs found via live monitoring:
- `calculateIronCondorPnl()`/`calculateSpreadPnl()`'s Redis-fallback and successful-computation paths
- `closeIronCondorTrade()`/`closeSpreadTrade()`'s concurrency-safety guard
- `markToMarket()`'s STOP_LOSS/TARGET_HIT decision (extracted into a pure, independently-testable `determineMarkToMarketExitTrigger()` method as part of this work)
- The multi-lot exit-threshold scaling fix (`shouldExitIronCondor()`/`shouldExitSpread()`), verified at a realistic 7-lot size

**Known gaps in test coverage (logged, not blocking):**
- `TradingScheduler.executeDailyEntry()`/`executeEntryRetry()` — still zero dedicated unit test coverage (Kite-dependent, token-guarded)
- Full entity-level `Double`→`BigDecimal` migration remains undone (115 call sites, 13 files, properly scoped but not yet started)

**Evidence discipline:** All architectural claims in this document are grounded in verified current code, not narrative. Verification method: direct source-code inspection, grep output, or test execution — never hand-calculation or assumption.

---

## 11. Related Documentation

- **[docs/architecture/decisions/](decisions/)** — Architectural Decision Records (ADR-001 through ADR-005)
  - ADR-001: One Strategy Per Market Regime
  - ADR-002: No Execution Abstraction Until Live Requirements Are Known
  - ADR-003: Sizing vs. Scoring Double-Count Principle
  - ADR-004: Fail-Loud Schema Migrations
  - ADR-005: Distinct Execution Adapters, Not Flag Toggle

- **[docs/architecture/CURRENT_STATE.md](CURRENT_STATE.md)** — Session-by-session implementation history, design review findings, and tracked gaps

- **[README.md](../../README.md)** — API reference, strategy configurations, bug history, and roadmap

---

## Appendix: Database Schema

**Current migrations (15 total):**
- 001-015: Schema creation, table creation, data type corrections, index additions
- Latest (015): Added indexes on `exit_time` and partial unique index on `strategy_name WHERE status='OPEN'`

**Key tables:**
- `paper_trades` — Core trade records (UUID PK, JSONB legs, numeric P&L fields)
- `strategy_execution_logs` — Execution history
- `option_chain_snapshots`, `vix_history`, `oi_snapshots`, `max_pain_history`, `iv_history`, `nifty_daily_prices` — Market data

**Liquibase convention:** Fail-loud migrations (no IF NOT EXISTS, no defensive guards). A migration that fails due to environment drift or data-integrity problems is a desired signal, not a bug.

---

**Document version:** 1.1 | **Last verified:** July 20, 2026, end of day IST
