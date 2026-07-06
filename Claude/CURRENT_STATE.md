# TradeMesh — Current State

**Last updated:** 2026-07-06 (Monday, live paper-trading observation session)

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
| Stage 4c scheduler | `scheduledDailyExit()` → `exitPositionsForStrategy("NIFTY50","EXPIRY_DAY_STRANGLE")`. `exitAllPositions()` has zero production callers. | Fixed 2026-07-04 — real diff, grep, 174 tests. |
| @PostLoad fix | `NiftyDailyPrice` & `StrategyExecutionLog`: `isNew` defaults `false`, `@PostLoad` removed, `createNew()` factory added. | Fixed 2026-07-04 — real diff, 174 tests unchanged. |
| **LEG_BREACH** | Implemented for IRON_CONDOR, BULL_PUT_SPREAD, BEAR_CALL_SPREAD (EXPIRY_DAY_STRANGLE explicitly excluded). Reactive, Redis spot price (`price:NIFTY50`), no `.block()`. Priority: VIX_PANIC → DTE_EXIT → LEG_BREACH → STOPLOSS_HIT/TARGET_HIT. | **Implemented 2026-07-06** — real diff, grep, 185 tests. **Implemented ahead of Stage 4c's first live boundary observation, per Kuldeep's explicit override** — confounding risk with Stage 4c root-causing accepted and logged. |
| **DTE boundary overlap fix** | `dte-min` was `<=` `dte-exit-threshold` for all three DTE-governed strategies (BULL_PUT_SPREAD 1/1, BEAR_CALL_SPREAD 1/1, IRON_CONDOR 1/2) — meant a trade entered at the minimum allowed DTE would satisfy its own exit condition on the very next 60-second mark-to-market cycle. Fixed: `dte-min` now 2/2/3 respectively (strictly `>` `dte-exit-threshold`). `@PostConstruct` startup validation added in `StrategiesConfig.java` — fails fast with `IllegalStateException` if any enabled strategy violates `dte-min > dte-exit-threshold`. | **Fixed 2026-07-06** — discovered via live manual paper-trade observation (see below), real diff, 209 tests. |
| **DTE_EXIT time-gate** | Even after the boundary fix, `isDteExitDue()` fired the instant `tradingDaysRemaining <= threshold` with no time-of-day check — meaning on the exit-threshold day, DTE_EXIT fired on the first mark-to-market cycle after market open (~9:15 AM), giving near-zero time to observe overnight gap risk before forced closure. Added `dte-exit-earliest-time` config (default `"10:00"`) — `isDteExitDue()` now only fires on the threshold day at or after this time; fires immediately regardless of time if already past the threshold day (safety-net-equivalent behavior). Signature changed to `isDteExitDue(PaperTrade, StrategyProperties, int, LocalTime)`. | **Fixed 2026-07-06** — real diff, 209 tests, boundary cases covered (exact time, ±1 minute). |
| **FORCE_CLOSE_EOD removed from IRON_CONDOR/spreads** | `shouldExitIronCondor()` had a config-gated FORCE_CLOSE_EOD at 15:15 (`force-close-time: "15:15"` was actively set for IRON_CONDOR — gate was open). `shouldExitSpread()` had an unconditional, hardcoded 15:15 force-close with no config gate at all, applying to both BULL_PUT_SPREAD and BEAR_CALL_SPREAD. **This directly contradicted README's documented Stage 4c architecture** ("FORCE_CLOSE_EOD NO LONGER APPLIES to IRON_CONDOR/spreads") and silently prevented any of these three strategies from ever holding a position overnight — every position was force-closed same-day at 3:15 PM regardless of DTE status, every single day, since this code was never actually removed when Stage 4c was built. Confirmed EXPIRY_DAY_STRANGLE is unaffected — it exits via a fully separate path (`TradingScheduler.scheduledDailyExit()` → `TradingOrchestrator.exitPositionsForStrategy()`, still correctly force-closing at 15:15 daily as required for a same-day-expiry strategy). Removed both blocks; removed `force-close-time` config key from IRON_CONDOR. | **Fixed 2026-07-06** — real diff, grep confirming zero remaining `FORCE_CLOSE_EOD`/`force-close-time` logic outside EXPIRY_DAY_STRANGLE's separate path, 209 tests. **This is likely the single biggest reason Stage 4c has never been observed holding overnight — the mechanism blocking it has now been removed.** |

## Corrected today (2026-07-06) — read before trusting older docs

**This may be the actual explanation for Stage 4c's persistent zero-live-observation status.** Discovered while manually exercising `/trading/run/NIFTY50` in real market hours (see below): a BULL_PUT_SPREAD entered and self-closed via `DTE_EXIT` within 60 seconds, twice in a row. Root-caused to two independent, compounding bugs:

1. **DTE boundary overlap** — entry allowed at the exact DTE value that immediately satisfies exit (`dte-min == dte-exit-threshold`). Fixed.
2. **FORCE_CLOSE_EOD still active for IRON_CONDOR/spreads** — even had (1) been fixed in isolation, these strategies would still force-close every single day at 15:15, making genuine overnight holding structurally impossible regardless of DTE logic. This directly contradicts README's own documentation of Stage 4c's design. Fixed.

Both fixes together are what actually make Stage 4c's core premise — DTE-governed overnight holding — possible for the first time. Previously, no combination of market conditions could have produced a real overnight hold under IRON_CONDOR/BULL_PUT_SPREAD/BEAR_CALL_SPREAD; the architecture documented in README was aspirational, not actually wired end-to-end.

## Live paper-trading observation (2026-07-06, Monday, real market hours)

Manually triggered `/trading/run/NIFTY50` twice during real market hours (13:05, 13:08 IST) using live Kite option-chain data:
- Both times: BULL_PUT_SPREAD entered (24300PE/24200PE, ~13.6-13.7 credit), then closed via `DTE_EXIT` within 60 seconds (next mark-to-market cycle).
- This is what surfaced both bugs above — not a hypothetical, an actual repeated real-market observation.
- No open position exists as of session end; both test trades closed same-minute as designed-at-the-time (now fixed).

Separately verified via live endpoints:
- `/intelligence/{symbol}/summary` Support/Resistance/MaxPain briefly showed identical values (24400/24400/24400) for NIFTY50 — investigated, confirmed to be a **cache-timing artifact**, not a bug. `/intelligence/oi/{symbol}` (uncached) showed correctly differentiated OI walls throughout (24500 call / 24400 put); re-hitting `/summary` after the 5-min cache cycle showed correctly differentiated values too. `generateIntelligence()`'s 5-min TTL is working as designed per `.clinerules` — no code change made.
- `/admin/test/{strategy}-evaluate` endpoints correctly bypass `StrategySelector`'s market-condition gate by design (isolated per-strategy diagnostic testing) — confirmed via grep, both BULL_PUT_SPREAD and BEAR_CALL_SPREAD showing `isExecutable: true` simultaneously from these endpoints is expected, not a bug. Real entry path (`/trading/run/{symbol}`) correctly selects exactly one strategy via `StrategySelector`.

## Open / Unverified

- **Stage 4c still has zero live/runtime observation of a position surviving a trading-day boundary.** Design is now correct for the first time (both blocking bugs fixed today) — no position has yet actually held overnight under the corrected logic. No open position exists as of today. Real validation trigger = next time a DTE-governed strategy opens a position and survives a trading-day boundary — not a calendar date.
- Zerodha token refresh is manual (~6 AM), dependent on Kuldeep's availability — accepted operational reality, not a defect.
- `dailySafetyNetCheck()` at 9:00 AM — now correctly time-gated via the same `dte-exit-earliest-time` logic as the primary path (fixed today, see above); previously would have fired before market open regardless.

## Tracked gaps (logged, not scheduled)

- No exit path anywhere places a real Kite order — all exits, including all of today's fixes, only write to the database. Address only when live trading becomes an active initiative.
- Cosmetic: two stale Javadoc comments in `PaperTradingEngine.java` still reference "force-close-time check" after the logic itself was removed — Tier 1, safe anytime, not yet done.
- Leftover unused mock fields / duplicate stubs accumulated during this session's test-fixing iterations — cosmetic, Tier 1, safe anytime.

## Evidence standard (unchanged)

Risk-tiered, evidence always from Kuldeep, not Cline:
- **Tier 1** (config, docs, logging, read-only, cosmetic): one targeted piece of evidence.
- **Tier 3** (P&L, entry/exit, scheduler wiring, lot size, cache freshness, DB writes): diff of changed methods, targeted grep for "only caller"/"doesn't exist elsewhere" claims, specific test names with old→new count.
- Narrative instead of real command output is rejected at any tier — reconfirmed multiple times today after Cline reported "done" without a passing build on several occasions (LEG_BREACH test-fixing took ~9 round-trips; DTE time-gate signature change missed a third call site + Mockito matcher bugs on first pass). Evidence-first discipline caught every one of these before merge.

## No open Cline task right now

## Session changelog (most recent 5)
- **2026-07-06:** Implemented LEG_BREACH (IRON_CONDOR/BULL_PUT_SPREAD/BEAR_CALL_SPREAD, EXPIRY_DAY_STRANGLE excluded), overriding the standing Stage-4c-observation gate per Kuldeep's explicit acknowledged-risk override. Corrected README's "production-ready" framing and stale test counts. Added `.gitattributes` for LF normalization. Removed dead repo method `existsByStrategyNameAndTradeDateAndStatus`. During live manual paper-trading observation, discovered and fixed two compounding bugs that had silently prevented Stage 4c's core premise (overnight holding) from ever being possible: (1) DTE boundary overlap (`dte-min <= dte-exit-threshold`), fixed with startup validation; (2) FORCE_CLOSE_EOD still active for IRON_CONDOR/spreads despite README claiming removal, force-closing all three strategies daily regardless of DTE — removed. Added `dte-exit-earliest-time` gate so DTE_EXIT doesn't fire in the first minute of the threshold day. 209 tests passing, all committed and pushed.
- 2026-07-04: Adopted final charter (risk-tiered evidence, Kuldeep-sourced). Found & fixed Stage 4c scheduler defect. Ran full 8-item gap sweep; found & fixed `@PostLoad` bug in 2 entities. Generated final CURRENT_STATE.md + handoff summary.
- 2026-07-04 (earlier): Verified 2 README "Paper-Tested" claims against real `paper_trades` rows; found credit-value error (31.5→30.9).
- 2026-07-03: Stage 4c claimed complete, 171 tests — since found incomplete.
