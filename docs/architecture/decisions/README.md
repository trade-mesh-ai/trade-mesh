# Architecture Decision Records (ADRs)

This directory contains Architecture Decision Records (ADRs) documenting real design decisions made during TradeMesh development. ADRs are permanent, append-only records — once written and accepted, an ADR is never edited to reverse a decision. A reversal gets a NEW ADR that supersedes the old one and says so explicitly.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [ADR-001](ADR-001-one-strategy-per-market-regime.md) | One Strategy Per Market Regime | Accepted |
| [ADR-002](ADR-002-no-execution-abstraction-until-live-requirements-known.md) | No Execution Abstraction Until Live Requirements Are Known | Accepted |
| [ADR-003](ADR-003-sizing-vs-scoring-double-count-principle.md) | Sizing vs. Scoring Double-Count Principle | Accepted |
| [ADR-004](ADR-004-fail-loud-schema-migrations.md) | Fail-Loud Schema Migrations | Accepted |
| [ADR-005](ADR-005-distinct-execution-adapters-not-flag-toggle.md) | Distinct Execution Adapters, Not Flag Toggle | Accepted |

## How to Use This Directory

- **For understanding a design decision:** Read the relevant ADR file. Each ADR includes Context, Decision, Consequences, and Alternatives Considered.
- **For proposing a new decision:** Create a new ADR file following the standard format (see any existing ADR for the template).
- **For reversing a decision:** Do NOT edit the existing ADR. Instead, create a new ADR that explicitly references the old one and explains why it is being superseded.

## References

All ADRs in this directory are grounded in evidence from:
- **CURRENT_STATE.md, Design Review Findings (2026-07-15):** Principal Engineer design-coherence review conducted across 5 sections (capital & risk architecture, strategy selection & market-regime logic, paper-to-live transition, failure-mode design, data & state model).
- **Source code:** Direct inspection of implementation files referenced in each ADR.
