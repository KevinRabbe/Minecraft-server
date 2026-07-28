# Documentation

This directory is the canonical planning and architecture source for the project.

The project is a Minecraft-first persistent multiplayer game/server framework optimized for **repeatable retained player-hours per developer-hour and recurring maintenance cost**.

## Canonical hierarchy

Read [`planning/PLANNING_HIERARCHY.md`](planning/PLANNING_HIERARCHY.md) first when documents appear to disagree.

Highest-level product/planning order:

1. [`reference/DESIGN_LAWS.md`](reference/DESIGN_LAWS.md)
2. [`planning/V1_SCOPE.md`](planning/V1_SCOPE.md)
3. [`planning/MASTER_ROADMAP.md`](planning/MASTER_ROADMAP.md)
4. [`planning/IMPLEMENTATION_ORDER.md`](planning/IMPLEMENTATION_ORDER.md)
5. [`reference/ACCEPTANCE_CRITERIA.md`](reference/ACCEPTANCE_CRITERIA.md)
6. [`planning/OPEN_DECISIONS.md`](planning/OPEN_DECISIONS.md)

Architecture documents implement those locked decisions and must be updated before code when a product-level decision changes.

## Documentation structure

### `architecture/`
System contracts implementation must preserve: authority, state, transactions, gameplay-system boundaries, operations, and extension points. Architecture is completed/updated **before** corresponding feature code proceeds.

Notable cross-cutting policies include:

- [`MINECRAFT_VERSION_COMPATIBILITY.md`](architecture/MINECRAFT_VERSION_COMPATIBILITY.md) — native Paper version and supported client protocol policy;
- [`ARTIFACTS_AND_ATTUNEMENT.md`](architecture/ARTIFACTS_AND_ATTUNEMENT.md) — hidden artifact collection and one-active-profile exploration stat allocation.

### `planning/`
Canonical scope, roadmap, current execution order, planning precedence, and genuinely unresolved decisions.

### `reference/`
Stable design laws, terminology, and acceptance criteria.

### `development/`
Repository-development and integration workflow. [`development/CHANGE_WORKFLOW.md`](development/CHANGE_WORKFLOW.md) defines the bounded branch/PR policy and keeps `main` as the latest qualified integration checkpoint.

### `v1/`
Legacy compatibility paths from the first planning pass. These files exist only so old links remain useful. They are not authoritative over the canonical documents above.

## Current product summary

- compact purpose-built gameplay zones; concurrency scales by instance replication;
- PostgreSQL durable authority; Velocity routing; Paper live gameplay under explicit player-state ownership;
- native Minecraft backend tracks the latest **Paper stable** release through an explicit compatibility/test gate rather than intentionally targeting a legacy server version;
- supported client versions are a narrow explicitly tested matrix, not every protocol a translator can technically accept;
- atomic/idempotent value movement with explicit custody, provenance, escrow/pending delivery, and economic evidence;
- Coin pocket + protected Bank Manager;
- player-driven Bazaar for fungible commodities and Auction House for individualized items;
- crafting with bounded rolled gear quality and a narrow meaningful V1 item pool;
- launch active skill cap 50, later 75, much later 100;
- Portal/Map scalable PvE plus mob-family Bounties as the launch PvE backbone;
- Bazaar-tradable bounty materials and category pouches/specialized gear;
- server-authoritative historical PvE leaderboards;
- hidden world artifacts expand with later worlds/eras and feed a small one-active-profile Attunement Point system rather than universal passive stat inflation;
- clans plus previously locked opt-in ranked 1v1 PvP and controlled clan war;
- player voting determines future world expansion direction;
- ordinary districts have no developer-authored physical blueprint or minimum block count;
- Nether/End are later major power milestones; they raise practical gear ceilings rather than gating Map difficulty numbers;
- Day 0 begins canonical public history; private/closed beta state is disposable.

## Decision status convention

- **Locked** — architectural/product rule; implementation should conform unless intentionally reopened.
- **Planned** — expected behavior whose internal implementation may still change.
- **Balance/config** — numbers/content tuning to measure in playtests; not architecture.
- **Deferred** — intentionally outside Day-0/V1 requirement.
- **Open** — genuinely unresolved and listed in `planning/OPEN_DECISIONS.md`.

## Development rule

Finish planning consistency, then complete the cross-cutting architecture contracts for all settled V1 systems. Only after that continue feature implementation in dependency order.

Repository changes follow [`development/CHANGE_WORKFLOW.md`](development/CHANGE_WORKFLOW.md): integrate coherent green slices into `main` rather than accumulating an evergreen implementation PR.

Do not spend architecture time debating cheap tuning values such as exact damage, XP requirements, interest rates, boss HP, Map scaling, bounty kill counts, or artifact/attunement conversion rates.
