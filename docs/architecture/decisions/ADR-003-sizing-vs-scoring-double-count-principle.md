# ADR-003: Sizing vs. Scoring Double-Count Principle

**Status:** Accepted

**Date:** 2026-07-15

**Deciders:** Principal Engineer design-coherence review (principle established 2026-07-09, promoted to permanent ADR)

## Context

During the implementation of position-sizing features (2026-07-09), VIX was discovered being used **three times simultaneously** for Iron Condor:

1. **Hard entry gate:** `max-vix` configuration (binary: trade or don't trade)
2. **Confidence-score input:** VIX contributed up to 15 of 100 points to the confidence score
3. **Sizing multiplier:** VIX>18 scaled position size to 0.5x, VIX≤18 stayed full size

This triple usage was not a deliberate design choice; it was an artifact of building two independent features (confidence scoring and VIX-based sizing) without coordinating their use of the same factor. The compounding effect between the scoring and sizing treatments was never explicitly chosen, creating an unprincipled double-count.

This pattern is a risk for future work: as new factors are added to sizing multipliers, they may already exist in confidence scores, creating the same unintended compounding.

## Decision

**A factor may have a hard entry gate plus at most ONE graded mechanism (confidence-score input OR sizing multiplier), never both.**

When a new sizing multiplier is added for a factor that is already a confidence-score input, that factor must be removed from the confidence-score formula in the same change. The `min-score` threshold must be recalibrated proportionally to preserve the pass bar, without reweighting the remaining factors.

## Consequences

- **Clearer factor semantics:** Each factor has a single, well-defined role: hard gate, confidence input, or sizing multiplier — not multiple overlapping roles.
- **Predictable compounding:** The interaction between confidence scoring and sizing is now explicit and controlled, not an accidental artifact.
- **Easier future work:** When adding a new sizing multiplier, the rule is clear: check if that factor is already in any confidence score, and remove it if so.

### Consequence Already Applied (2026-07-09)

VIX was removed from Iron Condor's confidence score (was max 15 of 100 points). The remaining factors (IVR, IV/HV, ATR%, PCR) total 85 points unchanged. The `min-score` threshold was recalibrated from 60 to 51, preserving the 60% pass bar against the new 85-point ceiling (60% × 85 ≈ 51). No other factors were reweighted.

## Standing Rule for Future Work

**When an ATR%-based sizing multiplier is eventually added** (currently discussed but not yet built), ATR% must be removed from the confidence-score formula in that same change, following this identical pattern. Do not let ATR% become a second instance of the same unprincipled double-count.

## Alternatives Considered

### Option 1: One graded mechanism per factor (Chosen)
A factor may have a hard gate plus at most one graded mechanism (confidence input OR sizing multiplier). This keeps factor semantics clear and compounding predictable.

### Option 2: Allow multiple graded mechanisms per factor
Permit a factor to be both a confidence-score input and a sizing multiplier simultaneously. **Rejected** because:
- The compounding effect is unprincipled and unintended.
- It makes the factor's contribution to the final position size opaque (is it the confidence score, the sizing multiplier, or both?).
- It creates a maintenance burden: changing one mechanism requires understanding its interaction with the other.

## References

- **CURRENT_STATE.md, lines 100-103:** VIX was found being used three times simultaneously for Iron Condor; principle established 2026-07-09, promoted to permanent ADR.
- **CURRENT_STATE.md, lines 156-158:** Principle established and standing reminder logged for future work (ATR%-based sizing multiplier).
- **Source:** `com.trademesh.strategy.ironcondor.IronCondorStrategy.calculateConfidenceScore()`
