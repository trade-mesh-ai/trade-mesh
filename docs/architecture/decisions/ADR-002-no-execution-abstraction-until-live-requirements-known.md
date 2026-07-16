# ADR-002: No Execution Abstraction Until Live Requirements Are Known

**Status:** Accepted

**Date:** 2026-07-15

**Deciders:** Principal Engineer design-coherence review

## Context

`OrderExecutionService` exists in the codebase at `com.trademesh.execution.OrderExecutionService` but is confirmed dead code with zero callers. It was designed to target the `TradingSignal` model (single-instrument signals), which is incompatible with the current `TradeSignal` model used by all active strategies (multi-leg signals).

The paper-to-live transition requires designing an execution abstraction that will eventually support both paper trading (via `PaperTradingEngine`) and live trading (via a future `LiveTradingEngine`). However, the concrete requirements for live execution are not yet known:

- **Multi-leg execution semantics:** Should legs be placed as a basket order (all-or-nothing) or individually? How should partial fills be handled?
- **Order rejection and reconciliation:** How should the system handle broker rejections, partial fills, or reconciliation drift between the system's view and the broker's actual fills?
- **State representation:** The current `TradeStatus` enum (`OPEN`/`CLOSED`/`CANCELLED`) and `TradeLeg` model (write-once JSONB blob) assume instantaneous, guaranteed fills — true in paper trading, false in live trading.

Building an execution abstraction now, before these requirements are known, would result in either:
1. A speculative interface that doesn't match real broker constraints (requiring rework when live requirements emerge)
2. A wrong interface that becomes a dependency for other code, making it harder to change than a wrong implementation would be

## Decision

**No ExecutionAdapter interface (or any execution abstraction) will be built until live-trading requirements are actually known. This applies even to a contract-only interface with no implementation.**

The current `OrderExecutionService` will be retained as a labeled legacy artifact, with zero callers, pending deletion at the dedicated future paper-to-live architecture review once real broker/execution constraints are known.

## Consequences

- **Deferred design work:** The execution abstraction design is explicitly postponed until the paper-to-live migration begins in earnest.
- **No speculative interfaces:** Avoids building a method signature that will inevitably be wrong and become a dependency for other code.
- **Clearer trigger for revisiting:** The decision to build an execution abstraction is explicitly tied to a concrete event (the paper-to-live architecture review with known broker constraints), not to a vague "when we're ready" timeline.
- **Preserved flexibility:** The current entry chokepoint (`PaperTradingEngine.executeTrade()`, one caller) and exit paths remain unchanged, allowing the eventual abstraction to be designed with real constraints in mind.

## Alternatives Considered

### Option 1: No abstraction until live requirements known (Chosen)
Wait for the dedicated paper-to-live architecture review, when real broker/execution constraints are known. Design the abstraction then, with concrete requirements.

### Option 2: Refactor OrderExecutionService to current model
Adapt `OrderExecutionService` to the current `TradeSignal` model and wire it in. **Rejected** because:
- This is speculative design: multi-leg execution semantics (basket orders vs per-leg placement, partial-fill handling) are unresolved.
- Refactoring a component to a model that may still change is wasted effort.
- The component would still not be wired into the actual execution path, so the refactor would be theoretical.

### Option 3: Build fresh ExecutionAdapter interface now, unimplemented
Create a contract-only interface with no implementation, to be filled in later. **Rejected** because:
- A method signature is itself a design decision.
- A wrong interface is arguably worse than a wrong implementation, because interfaces get depended on by other code.
- Once other code depends on the interface, changing it becomes a breaking change.

## Trigger for Revisiting

This decision will be revisited during the dedicated paper-to-live architecture review, once:
- Real broker API constraints are known (order types, partial-fill handling, reconciliation mechanisms)
- The state model has been extended to support partial fills and pending states
- The entry and exit chokepoints have been consolidated (see CURRENT_STATE.md, Design Review Findings, Finding #1)

## References

- **CURRENT_STATE.md, Design Review Findings (2026-07-15), Finding #5:** `OrderExecutionService` is dead code (zero callers, confirmed) and lives in the wrong package; targets obsolete `TradingSignal` model incompatible with current multi-leg `TradeSignal` model.
- **CURRENT_STATE.md, Architectural gaps:** Zero broker/execution abstraction exists anywhere in the codebase; the "shared signal/evaluation layer feeding two distinct execution adapters behind one interface" design is a stated decision, not code.
- **Source:** `com.trademesh.execution.OrderExecutionService`
