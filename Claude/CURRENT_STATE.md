# TradeMesh — Current State

**Last updated:** 2026-07-07 (Tuesday, expiry day — live paper-trading + retry mechanism session)

## Source-of-truth rule
Most recent verified evidence > this file > README.md narrative.

## Branch state
- `feature/paper_trade_sit_issues` — **merged to main.** Contains: LEG_BREACH, DTE boundary fix, DTE_EXIT time-gate, FORCE_CLOSE_EOD removal.
- `feature/adding_periodic_entry_retry` — **merging to main.** Contains: latest-entry-time enforcement, periodic entry retry scheduler, retry-loop single-call fix, stale Javadoc cleanup. Merge rationale documented below.

## Verified, stable (merged to main)

| Stage | What it does | Evidence |
|---|---|---|
| Stage 1–4b | Entry DTE, VIX panic, DTE-exit staging, clock fix, position cap | Verified, unchanged |
| Stage 4c scheduler | `exitPositionsForStrategy("NIFTY50","EXPIRY_DAY_STRANGLE")` | Fixed 2026-07-04 |
| @PostLoad fix | 2 entities corrected | Fixed 2026-07-04 |
| **LEG_BREACH** | IRON_CONDOR/BULL_PUT_SPREAD/BEAR_CALL_SPREAD, EXPIRY_DAY_STRANGLE excluded. Reactive, Redis spot (`price:NIFTY50`), no `.block()`. **Implemented ahead of Stage 4c's first live observation — Kuldeep's explicit override, risk logged.** | 2026-07-06, 185 tests |
| **DTE boundary overlap fix** | `dte-min` was `<=` `dte-exit-threshold` for all three strategies — self-closing bug. Fixed: dte-min now 2/2/3. `@PostConstruct` startup validation added (fails fast on violation). | 2026-07-06, 209 tests |
| **DTE_EXIT time-gate** | `dte-exit-earliest-time` (default "10:00") — DTE_EXIT no longer fires in the first minute of the threshold day. | 2026-07-06, 209 tests |
| **FORCE_CLOSE_EOD removed (IRON_CONDOR/spreads)** | Was silently active (config-gated for IRON_CONDOR, hardcoded for spreads), contradicting README, force-closing all three daily regardless of DTE. **Likely the actual reason Stage 4c had zero live observation.** Removed. EXPIRY_DAY_STRANGLE confirmed unaffected (separate scheduler path). | 2026-07-06, 209 tests |

## New this session (2026-07-07) — pending merge on `feature/adding_periodic_entry_retry`

**Periodic entry retry, all 4 strategies.** New automated layer, added ahead of Stage 4c's first live observation — **second explicit gate override this week** (LEG_BREACH was the first). Risk logged: confounds root-causing if something breaks post-observation; accepted per Kuldeep.

- **Latest-entry-time now enforced** for BULL_PUT_SPREAD, BEAR_CALL_SPREAD, IRON_CONDOR (EXPIRY_DAY_STRANGLE already had this check). Previously configured in `application.yml` but never read by 3 of 4 strategies.
- **`scheduledEntryRetry()`** added to `TradingScheduler`, cron `0 45,0,15,30 9-13 * * MON-FRI` — re-attempts entry every 15 min, 9:45 AM–1:30 PM, only when no open position exists for that strategy and current time is before its `latest-entry-time`.
- **Architecture correction applied during review:** initial Cline proposal injected `PaperTradeRepository` directly into `TradingScheduler`, violating `.clinerules` ("never inject repositories directly into schedulers"). Corrected: added `TradingOrchestrator.hasOpenPositionForStrategy()` (reuses existing `existsByStrategyNameAndStatus()`), `TradingScheduler` stays repository-free.
- Reuses existing `runTradingCycle()` / `StrategySelector` / `RiskManager.isWithinConcurrentPositionLimit()` — no duplicated logic, concurrent-position cap confirmed to gate every retry attempt.
- **Found and fixed in passing:** `PaperTradingEngineTest.java` had a hardcoded `today = LocalDate.of(2026, 7, 6)` — silently correct only while wall-clock date matched, broke the moment the calendar advanced past July 6. Fixed to `LocalDate.now(ZoneId.of("Asia/Kolkata"))`.
- **Retry-loop design bug found and fixed same day, via live expiry-day log review:** first implementation of `executeEntryRetry()` looped over all 4 strategy names and called `runTradingCycle("NIFTY50")` once per name — but `runTradingCycle()` is condition-driven via `StrategySelector`, which always picks exactly one strategy regardless of the loop variable. Result: 3 redundant Kite API calls + intelligence fetches per retry cycle, and misleading logs (all 4 strategies reported the same outcome under different names). Confirmed live on 2026-07-07 (expiry day) — retry logs showed IRON_CONDOR/BULL_PUT_SPREAD/BEAR_CALL_SPREAD/EXPIRY_DAY_STRANGLE all logging identical "Premium too low" skip reasons, when only EXPIRY_DAY_STRANGLE should have been evaluated at all that day. Fixed: `StrategySelector.getSelectedStrategyName()` added to resolve the actual selected strategy from one fresh intelligence fetch; pre-flight checks (open-position, latest-entry-time) now applied to that one resolved strategy only; `runTradingCycle()` called exactly once per cycle. A first-pass implementation attempt silently dropped the pre-flight gating entirely (called `runTradingCycle()` unconditionally with dead/no-op code) — caught before merge via evidence review, not accepted on Cline's narrative alone.
- 209 tests, BUILD SUCCESSFUL, pushed to `feature/adding_periodic_entry_retry`. **Not yet merged to main.**

## Live paper-trading observations (2026-07-06 Monday, 2026-07-07 Tuesday/expiry day)

- Manually triggered `/trading/run/NIFTY50` in real market hours multiple times across two sessions. This is what originally surfaced the DTE boundary + FORCE_CLOSE_EOD bugs (July 6) and confirmed EXPIRY_DAY strategy's OI-wall structural-validity hard-skip is working correctly (July 7 — spot moved above the call wall mid-morning, correctly triggered the existing "OI walls structurally invalid" skip per Bug 8's safety gate).
- `/intelligence/{symbol}/summary` Support/Resistance/MaxPain briefly collapsed to identical values for NIFTY50 — investigated, confirmed cache-timing artifact (5-min TTL), not a bug. No code change.
- `/admin/test/{strategy}-evaluate` endpoints confirmed to correctly bypass `StrategySelector`'s condition gate by design (isolated diagnostics) — both spreads showing `isExecutable:true` simultaneously from these endpoints is expected; real entry path (`/trading/run/{symbol}`) correctly selects one strategy.
- Confirmed `scheduledDailyEntry()` cron is `0 30 9 * * MON-FRI` (9:30 AM) in production — a temporary local test change to `0 40 11...` was reverted before session end, never committed.

## Open / Unverified

- **Stage 4c still has zero live/runtime observation of a position surviving a trading-day boundary** — design is now correct for the first time (FORCE_CLOSE_EOD + DTE boundary fixes on main). No open position exists as of session end. Real validation trigger = next time a DTE-governed strategy opens a position and survives a trading-day boundary.
- **Merge decision made:** `feature/adding_periodic_entry_retry` will be merged to main. Rationale: without the retry mechanism, entries only happen once daily at 9:30 AM and may miss the window entirely on any given day — this slows arrival of Stage 4c's first observation rather than protecting it. The two features (entry retry, exit timing) don't interact with each other's core logic, so merging doesn't confound root-causing between them. Same evidence discipline applies to whatever happens next regardless of branch state.
- Zerodha token refresh remains manual (~6 AM) — accepted operational reality.

## Tracked gaps (logged, not scheduled)

- No exit path places a real Kite order — all exits remain DB-only. Address only when live trading becomes active.
- Two stale Javadoc comments in `PaperTradingEngine.java` still reference "force-close-time check" after removal — Tier 1, cosmetic, not yet done.
- Leftover unused mock fields from this week's test-fixing iterations — cosmetic, Tier 1, safe anytime.

## Evidence standard (unchanged)
Risk-tiered, evidence always from Kuldeep, not Cline. Tier 1: one targeted command. Tier 3 (entry/exit, scheduler, DB writes): diff + grep + test names/counts. Narrative without real command output rejected at any tier — reconfirmed repeatedly this week; several "done" reports from Cline were incomplete or fabricated (missed call sites, unused imports, mismatched Mockito matchers, stale hardcoded test dates) and only caught because build/test evidence was demanded before acceptance.

## No open Cline task right now

## Session changelog (most recent 5)
- **2026-07-07:** Observed EXPIRY_DAY_STRANGLE correctly hard-skip on real expiry day due to OI-wall structural invalidity (spot moved above call wall) — confirmed working as designed, no action. Implemented periodic entry-retry mechanism for all 4 strategies (second explicit gate override this week) on new branch `feature/adding_periodic_entry_retry`: latest-entry-time now enforced everywhere, 15-min retry cadence 9:45–13:30, kept scheduler repository-free per `.clinerules` after catching an initial design violation. Fixed a hardcoded test date that silently broke as the calendar advanced. Live log review on expiry day surfaced a second retry-loop design bug (calling `runTradingCycle()` once per strategy name instead of once per cycle, causing 3x redundant API calls and misleading logs) — fixed via `StrategySelector.getSelectedStrategyName()`, with a first-pass Cline implementation rejected before merge for silently dropping the pre-flight gating it claimed to include. 209 tests throughout, pushed, not yet merged.
- **2026-07-06:** Merged `feature/paper_trade_sit_issues` to main. Implemented LEG_BREACH (first gate override). During live market-hours testing, discovered and fixed the DTE boundary overlap and FORCE_CLOSE_EOD bugs — likely the actual explanation for Stage 4c's persistent zero-observation status. Added DTE_EXIT time-gate. README correction, `.gitattributes`, dead repo method cleanup also closed. 209 tests.
- 2026-07-04: Adopted final evidence charter. Fixed Stage 4c scheduler defect and `@PostLoad` bug via full gap sweep.
- 2026-07-03: Stage 4c claimed complete, 171 tests — since found incomplete.
