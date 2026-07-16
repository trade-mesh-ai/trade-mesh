# ADR-005: Distinct Execution Adapters, Not Flag Toggle

**Status:** Accepted

**Date:** 2026-07-15

**Deciders:** Principal Engineer design-coherence review (decision predates review, formalized now)

## Context

The paper-to-live transition requires a design that supports both paper trading (via `PaperTradingEngine`) and live trading (via a future `LiveTradingEngine`). This decision predates the 2026-07-15 design review (made during earlier planning, referenced in handoff notes) but has never been formally recorded in a permanent document.

Paper and live trading differ fundamentally in their failure modes:

- **Paper trading:** Fills are instantaneous and guaranteed by construction. The system's view of a position is always correct.
- **Live trading:** Fills may be partial, orders may be rejected, and reconciliation drift may occur between the system's view and the broker's actual fills. These failure modes are not edge cases; they are normal operational realities.

A single engine with a paper/live flag toggle would hide these differences in the code structure, making the failure modes invisible to future maintainers and creating a false sense that the two modes are interchangeable.

## Decision

**PaperTradingEngine and any future LiveTradingEngine will be distinct execution adapters behind a shared signal/evaluation layer — never a single engine with a paper/live flag toggle.**

The two engines will share the same entry/exit decision logic (strategy evaluation, confidence scoring, position sizing) but implement execution differently:

- **PaperTradingEngine:** Executes trades immediately, records fills as instantaneous, updates position state synchronously.
- **LiveTradingEngine:** Places orders with the broker, handles partial fills, manages reconciliation, updates position state asynchronously based on broker confirmation.

## Consequences

- **Visible failure modes:** The code structure makes it explicit that paper and live trading have different failure modes. A future maintainer reading `PaperTradingEngine` vs `LiveTradingEngine` immediately understands the difference.
- **No false equivalence:** A flag toggle would suggest the two modes are equivalent except for a runtime setting. Distinct adapters make it clear they are fundamentally different implementations.
- **Easier testing:** Each adapter can be tested independently with its own failure scenarios (e.g., partial fills for `LiveTradingEngine`, guaranteed fills for `PaperTradingEngine`).
- **Clearer evolution:** As live trading requirements emerge (partial fills, order rejections, reconciliation), the `LiveTradingEngine` can evolve independently without affecting `PaperTradingEngine`.

## Alternatives Considered

### Option 1: Distinct execution adapters (Chosen)
Two separate engine implementations behind a shared signal/evaluation layer. Failure modes are visible in the code structure.

### Option 2: Single engine with paper/live flag toggle
One engine with conditional logic controlled by a runtime flag. **Rejected** because:
- Failure modes are hidden in conditional branches, not visible in the code structure.
- A flag toggle suggests the two modes are equivalent except for a setting, which is false.
- As live trading requirements emerge, the conditional logic becomes increasingly complex and fragile.
- Testing becomes harder: you must test both code paths for every scenario, and the interaction between the two paths is opaque.

## Related Constraints

This decision constrains ADR-002's future design. When an execution abstraction (ExecutionAdapter interface) is eventually built, it must support this adapter-per-mode structure, not a toggle. The interface should define the contract that both `PaperTradingEngine` and `LiveTradingEngine` implement, with each adapter handling its own failure modes.

## References

- **CURRENT_STATE.md, Architectural gaps, lines 70-71:** "shared signal/evaluation layer feeding two distinct execution adapters behind one interface" design is a stated decision, not code; confirmed via empty search for any adapter/interface pattern.
- **CURRENT_STATE.md, lines 70-71:** Recommended build order when live design work starts: (1) consolidate the three close paths, (2) extend the state model, (3) only then design ExecutionAdapter, scoped narrowly to the two real chokepoints that exist today.
- **Source:** `com.trademesh.execution.PaperTradingEngine`
