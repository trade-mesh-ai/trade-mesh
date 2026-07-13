Nothing urgent is queued — here's the actual state of things and the real options in front of you:

**Waiting on time, not code:**
- Stage 4c's first live overnight observation — no engineering work left to do, just needs a DTE-governed position to survive a trading-day boundary. Still on local, restarts daily — worth revisiting whether you want to solve that infrastructure problem now that position-sizing work is done, since it's the actual blocker to ever observing this.

**Logged, real, deliberately deferred (in priority order I'd suggest):**
1. **Merge `feature/position_sizing_vix_lot_multiplier` to main** — 292 tests, thoroughly verified, no reason to leave it sitting on a branch unless you want more live observation on it first.
2. **Confidence-scoring for Bull Put Spread / Bear Call Spread** — real gap, needs a genuine design pass (PCR flips meaning, ATR% threshold changes, possible new trend-strength factor).
3. **ExpiryDayStrategy position sizing** — needs a different risk-per-lot formula (margin-based or stop-loss-implied, no `wingWidth` to lean on).
4. **Strategy-name/symbol hardcoding refactor** — large, cross-cutting, correctly deferred twice now.
5. **Live trading integration design** — order placement, confirmation/retry, reconciliation; explicitly logged as its own dedicated session, not incremental work.

**Not logged, worth asking:** do you want to merge the position-sizing branch now, or let it accumulate more real trading days on the branch first before trusting it on main?

Where do you want to go?