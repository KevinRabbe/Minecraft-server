# Planning Hierarchy

This file prevents stale documents from silently reopening settled decisions.

## Canonical precedence

When two planning/reference documents appear to conflict, resolve them in this order:

1. `docs/reference/DESIGN_LAWS.md` — stable architectural/product laws.
2. `docs/planning/V1_SCOPE.md` — what V1/Day-0 must or may contain.
3. `docs/planning/MASTER_ROADMAP.md` — dependency order and milestone intent.
4. `docs/planning/IMPLEMENTATION_ORDER.md` — current execution sequence/status.
5. `docs/reference/ACCEPTANCE_CRITERIA.md` — observable proof that V1 is safe/complete.
6. `docs/planning/OPEN_DECISIONS.md` — only genuinely unresolved choices; it cannot override a locked decision above.
7. `docs/architecture/*` — implementation contracts that must be updated when a higher-level locked decision changes.
8. `docs/v1/*` — legacy compatibility material only; never authoritative over canonical planning/architecture.

If a lower-precedence document contradicts a higher-precedence locked decision, update the stale document; do not reopen the decision by default.

## Decision statuses

- **Locked** — implementation constraint; reopen only by explicit decision or direct contradiction.
- **Planned** — expected behavior whose internal implementation may still change without altering the product contract.
- **Balance/config** — cheap tunable numbers/content data; do not block architecture work.
- **Deferred** — intentionally outside Day-0/V1 requirement.
- **Open** — genuinely unresolved and listed in `OPEN_DECISIONS.md`.

## Change-control rule

When a new design discussion settles something:

1. update `V1_SCOPE.md` if launch/product scope changes;
2. update `DESIGN_LAWS.md` only if it creates a durable cross-system rule;
3. update `MASTER_ROADMAP.md`/`IMPLEMENTATION_ORDER.md` if sequencing changes;
4. remove the item from `OPEN_DECISIONS.md` if it is no longer open;
5. update architecture contracts before implementing code that depends on the changed decision;
6. update acceptance criteria so the new contract is testable.

## Architecture-first rule

Planning completion does **not** mean immediately coding the first feature milestone in isolation. After planning is consistent, first complete the cross-cutting architecture contracts for all settled V1 systems. Existing proven architecture/code is retained; only missing/contradictory boundaries are added or corrected.

## Balance rule

Do not create planning churn around values such as 118 versus 123 damage, exact XP requirements, exact interest percentages, exact boss HP, exact Map scaling, or exact bounty kill counts. Record only the configurable mechanism and a broad target envelope where one matters structurally.

## Settled world-agency rule

World expansion order and ordinary district form are player outcomes. No planning document may introduce a hidden preferred route, canonical district blueprint, required appearance, or minimum ordinary-district build size unless the decision is explicitly reopened.