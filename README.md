# Minecraft Server

Minecraft-first persistent multiplayer game/server framework.

## Objective

The project is optimized for **repeatable retained player-hours generated per developer-hour and recurring maintenance cost**.

Minecraft supplies useful primitives such as movement, building, inventory UI, combat, enchanting, brewing, and multiplayer. Custom code supplies the persistent game layer: progression, economy, controlled resource generation, scalable PvE, social systems, provenance/history, player-directed world expansion, cross-instance player state, and network routing.

## Current architecture direction

- deliberately small, dense, purpose-built gameplay zones rather than endless vanilla wilderness
- capacity scales by replicating zone instances, not by making maps larger
- instance count is driven by concurrent demand in that specific zone, independent of total network population
- zone = gameplay definition; instance = one live copy; backend = infrastructure hosting instances
- PostgreSQL = durable persistent authority; Velocity = routing; Paper = live gameplay while it holds player-state ownership
- single-writer player state and versioned transfers
- atomic/idempotent value movement with escrow/pending delivery for important transactions
- Coin pocket + protected Bank Manager
- player-driven Bazaar/Auction economy
- bounded individualized gear rolls; perfect rolls are optional luxury optimization
- Portal/Map scalable PvE plus mob-family Bounties as launch PvE pillars
- player-directed expansion voting; ordinary district form/scale is player-created rather than blueprint-driven
- Nether/End are later major power milestones rather than Map-difficulty permission gates

## Repository

- `common/` — infrastructure-neutral domain contracts/shared logic
- `paper/` — Paper gameplay/backend adapter
- `velocity/` — Velocity network/routing adapter
- `resource-pack/` — presentation assets
- `infra/` — local/deployment infrastructure
- `docs/` — canonical architecture, planning, and reference documentation

## Documentation

Start at [`docs/README.md`](docs/README.md).

Planning/reference entrypoints:

1. [`docs/planning/PLANNING_HIERARCHY.md`](docs/planning/PLANNING_HIERARCHY.md)
2. [`docs/reference/DESIGN_LAWS.md`](docs/reference/DESIGN_LAWS.md)
3. [`docs/planning/V1_SCOPE.md`](docs/planning/V1_SCOPE.md)
4. [`docs/planning/MASTER_ROADMAP.md`](docs/planning/MASTER_ROADMAP.md)
5. [`docs/planning/IMPLEMENTATION_ORDER.md`](docs/planning/IMPLEMENTATION_ORDER.md)
6. [`docs/reference/ACCEPTANCE_CRITERIA.md`](docs/reference/ACCEPTANCE_CRITERIA.md)
7. [`docs/planning/OPEN_DECISIONS.md`](docs/planning/OPEN_DECISIONS.md)

Core architecture entrypoints:

- [`docs/architecture/SYSTEM_OVERVIEW.md`](docs/architecture/SYSTEM_OVERVIEW.md)
- [`docs/architecture/WORLD_ZONES_INSTANCES.md`](docs/architecture/WORLD_ZONES_INSTANCES.md)
- [`docs/architecture/AUTHORITY_MODEL.md`](docs/architecture/AUTHORITY_MODEL.md)
- [`docs/architecture/TRANSACTIONS_AND_ANTI_DUPE.md`](docs/architecture/TRANSACTIONS_AND_ANTI_DUPE.md)

## Development strategy

1. Keep canonical planning internally consistent.
2. Complete/update the **cross-cutting architecture for all settled V1 systems** before continuing feature implementation.
3. Preserve existing proven authority/economy code rather than rebuilding it to match new milestone names.
4. Implement in dependency order using small end-to-end proof slices.
5. Spend serious effort on structure/correctness; keep exact balance values configurable and cheap to change.
6. Development and initial playtests remain local-PC-first. Hosting/process count grows only from measured player demand and operational need.

The canonical public world begins at Day 0. Private/closed beta state is disposable and must not leak into canonical history.