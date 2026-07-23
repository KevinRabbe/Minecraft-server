# Minecraft Server

Minecraft-first persistent multiplayer game/server framework.

## Objective

The project is optimized for **repeatable retained player-hours generated per developer-hour and recurring maintenance cost**.

Minecraft supplies useful primitives such as movement, building, inventory UI, combat, enchanting, brewing, and multiplayer. Custom code supplies the persistent game layer: progression, economy, controlled resource generation, social systems, provenance/history, community projects, cross-instance player state, and network routing.

## Current architecture direction

- deliberately small, dense, purpose-built gameplay zones rather than endless vanilla wilderness
- capacity scales by replicating zone instances, not by making maps larger
- instance count is driven by concurrent demand in that specific zone, independent of total network population
- zone = gameplay definition; instance = one live copy; backend = infrastructure hosting instances
- PostgreSQL = durable persistent authority; Velocity = routing; Paper = live gameplay while it holds player-state ownership
- single-writer player state and versioned transfers
- atomic/idempotent value movement with escrow/pending delivery for important transactions
- player-driven Bazaar/Auction economy
- compact persistent City/starter region with first gathering/PvE activities
- Nether supported architecturally but locked at launch and later opened through a community-built project

## Repository

- `common/` — infrastructure-neutral domain contracts/shared logic
- `paper/` — Paper gameplay/backend adapter
- `velocity/` — Velocity network/routing adapter
- `resource-pack/` — presentation assets
- `infra/` — local/deployment infrastructure
- `docs/` — canonical architecture, planning, and reference documentation

## Documentation

Start at [`docs/README.md`](docs/README.md).

Key documents:

1. [`docs/reference/DESIGN_LAWS.md`](docs/reference/DESIGN_LAWS.md)
2. [`docs/planning/V1_SCOPE.md`](docs/planning/V1_SCOPE.md)
3. [`docs/architecture/SYSTEM_OVERVIEW.md`](docs/architecture/SYSTEM_OVERVIEW.md)
4. [`docs/architecture/WORLD_ZONES_INSTANCES.md`](docs/architecture/WORLD_ZONES_INSTANCES.md)
5. [`docs/architecture/AUTHORITY_MODEL.md`](docs/architecture/AUTHORITY_MODEL.md)
6. [`docs/architecture/TRANSACTIONS_AND_ANTI_DUPE.md`](docs/architecture/TRANSACTIONS_AND_ANTI_DUPE.md)
7. [`docs/planning/IMPLEMENTATION_ORDER.md`](docs/planning/IMPLEMENTATION_ORDER.md)

## Development strategy

Finish/freeze the V1 architecture contract first, then implement against it.

Development and initial playtests remain local-PC-first. Hosting/process count grows only from measured player demand and operational need; gameplay identities and persistent-state semantics must not change when capacity scales out.
