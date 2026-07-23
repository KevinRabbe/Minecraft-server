# Documentation

This directory is the canonical planning and architecture source for the project.

The project is a Minecraft-first persistent multiplayer game/server framework optimized for **repeatable retained player-hours per developer-hour and recurring maintenance cost**.

## Documentation structure

### `architecture/`
System contracts that implementation must preserve. These documents describe boundaries, authority, state, transactions, gameplay systems, operations, and extension points. They should change only when an architectural decision changes.

### `planning/`
V1 scope, implementation order, and unresolved decisions. This is where work sequencing and explicit deferrals live.

### `reference/`
Stable design laws, terminology, and acceptance criteria used across the rest of the repository.

### `v1/`
Legacy compatibility paths from the first planning pass. These files are being retained only so old links remain useful; canonical content lives in the directories above.

## Read first

1. [`reference/DESIGN_LAWS.md`](reference/DESIGN_LAWS.md)
2. [`planning/V1_SCOPE.md`](planning/V1_SCOPE.md)
3. [`architecture/SYSTEM_OVERVIEW.md`](architecture/SYSTEM_OVERVIEW.md)
4. [`architecture/WORLD_ZONES_INSTANCES.md`](architecture/WORLD_ZONES_INSTANCES.md)
5. [`architecture/AUTHORITY_MODEL.md`](architecture/AUTHORITY_MODEL.md)
6. [`architecture/TRANSACTIONS_AND_ANTI_DUPE.md`](architecture/TRANSACTIONS_AND_ANTI_DUPE.md)
7. [`planning/IMPLEMENTATION_ORDER.md`](planning/IMPLEMENTATION_ORDER.md)

## Decision status convention

Documents use these meanings:

- **Locked** — architectural rule; implementation should conform unless the decision is intentionally reopened.
- **Planned** — expected V1 behavior, but implementation detail may still change without altering the architecture.
- **Balance/config** — numbers and tuning values to be measured in playtests, not hardcoded into architecture.
- **Deferred** — intentionally outside the current V1 launch/access scope.
- **Open** — unresolved and listed in `planning/OPEN_DECISIONS.md`.

## Core summary

- Minecraft supplies movement, inventory UI, building primitives, combat primitives, enchanting, brewing, multiplayer, and other useful mechanics where they already solve the problem.
- Custom systems supply persistent progression, economy, cross-instance state, social systems, controlled resource generation, provenance, projects, and network orchestration.
- Gameplay is organized into **small, dense, purpose-built zones**.
- Concurrent capacity scales by **replicating zone instances**, not by making maps physically larger.
- A **zone** is gameplay, an **instance** is one live copy, and a **backend** is infrastructure hosting one or more instances.
- PostgreSQL is durable authority for persistent network state and critical transactions.
- Velocity routes players; Paper owns moment-to-moment gameplay while it holds the active player-state lease.
- Persistent valuable state must never depend on the lifetime of a disposable zone instance.
