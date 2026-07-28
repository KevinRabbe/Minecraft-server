# Change Workflow

Status: **Canonical repository-development workflow.** This governs how implementation work is integrated without changing product or architecture authority.

## Core rule

`main` is the latest qualified integration checkpoint.

Do not use a long-lived pull request as a substitute for `main`. Once a coherent slice is green and internally consistent, integrate it and start the next slice from the new `main` head.

## Branch lifecycle

1. Start each implementation branch from the current qualified `main` head.
2. Give the branch one coherent purpose, for example `agent/map-bootstrap-acquisition` or `agent/restore-rehearsal-support`.
3. Keep documentation, implementation, migrations, tests, and recovery changes for that one purpose together when they are causally related.
4. Before writing to an existing branch, verify its head has not moved unexpectedly.
5. If the work grows into an independent second concern, finish/integrate the first concern and branch again rather than allowing the pull request to become an evergreen development branch.

## Pull-request boundary

A pull request should answer one reviewable question: **what capability or invariant does this change establish?**

A good PR may cross modules when one end-to-end slice genuinely requires it. Artificially splitting a transaction, migration, adapter, and its tests into separate PRs is worse than one coherent cross-module PR.

A PR should not accumulate unrelated future milestones merely because its branch already exists.

## Qualification before integration

Before integration:

- canonical planning and architecture must still agree with the implementation;
- settled decisions must not be silently reopened;
- database migrations and authority changes must have direct tests where practical;
- replay/concurrency/recovery behavior must be tested when the boundary can duplicate, lose, or ambiguously own persistent value;
- the repository build must be green at the exact head being integrated;
- deferred real-client or real-machine evidence must remain explicitly deferred rather than being claimed from unit/integration tests.

A failed CI run is repaired on the same bounded branch. Do not stack an unrelated feature on top of a failing slice.

## Merge strategy

Use **squash merge by default** for ordinary implementation slices. The pull request retains the detailed commit history while `main` receives one coherent checkpoint commit.

Use another merge strategy only when preserving a meaningful authored commit graph on `main` has a concrete benefit.

Do not force-push shared integration branches as routine cleanup. Prefer a new branch/checkpoint unless an explicit recovery operation requires rewriting a ref.

## After integration

The next branch starts from the new `main` head.

Do not continue adding work to the already-merged branch merely because the local checkout still points at it. Historical PR branches are archaeology, not the current integration surface.

## Scope discipline

Repository workflow must not become a substitute for product design:

- balance-only uncertainty stays configurable;
- open product/content decisions remain in `docs/planning/OPEN_DECISIONS.md`;
- empirical acceptance stays in the relevant acceptance document;
- architecture changes are made only when the implementation exposes a real missing/contradictory contract or the product decision was intentionally changed.

The objective is short feedback loops without fragmenting one coherent authority change into meaningless micro-PRs.