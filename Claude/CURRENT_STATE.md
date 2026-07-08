# TradeMesh — Current State

**Last updated:** 2026-07-05 (Sunday, code-review session)

## Source-of-truth rule
Most recent verified evidence > this file > README.md narrative.

## Verified, stable

| Stage | What it does | Evidence |
|---|---|---|
| Stage 1 | Entry DTE, trading-day based | Verified |
| Stage 3 | VIX panic exit, 3 strategies | 8/8 tests |
| Stage 4a | DTE-based exit (dormant staging) | 13/13 tests |
| Clock fix | `LocalTime` param, no Spring bean | Verified |
| Stage 4b | Position cap + date-scoping fix | Verified |
| Stage 4c scheduler | `scheduledDailyExit()` → `exitPositionsForStrategy("NIFTY50","EXPIRY_DAY_STRANGLE")`. `exitAllPositions()` has zero production callers. | Fixed 2026-07-04 — real diff, grep, 174 tests (3 new, negative assertions). |
| @PostLoad fix | `NiftyDailyPrice` & `StrategyExecutionLog`: `isNew` now defaults `false`, `@PostLoad` removed, `createNew()` factory added, 3 call sites updated. | Fixed 2026-07-04 — real diff, 174 tests unchanged. |

## Closed this session (2026-07-05)

- **Dead repository method removed:** `existsByStrategyNameAndTradeDateAndStatus` deleted from `PaperTradeRepository`. Confirmed via full-codebase grep — zero matches anywhere. `existsByStrategyNameAndStatus` (the actually-used method) untouched.

## Open / Unverified — Stage 4c live observation

- Stage 4c has **zero live/runtime observation**. Design correct (2026-07-04 fix), but no position has yet held overnight under it. No open position exists as of this session. Real validation trigger = next time a DTE-governed strategy opens a position and survives a trading-day boundary — not a calendar date.
- **Standing gate unchanged:** no new automated layers until Stage 4c's first live trading-day-boundary crossing is confirmed.
- Zerodha token refresh is manual (~6 AM), dependent on Kuldeep's availability — accepted operational reality, not a defect.

## Tracked gaps — found this session (2026-07-05 code review), none actioned, none touch the Stage 4c gate

All Tier 1 unless noted. Evidence commands given where already run; others are proposed for whenever Kuldeep wants to action them.

1. **`KiteHistoricalService.fetchHistoricalOHLC()` — missing null filter.** `parseCandle()` can return `null` on a malformed candle; no `.filter(Objects::nonNull)` after `.map(this::parseCandle)`. Confirmed via grep — zero `filter` calls in file. Risk: a single malformed candle from Kite silently poisons the Flux and NPEs downstream in `saveIfNotExists()`, killing the rest of a historical bootstrap batch. **Tier 3** (touches DB write path) despite being a one-line fix — evidence: diff + grep confirming filter added, re-run of bootstrap endpoint.

2. **`IvHistoryService.calculateAndSaveAtmIv()` — idempotency check runs after the expensive fetch, not before.** Calls `calculateAtmIv()` (which fetches full option chain) before checking `existsBySymbolAndTradeDate()`. Wasteful when IV already recorded for today, not incorrect. Fix: reorder the check first.

3. **`OIAnalysisScheduler` — missing `trackTokenMissing()` call.** Unlike `MaxPainScheduler` and `IntelligenceScheduler`, `captureAnalysis()` has no `onErrorResume`/`doOnError` block registering token failures with `TokenAvailabilityTracker`. Inconsistent sibling pattern across three near-identical schedulers.

4. **`OIAnalysis.data` column — dead, write-only, and buggy.** Confirmed via grep: zero callers of `getData()` anywhere in codebase. Duplicates `topCallWallsJson`/`topPutWallsJson` with a third, incomplete copy (serialization-order bug means `data` is set before the wall JSON fields are populated). Two prior migrations (012, 013) already flagged this column as "supplementary, not critical." **Recommendation: drop column, not fix the bug** — remove `data` field, `@Column("data")`, and `analysis.setData(...)` call from `OIAnalyserService`; add Liquibase migration `014-drop-oi-snapshots-data-column.yaml`. Batch with dead-method-style cleanup.

5. **`MarkToMarketScheduler` — missing token availability guard.** Confirmed via grep: zero matches for `tokenTracker`/`TokenAvailabilityTracker` in `PaperTradingEngine.java`. Every other scheduler (`IntelligenceScheduler`, `IvHistoryScheduler`, `MaxPainScheduler`, `OIAnalysisScheduler`, `VixScheduler.fetchVixData`) checks token availability before Kite calls; this one doesn't, per `.clinerules`.

6. **`VixScheduler.persistVixData()` — missing token guard.** Same class of gap as #5; `fetchVixData()` on the same class has the guard, `persistVixData()` doesn't. Low real risk (internally handled via `onErrorResume` in `VixMonitorService`), but inconsistent.

7. **`VixMonitorService.fetchAndSave()` can persist a stale cached VIX value under a fresh timestamp.** `fetchAndSave()` → `fetchCurrentVix()` reads cache first (5-min TTL); persisted at 15-min intervals, so a value up to 5 minutes stale can be written with `recorded_at = Instant.now()`, implying freshness it doesn't have. Acceptable given the interval design; not urgent.

8. **`MaxPainCalculatorService` — dead redundant private `getLotSize()` wrapper.** Duplicates the inline `lotSizeService.getLotSize(symbol)` call already used in `performCalculation()`. Unused elsewhere in the file. Cosmetic.

9. **`dailySafetyNetCheck()` cron mistimed for live mode.** Fires 9:00 AM, before NSE open (9:15 AM) — cannot place a corrective exit order at that time; harmless in paper mode (no real order ever fires). Correct fix is **not** moving to 9:15/9:16 (creates a race with `MarkToMarketScheduler`'s first ticks acting on the same positions). Target: **~9:20 AM**, giving the primary mark-to-market cycle 5 clean ticks first, preserving the safety net as genuinely redundant rather than competing. Also missing the `marketCalendarService.isMarketOpen()` filter that the other three `TradingScheduler` methods have — runs unconditionally on any weekday including holidays. Explicitly considered and rejected: removing the method entirely — it's the only backstop against a silent primary-mechanism failure (same failure class as the already-caught Stage 4c scheduler bug). Batch this fix with the live-order-placement design cycle — same exit path, same review pass.

10. **`MarketCalendarService.isTradingDay()` fails open on missing year data.** If a year isn't in the hardcoded `NSE_HOLIDAYS` map (e.g. 2027 arrives and nobody's updated the file), the method returns `true` with only a WARN log — silently treating every NSE holiday as a trading day. Storage location (static map vs. YAML vs. DB) was evaluated and is **not** the fix — annual maintenance is unavoidable regardless of where the data lives, and a DB/YAML migration adds cost (round-trips or config-binding overhead) for ~12 rows/year with no live-mode benefit today. **Actual fix:** change the fallback to fail safe (`return false` + ERROR log/alert) instead of failing open. Harmless in paper mode; **must fix before live go-live** since it directly gates order-placement timing. Practical mitigation: annual reminder to add next year's holiday set before December.

11. **`TradingScheduler` — double-subscribe pattern.** `executeDailyEntry`, `scheduledDailyExit`, `scheduledDailyReport` each wrap an inner `.subscribe()` inside `Mono.fromRunnable(...)` which is itself inside an outer `.subscribe()`. Functionally harmless (both complete correctly) but unnecessarily complex — three near-identical instances. Readability cleanup candidate, not a bug, not urgent.

## Full gap-sweep results (2026-07-04, 8 items, all closed)

1. TODOs — 4 found, all legitimate/intentional (SignalService ML inference, TickerService WebSocket integration, TradingOrchestrator VIX-freshness architectural note) — not bugs, no action.
2. `generateIntelligence` vs `generateFreshIntelligence` — confirmed correct: entry path uses fresh; `monitorOpenPositions` uses cached, with explicit comment flagging as intentional pending VIX/regime-based exits.
3. `.block()` — zero matches (one grep hit was a comment, not real code).
4. `@PostLoad` — 2 matches, real bug, fixed.
5. `existsByStrategyNameAndTradeDateAndStatus` — dead code, confirmed zero callers 2026-07-04, **removed 2026-07-05**.
6. `exitAllPositions` callers — zero production callers, confirmed post-fix.
7. Hardcoded lot sizes in strategy package — zero matches, clean.
8. Test-count-vs-comment mismatch — none.

## Tracked gaps — carried forward, not scheduled

- **LEG_BREACH exit check is stubbed, not implemented.** Confirmed via sed/grep: comment only, no logic. README lists it as active exit priority for IRON_CONDOR/spreads; it is not. Rejected for implementation now — new automated layer, blocked by standing Stage 4c gate. **Scheduled: implement immediately after Stage 4c's first live trading-day-boundary crossing is confirmed**, full JUnit coverage, same review rigor. README correction still pending.
- **`OrderExecutionService` — confirmed dead code, zero production callers.** Not a bug; premature Phase 4 scaffolding sitting unused. No action until live trading's dedicated design cycle opens. Do not extend further until then.
- No exit path anywhere places a real Kite order — all exits (including today's fixes) only write to the database. Address only when live trading becomes an active initiative; requires its own dedicated design cycle (order placement, confirmation/retry, alerting, timing across every exit path).
- Mixed line-endings (CRLF creeping into Cline diffs) — not urgent, consider `.gitattributes` normalization at some point.

## Evidence standard (unchanged)

Risk-tiered, evidence always from Kuldeep, not Cline:
- **Tier 1** (config, docs, logging, read-only, cosmetic): one targeted piece of evidence.
- **Tier 3** (P&L, entry/exit, scheduler wiring, lot size, cache freshness, DB writes): diff of changed methods, targeted grep for "only caller"/"doesn't exist elsewhere" claims, specific test names with old→new count.
- Narrative instead of real command output is rejected at any tier.
- Kuldeep's override right: if a risk is explicitly acknowledged and Kuldeep says "proceed anyway," document the risk in one line and proceed without further resistance. Paper mode accepts higher risk than live mode — calibrate accordingly.

## No open Cline task right now

## Session changelog (most recent 6)
- 2026-07-05: Full code-review pass across execution, intelligence (market intelligence, IV, max pain, OI, VIX), and trading-scheduler packages. Confirmed removal of dead repository method. Found and logged 11 new tracked gaps (Tier 1/3), all deliberately not actioned — none touch the Stage 4c gate. Notable: identified and corrected own initial recommendation on `dailySafetyNetCheck` retiming (9:15 rejected due to race with MarkToMarketScheduler; 9:20 is correct). Rejected proposal to remove `dailySafetyNetCheck()` entirely. Rejected YAML/DB migration for NSE holiday storage as solving the wrong problem; identified actual defect (fail-open fallback) instead.
- 2026-07-04: Adopted final charter (risk-tiered evidence, Kuldeep-sourced). Found & fixed Stage 4c scheduler defect. Ran full 8-item gap sweep; found & fixed `@PostLoad` bug in 2 entities. Closed all 8 sweep items. Generated CURRENT_STATE.md + handoff summary.
- 2026-07-04 (earlier): Verified 2 README "Paper-Tested" claims against real `paper_trades` rows; found credit-value error (31.5→30.9).
- 2026-07-03: Stage 4c claimed complete, 171 tests — since found incomplete.
