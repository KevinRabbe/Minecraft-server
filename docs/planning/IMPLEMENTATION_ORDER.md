# Implementation Order

**Current phase: Phase B — infrastructure/correctness foundation.** Phase A is complete enough to begin implementation. When coding reaches a genuinely unresolved architectural choice, stop at that boundary, update the contract, then continue; normal implementation details do not require another planning phase.

## Phase A — architecture freeze — COMPLETE

The canonical documents under `docs/architecture` now cover:

1. system overview and module boundaries
2. world/zone/instance/backend model
3. authority model and persistent data families
4. player session/transfer protocol
5. item/inventory/value model
6. transaction, escrow, idempotency, and anti-dupe rules
7. skill/gathering/modifier pipeline
8. economy and market lifecycle
9. enchanting/brewing boundary
10. clans/PvP/war isolation
11. community projects/history
12. permissions, failure/recovery, configuration, analytics, and extension points

Architecture is sufficiently frozen when implementation teams can answer "who owns this state?", "what transaction protects this operation?", and "what survives an instance crash?" without inventing new rules.

## Phase B — infrastructure/correctness foundation — ACTIVE

1. preserve the existing local Windows bootstrap and Gradle/Paper/Velocity skeleton
2. establish PostgreSQL schema/migrations for identity, session ownership, versioning, and critical ledgers
3. implement backend/instance registration and health in the simplest useful form
4. implement logical zone routing without exposing backend IDs to gameplay
5. implement single-writer player session ownership
6. prove safe checkpoint, logout, reconnect, and cross-backend transfer
7. prove crash recovery and stale-write rejection

No valuable economy should exist before this foundation is reliable.

## Phase C — item/value correctness

1. item definition catalog and validation
2. commodity quantity representation
3. unique item instance identity/provenance
4. authoritative inventory/equipment boundary
5. escrow/pending-delivery primitives
6. idempotent operation IDs and economic ledger
7. duplication/conflict quarantine path

## Phase D — compact starter gameplay

1. persistent City/starter-region shell
2. one small Woodcutting zone
3. one small Mining zone
4. one small Farming zone
5. one simple PvE zone
6. authorized gathering/mob sources and reset/respawn behavior
7. dynamic per-zone instance routing only as needed

Start with minimal zone templates. Scale by replication, not by making maps larger.

## Phase E — progression and production

1. skill framework/XP
2. stat/modifier pipeline
3. tool/use requirements
4. gathering pouches when needed
5. refining
6. crafting
7. Enchanting XP amplifier and Minecraft XP interaction
8. brewing/Witch bootstrap supply

## Phase F — economy

1. wallet/fixed-point currency
2. NPC salvage
3. Bazaar escrow/order/fill/cancel lifecycle
4. Auction House fixed-price lifecycle
5. secure direct trade
6. compression where actual quantity justifies it
7. labor/commission flow when base crafting/refining is stable

## Phase G — social and competition

1. clans, roles, roster, clan chat
2. global skill and clan leaderboards
3. standardized ranked 1v1 PvP
4. clan-war challenge/roster/loadout custody/match/settlement

## Phase H — community persistence

1. generic project definition/instance lifecycle
2. contribution transactions and contributor history
3. controlled build regions
4. archival/schematic workflow
5. immutable historical reward entitlement
6. generic feature-completion actions
7. Nether Entry project as the first major feature-unlock example

## Phase I — public-alpha readiness

1. run complete acceptance criteria
2. restore from backup in a test environment
3. deliberate backend/transfer/transaction interruption tests
4. exploit/dupe boundary tests
5. performance/instance-capacity tests with synthetic and real players
6. verify local-PC deployment remains understandable and recoverable
7. rent hosting only after real player demand justifies recurring cost

## Rule for future milestones

A milestone may tune numbers or implement a documented mechanism. It must not silently create a new authority model, item identity rule, transaction rule, progression route, or infrastructure dependency.
