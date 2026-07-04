# TradeMesh — Current State

**Last updated:** 2026-07-04 (Saturday, night session)

## Source-of-truth rule
Most recent verified evidence > this file > README.md narrative sections.

## Verified, stable

| Stage | What it does | Evidence |
|---|---|---|
| Stage 1 | Entry-side DTE, trading-day based | Verified, real bugs fixed |
| Stage 3 | VIX panic exit, all 3 strategies | 8/8 tests |
| Stage 4a | DTE-based exit (dormant staging) | 13/13 tests |
| Clock fix | `LocalTime` param, no Spring bean | Verified, zero regressions |
| Stage 4b | Position cap + date-scoping fix | Verified via diff + tests |
| **Stage 4c — scheduler wiring** | `scheduledDailyExit()` calls `exitPositionsForStrategy("NIFTY50","EXPIRY_DAY_STRANGLE")`. `exitAllPositions()` has zero remaining production callers. | **Fixed and verified tonight** — real diff, real grep, 174 testcases (`grep -c "<testcase"` on JUnit XML), 3 new negative-assertion tests (`verify(..., never()).exitAllPositions(...)`). |

## Corrected tonight — read this before trusting older docs

Prior claims (handoff summary, earlier CURRENT_STATE.md, README) stated
Stage 4c was "complete" based on 171 unit tests. **This was false in one
specific way**: `TradingOrchestrator.exitPositionsForStrategy()` did not
exist anywhere in the codebase — confirmed via `grep -rn` returning zero
matches. `scheduledDailyExit()` was still calling `exitAllPositions("NIFTY50")`,
force-closing ALL strategies daily, meaning overnight holding for
IRON_CONDOR/BULL_PUT_SPREAD/BEAR_CALL_SPREAD never actually existed in
the running scheduler, despite passing tests. The 171 tests verified
exit-condition *logic* in isolation; nothing verified the *scheduler's
call site* until tonight — that gap is what let this ship unnoticed.

Discovered via an unsolicited Cline README-validation pass, confirmed
independently via direct grep before any fix was trusted. Fixed same
session: new method created, scheduler updated, 3 new tests added
(174 total), all verified via raw command output, not narrative.

A second, later Cline "re-validation" report re-raised the same finding
as if still open — confirmed via fresh grep that the fix is intact;
that second report was stale/working from old context and is disregarded.

## Open / Unverified

- Stage 4c still has **zero live/runtime observation** — the fix above
  makes the *design* correct; it has not yet been observed holding a
  real position overnight. No open position exists as of tonight.
- Zerodha token refresh is manual (~6 AM), dependent on Kuldeep's
  availability — accepted operational reality, not a defect.
- `dailySafetyNetCheck()` at 9:00 AM fires before market open (9:15 AM)
  — known timing limitation, fine for paper mode, needs revisit for live.

## Tracked gaps (logged, not scheduled)
- No exit path anywhere places a real Kite order — all exits, including
  the newly-fixed one, only write to the database. Must be addressed
  before live trading, not before.

## Evidence standard (adopted this session)
Risk-tiered: Tier 1 (low risk) = one targeted piece of evidence, from
Kuldeep, not Cline. Tier 3 (P&L, entry/exit, scheduler wiring, lot size,
cache freshness, DB writes) = diff of changed methods, targeted grep for
"only caller"/"doesn't exist elsewhere" claims, specific test names with
old→new count. Narrative instead of command output is rejected at any tier.

## No open Cline task right now

## Session changelog
- 2026-07-04 (night): Found and fixed Stage 4c scheduler defect (see
  above). Adopted risk-tiered evidence standard. Updated this file and
  README.md.
- 2026-07-04 (earlier): Verified 2 README "Paper-Tested" claims against
  real `paper_trades` rows; found one credit-value error (31.5→30.9).
- 2026-07-03: Stage 4c claimed complete, 171 tests — since found incomplete.
