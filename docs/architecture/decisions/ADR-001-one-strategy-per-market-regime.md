# ADR-001: One Strategy Per Market Regime

**Status:** Accepted

**Date:** 2026-07-15

**Deciders:** Principal Engineer design-coherence review

## Context

Market regime classification in TradeMesh is implemented as a mutually-exclusive if/else chain in `StrategySelector`, where each of the four tradeable regimes maps to exactly one strategy:

- `EXPIRY_DAY` → `ExpiryDayStrategy` (EXPIRY_DAY_STRANGLE)
- `SIDEWAYS` → `IronCondorStrategy` (IRON_CONDOR)
- `TRENDING_BULLISH` → `BullPutSpreadStrategy` (BULL_PUT_SPREAD)
- `TRENDING_BEARISH` → `BearCallSpreadStrategy` (BEAR_CALL_SPREAD)

Two additional regimes (`NO_TRADE` and `HIGH_VOLATILITY`) are not tradeable and cause all strategies to be skipped entirely.

This structural constraint means the regime-selection logic always produces a list of at most one applicable strategy for tradeable regimes.

The regime classification thresholds themselves (e.g., SIDEWAYS's IVR/PCR bands) were derived from their target strategy's own entry preconditions. For example, SIDEWAYS's IVR and PCR boundaries are literally the same gates that Iron Condor uses to decide whether to trade on a given day. This means regime match and strategy-specific confidence scoring are two independent signals, not a gap requiring fallback arbitration.

## Decision

**One market regime maps to exactly one strategy. If that strategy fails its own entry criteria (confidence score below minimum, or any other gate), no trade is taken — no fallback to a different strategy for the same regime.**

This principle applies to the four tradeable regimes (EXPIRY_DAY, SIDEWAYS, TRENDING_BULLISH, TRENDING_BEARISH). For the two non-tradeable regimes (NO_TRADE and HIGH_VOLATILITY), the outcome is architecturally different: all strategies are skipped entirely before any regime-to-strategy mapping is evaluated. This is a stronger, earlier exit than "the one applicable strategy failed its own criteria" — it is a blanket market-condition gate that prevents any strategy evaluation at all.

The current implementation (`StrategySelector.selectByPriority()`) is dead architecture: it performs a switch/`findFirst()` on a list that is structurally guaranteed to have size ≤1, making the "priority-based selection" concept a misnomer. The correct replacement is a direct lookup: `applicableStrategies.getFirst()`, with a defensive `log.error()` if size > 1 ever occurs (which would indicate a configuration error, not a scenario to silently arbitrate).

## Consequences

- **Simpler code:** Removes the illusion of multi-candidate arbitration and replaces it with a direct, single-candidate lookup.
- **Clearer intent:** The code now explicitly reflects the design: one regime, one strategy, no fallback.
- **Configuration discipline:** Any future addition of a second strategy to a single regime would require an explicit design decision and code change, not a silent reuse of existing "priority" logic.
- **Failure visibility:** A strategy's failure to meet its own entry criteria is now a clear signal that market conditions are not suitable for any trade today, not a gap to be papered over with a fallback.

## Alternatives Considered

### Option 1: One-regime-one-strategy-no-fallback (Chosen)
Regime thresholds are derived from their target strategy's entry preconditions, so a failed confidence score on top of regime match is two independent signals agreeing that today isn't good enough. No fallback needed.

### Option 2: Multi-candidate arbitration
Build a normalized cross-strategy scoring layer to arbitrate between multiple strategies applicable to the same regime. **Rejected** because:
- No such scoring layer exists today.
- Iron Condor's confidence score (4 factors, 85-point ceiling) and the spreads' confidence scores (5 factors, 100-point ceiling) use deliberately different weights and even opposite-sign PCR logic — they are not directly comparable.
- This would add complexity without addressing a real problem: the regime classification already ensures at most one strategy is applicable.

## References

- **CURRENT_STATE.md, Design Review Findings (2026-07-15), Finding #4:** `StrategySelector.selectByPriority()` is dead architecture; regime classification is mutually-exclusive if/else, each strategy maps to exactly one regime.
- **Source:** `com.trademesh.strategy.StrategySelector.selectByPriority()`
