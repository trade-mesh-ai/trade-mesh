# TradeMesh — Current State

**Last updated:** 2026-07-04 (Saturday, full-day session)

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
| **Stage 4c scheduler** | `scheduledDailyExit()` → `exitPositionsForStrategy("NIFTY50","EXPIRY_DAY_STRANGLE")`. `exitAllPositions()` has zero production callers. | **Fixed today** — real diff, grep, 174 tests (3 new, negative assertions). |
| **@PostLoad fix** | `NiftyDailyPrice` & `StrategyExecutionLog`: `isNew` now defaults `false`, `@PostLoad` removed, `createNew()` factory added, 3 call sites updated. | **Fixed today** — real diff, 174 tests unchanged (no new tests needed). |

## Corrected today — read before trusting older docs

Stage 4c was claimed "complete" (171 tests) but `exitPositionsForStrategy()`
did not exist — `grep -rn` returned zero matches. Scheduler still called
`exitAllPositions()`, force-closing all strategies daily; overnight
holding never actually ran. Discovered via unsolicited Cline README
validation, confirmed via direct grep, fixed and verified same session
(174 tests, 3 new negative assertions on `exitAllPositions()`).

Separately found via full-codebase gap sweep: `@PostLoad` present in
2 entities, silently ignored by R2DBC (per `.clinerules`), `isNew`
defaulted `true` and never reset after load. `NiftyDailyPrice`: call-site
check showed `saveIfNotExists()` never re-saves a loaded row — bug was
dormant, not actively firing. `StrategyExecutionLog`: only ever
constructed fresh via builder, never loaded-then-resaved — also dormant.
Fixed anyway (same defect class, zero cost to fix now).

## Full gap-sweep results (8 items, all closed)

1. TODOs — 4 found, all legitimate/intentional (SignalService ML
   inference, TickerService WebSocket integration, TradingOrchestrator
   VIX-freshness architectural note) — not bugs, no action.
2. `generateIntelligence` vs `generateFreshIntelligence` — confirmed
   correct: entry path (`runTradingCycle`) uses fresh; `monitorOpenPositions`
   uses cached, with an explicit comment flagging it as intentional
   pending VIX/regime-based exits — matches existing known-open-item.
3. `.block()` — zero matches (one grep hit was a comment, not real code).
4. `@PostLoad` — 2 matches, real bug, fixed (see above).
5. `existsByStrategyNameAndTradeDateAndStatus` — only the repository
   method declaration remains, zero callers. Dead code, not a regression;
   low-priority cleanup candidate, not urgent.
6. `exitAllPositions` callers — zero production callers, confirmed
   post-fix; only referenced in test negative-assertions.
7. Hardcoded lot sizes in strategy package — zero matches, clean.
8. Test-count-vs-comment mismatch — none; `TradingSchedulerTest.java`
   grep ambiguity resolved (3 `@Test` methods, matches expected).

## Open / Unverified

- Stage 4c has **zero live/runtime observation** — design now correct
  (today's fix), but no position has yet held overnight under it.
  No open position exists as of today. Real validation trigger = next
  time a DTE-governed strategy opens a position and survives a
  trading-day boundary — not a calendar date.
- Zerodha token refresh is manual (~6 AM), dependent on Kuldeep's
  availability — accepted operational reality, not a defect.
- `dailySafetyNetCheck()` at 9:00 AM fires before market open (9:15 AM)
  — known, fine for paper mode, revisit for live.

## Tracked gaps (logged, not scheduled)

- **LEG_BREACH exit check is stubbed, not implemented.** Confirmed via
  sed/grep: code has `// Check 2: Leg breach (spot crosses short strike)`
  followed by `// (Skipped for now — would require live spot price from
  market data)` — a comment, no logic. README lists LEG_BREACH as active
  exit priority for IRON_CONDOR/spreads; it is not. Rejected for
  implementation now (would be a new automated layer, blocked by the
  standing Stage 4c gate, and would confound root-causing if something
  breaks after Stage 4c's live observation). **Scheduled: implement
  immediately after Stage 4c's first live trading-day-boundary crossing
  is confirmed, with full JUnit coverage, same review rigor.** README
  also needs a correction noting this gap in the meantime (not yet done).


- No exit path anywhere places a real Kite order — all exits,
  including today's fixes, only write to the database. Address only
  when live trading becomes an active initiative.
- `existsByStrategyNameAndTradeDateAndStatus` dead repository method —
  low-priority removal candidate.
- Mixed line-endings (CRLF creeping into some Cline diffs) caused
  noisy whole-file-looking diffs today — not urgent, consider
  `.gitattributes` normalization at some point.

## Evidence standard (adopted today, final version)

Risk-tiered, evidence always from Kuldeep, not Cline:
- **Tier 1** (config, docs, logging, read-only, cosmetic): one
  targeted piece of evidence.
- **Tier 3** (P&L, entry/exit, scheduler wiring, lot size, cache
  freshness, DB writes): diff of changed methods, targeted grep for
  "only caller"/"doesn't exist elsewhere" claims, specific test names
  with old→new count.
- Narrative instead of real command output is rejected at any tier.

## No open Cline task right now

## Session changelog (most recent 5)
- 2026-07-04: Adopted final charter (risk-tiered evidence, Kuldeep-
  sourced). Found & fixed Stage 4c scheduler defect. Ran full 8-item
  gap sweep; found & fixed `@PostLoad` bug in 2 entities. Closed all
  8 sweep items. Generated final CURRENT_STATE.md + handoff summary.
- 2026-07-04 (earlier): Verified 2 README "Paper-Tested" claims
  against real `paper_trades` rows; found credit-value error (31.5→30.9).
- 2026-07-03: Stage 4c claimed complete, 171 tests — since found incomplete.
