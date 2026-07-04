# TradeMesh — Current State

**Last updated:** 2026-07-04 (Saturday)
**Maintained as:** a living document — overwritten each session, not appended.
Resolved/stable history migrates into README.md's changelog sections
(e.g. "Key Bugs Fixed") instead of accumulating here.

---

## Source-of-truth rule

If this file, README.md, and any pasted handoff summary ever disagree:
**handoff summary (most recent) > this file > README.md.**
README.md has previously contained Cline-authored narrative sections
(e.g. a "Live Validation Results" section) that were not backed by
real evidence and were corrected. Treat any new README content the
same way — narrative detail is not evidence.

---

## Verified, stable, no open questions

| Stage | What it does | Evidence |
|---|---|---|
| Stage 1 | Entry-side DTE migrated from calendar days to trading days (`MarketCalendarService.countTradingDays()`). `dte-min: 1, dte-max: 4`, derived from real sample data. | Verified — two real bugs caught and fixed (YAML syntax error; `tte` calendar-day/trading-day mixup in Bull Put/Bear Call strike selection, fixed via separate `calendarDte` variable). |
| Stage 3 | VIX panic exit added to all three theta strategies via shared `shouldExitIronCondor()`/`shouldExitSpread()`. `vix-panic-threshold: 25.0`. Highest priority, checked before leg breach/SL/target/force-close. Fail-open on missing VIX data (sentinel -1.0). | 8/8 tests passing. |
| Stage 4a | DTE-based exit condition added (`dte-exit-threshold: 2` Iron Condor, `1` spreads). Built dormant initially (daily force-close still ran). | 13/13 tests passing. |
| Clock fix | Force-close-time checks were using `LocalTime.now()` directly (wall-clock flakiness after 15:15 IST). Fixed via plain field initializer (`Clock.system(...)`, no Spring bean) + `LocalTime` threaded as explicit method parameter to exit-check methods (pure function, fully testable). | 6 rounds to land correctly; final state verified, zero regressions. |
| Stage 4b | Aggregate position cap (`max-concurrent-open-positions: 3`, configurable) via `RiskManager.isWithinConcurrentPositionLimit()` (queries DB directly), wired as final gate in `TradingOrchestrator.runTradingCycle()`. Also fixed a real date-scoping bug: per-strategy duplicate check changed from `existsByStrategyNameAndTradeDateAndStatus` → `existsByStrategyNameAndStatus` (date-independent), needed to correctly detect overnight-held duplicates once Stage 4c shipped. | Verified via real diff + test output. |
| Stage 4c | `scheduledDailyExit()` (3:15 PM) now closes ONLY `EXPIRY_DAY_STRANGLE`. IRON_CONDOR/BULL_PUT_SPREAD/BEAR_CALL_SPREAD no longer force-closed daily — governed entirely by DTE-exit logic. Added `dailySafetyNetCheck()` (9:00 AM MON-FRI) calling `TradingOrchestrator.runDteExitSafetyNetCheck()`, scoped to the three DTE-governed strategies, excludes EXPIRY_DAY_STRANGLE, exit reason `DTE_EXIT_SAFETY_NET`. Extracted shared `PaperTradingEngine.isDteExitDue(trade, cfg, defaultThreshold)` — single source of truth, explicit default-threshold parameter (prevents silent Iron Condor/spread threshold mixup). | **171 tests, 0 failures, full suite.** Required 3 implementation rounds — two silent omissions caught (missing repo method causing compile failure; silently-reverted `shouldExitSpread()` refactor) + one test-coverage gap caught via flat test-count discrepancy. |

**All of the above: unit/JUnit-level verified only.** See "Open / Unverified" below — this is the critical distinction for Stage 4c specifically.

---

## Open / Unverified

### 1. Stage 4c — zero live/runtime observation to date
- No cloud deployment exists. Everything runs on Kuldeep's local laptop, in active development, restarted daily.
- No position has yet existed under Stage 4c's live rules long enough to cross a DTE-exit boundary.
- As of 2026-07-04: **no open position in the market.** `dailySafetyNetCheck()` will correctly find 0 positions — this is expected given no open position exists, and is **not** evidence Stage 4c works; there's simply nothing to test yet.
- **Real validation trigger is not a calendar date** — it's whenever a new IRON_CONDOR / BULL_PUT_SPREAD / BEAR_CALL_SPREAD position actually opens and survives at least one trading-day boundary. Watch for that event, not "Monday" specifically.
- When it happens, capture: entry DTE at open, the trading-day countdown, and the actual exit log line (`DTE_EXIT`, `DTE_EXIT_SAFETY_NET`, or an earlier `STOP_LOSS`/`TARGET_HIT`).

### 2. Zerodha token refresh is a manual, human-dependent step
- Token refresh (~6:00 AM daily) requires Kuldeep to log in manually — this is **accepted operational reality**, not something to fix.
- Consequence: any morning log showing "safety net found 0 / no WARN" must be read alongside whether the token was actually available at that time. Absence of alarm is not proof of correctness if the token-dependent mechanism may not have run at all.
- If Kuldeep's realistic login time is closer to market open (9:15) than the safety net's schedule (9:00 AM), the safety net may structurally WARN-skip every single morning by construction of the daily routine — this is a legitimate future finding, not a bug, and may warrant moving the cron time. Do not act on this until real log evidence shows the pattern.

### 3. Tracked gap — exit logic has no live order-placement path (logged, not scheduled)
- Every exit condition (target, stop-loss, VIX panic, DTE exit, DTE safety net) calls `PaperTradingEngine.closeTrade()`, which **only writes a database row** — no real Kite order is ever placed, for any exit path, system-wide.
- `OrderExecutionService` exists in the codebase but is fully disconnected from the live trading cycle.
- Before live trading: every exit path needs a real Kite order-placement call with confirmation/retry/failure handling.
- Additionally: `dailySafetyNetCheck()`'s 9:00 AM cron fires before NSE market open (9:15 AM) — a sell order cannot execute against a closed exchange. Timing needs reconsideration for live mode (e.g. move to ~9:16 AM, or restructure to queue-and-execute-at-open).
- **Status: logged, not scheduled.** Not to be worked on until live trading integration becomes an active initiative — building this now, with no live-trading design decided, would be solving a problem out of sequence.

### 4. README.md contains a "Paper-Tested Results" section with partially-verified specifics
- June 30 EXPIRY_DAY_STRANGLE: entry/exit timestamps and TARGET_HIT confirmed against real `paper_trades` row (`79661506-...`). **Entry credit was misstated as 31.5 — actual verified value is 30.9.** P&L (+16.05) confirmed correct.
- July 1 BULL_PUT_SPREAD: strikes and entry credit (21.3) confirmed against real row (`8d81333b-...`). The "duplicate entry blocked at 13:45" sub-claim is **not verifiable from the `paper_trades` table** (rejected attempts don't persist there) — needs a real log line or API response to confirm, or should be softened to reflect that it's unverified.
- See README correction section below.

---

## No open Cline task right now

Nothing is mid-flight. No pending proposal. Next real work begins when a DTE-governed strategy opens a new position, or when Kuldeep brings a new proposal.

---

## Session changelog (last 5 sessions, one line each — older entries retire once superseded)

- 2026-07-04: Reconciled README.md vs handoff-summary conflicts (handoff wins). Verified two README claims against real `paper_trades` CSV — one numeric error found (credit 31.5 → 30.9). Confirmed no open position exists; established real Stage 4c validation trigger is "next live entry," not a calendar date. Logged operational reality of manual token refresh. Created this file and a corrected README diff.
- 2026-07-03 (earlier chat): Traced BULL_PUT_SPREAD DTE-exit timing in detail (Wednesday entry → exits Monday morning, first mark-to-market cycle after 9:15 AM). Corrected an over-claim that `dailySafetyNetCheck()` protects live capital today — confirmed `closeTrade()` has zero order-placement capability, system-wide. Logged the live-order-placement gap formally.
- 2026-07-03 (prior session, per handoff): Stage 4c completed — 171 tests, 0 failures. Three implementation rounds, two silent omissions caught.
- Earlier: Stages 1, 3, 4a, 4b, and the Clock-flakiness fix completed and verified (see table above for detail).
