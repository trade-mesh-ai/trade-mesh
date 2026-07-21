## Open Tasks — TradeMesh (as of 2026-07-21)

### Top priority
- **Hosting/uptime decision** — app still runs locally, restarted daily. This is the standing #1 blocker, unresolved across multiple sessions now, with a real causal link to a past P&L-corruption incident.

### Needs a decision (not urgent, but shouldn't sit forever)
- **`calculateAdjustedExitLevels()`** — delete the orphaned ATR-exit code, or wire it in properly (IV/HV-based, backtested)? Deliberately left open today, not resolved.
- **WSL → Windows Postgres networking** — WSL can't reach Postgres. Only matters if WSL becomes a real build shell.
- **`gradlew` Unix tracking** — confirm going forward whether to keep it in git tracking now that it's fixed.

### Deferred, real work, no timeline yet
- **Live trading integration** — no exit path places a real Kite order; all exits are DB-only. Needs its own dedicated design session (execution adapter, partial fills, order confirmation).
- **Strategy/symbol names hardcoded as strings** — large cross-cutting refactor, partially resolved (StrategySelector), rest untouched.
- **Should `ExpiryDayStrangle` be hedged?** — real design question, recommendation given (yes, eventually), not started.
- **Confidence-scoring formula retuning** (spread ATR% bands, PCR edge cases, trend-strength thresholds) — needs 4-6 weeks of real trade data before retuning.
- **Full `BigDecimal` migration — confirm completeness.** Phase 1 done and merged; recommend one fresh grep sweep to confirm no remaining `Double` usage before calling this fully closed.

### Smaller, logged, low urgency
- Skip-reason propagation uses a generic "check logs" message — could be improved.
- BANKNIFTY historical price bootstrap — not started.
- Single-flight de-duplication for `OptionChainService.fetchOptionChain()` — no incident yet, explicitly not worth doing now.
- Option-chain cache TTL (3 min) shorter than its refresh scheduler (5 min) — worth checking if intentional.

### Roadmap / long-term (not started)
- Dynamic/Kelly-criterion position sizing (VIX-based sizing already exists; this is beyond that)
- Iron Condor adjustment/rolling logic (v2, after 4 weeks validated)
- Skew-based wing-width adjustment
- Web dashboard, backtesting framework, portfolio Greeks, multi-account support, global-market correlation

### Standing caveats (not tasks, just don't forget)
- Trade `c5905e56`'s `realizedPnl: 0.00` is confirmed unreliable — exclude from any future performance analysis.
- Zerodha token refresh is manual (~6 AM) — accepted, not a bug.

Want this trimmed to just the top 3, or is the full list useful to keep around?