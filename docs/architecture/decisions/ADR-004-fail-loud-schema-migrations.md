# ADR-004: Fail-Loud Schema Migrations

**Status:** Accepted

**Date:** 2026-07-15

**Deciders:** Principal Engineer design-coherence review

## Context

Liquibase schema migrations in TradeMesh are evaluated for defensive idempotency guards (e.g., `IF NOT EXISTS`, preconditions, conditional logic) to protect against environment drift between developer machines. This evaluation was conducted explicitly during the design review (migration 015, adding paper_trades indexes) as part of assessing failure-mode design.

The existing convention in migrations 013 and 014 uses bare `ALTER TABLE` statements with no defensive guards. This approach is consistent with the `.clinerules` established fail-safe-not-fail-open principle (e.g., `MarketCalendarService.isTradingDay()` returns `false` on unknown holidays rather than silently treating them as trading days).

## Decision

**Liquibase schema migrations in this project are deliberately fail-loud — no IF NOT EXISTS, no preconditions, no defensive idempotency guards.**

A migration that fails because an index unexpectedly exists, or because a unique constraint cannot be built due to real duplicate data, is a desired signal. It surfaces environment drift or a real data-integrity problem immediately, rather than silently masking it.

## Consequences

- **Immediate visibility:** Environment drift or data-integrity problems are surfaced at migration time, not discovered later during application operation.
- **Fail-safe principle:** Consistent with the established fail-safe-not-fail-open principle throughout the codebase.
- **Operational discipline:** Requires pre-flight validation before applying migrations, but ensures the system never silently accepts a degraded state.

### Process Requirement

Before applying any migration with a uniqueness constraint, run a pre-flight diagnostic query against real data to confirm it will succeed, rather than discovering a failure at deploy time.

Example: Before applying a migration that adds a `UNIQUE` constraint on a column, query the table to confirm no duplicate values exist:

```sql
SELECT column_name, COUNT(*) as count
FROM table_name
GROUP BY column_name
HAVING COUNT(*) > 1;
```

If duplicates exist, the data-integrity problem must be resolved before the migration can proceed.

## Alternatives Considered

### Option 1: Fail-loud migrations (Chosen)
Migrations fail immediately if preconditions are not met. This surfaces environment drift and data-integrity problems at migration time.

### Option 2: Defensive idempotency guards
Add `IF NOT EXISTS`, preconditions, or conditional logic to migrations to allow them to succeed even if the expected state is not met. **Rejected** because:
- Silent success masks environment drift or data-integrity problems.
- A migration that silently skips a constraint addition leaves the system in a degraded state without alerting operators.
- This violates the fail-safe-not-fail-open principle established in `.clinerules`.

## References

- **CURRENT_STATE.md, Design Review Findings (2026-07-15), lines 59-60:** Evaluated explicitly during the design review (migration 015, adding paper_trades indexes) whether defensive guards should be added given the risk of environment drift between developer machines.
- **CURRENT_STATE.md, lines 59-60:** Rationale: a migration failing because an index unexpectedly exists, or because a unique constraint can't be built due to real duplicate data, is a desired signal — it surfaces environment drift or a real data-integrity problem immediately rather than silently masking it.
- **.clinerules:** Fail-safe-not-fail-open principle (MarketCalendarService.isTradingDay()).
- **Source:** `src/main/resources/db/changelog/changes/013-*.yaml`, `src/main/resources/db/changelog/changes/014-*.yaml`, `src/main/resources/db/changelog/changes/015-*.yaml`
