# Architecture Completion Gate

This file tracks the architecture-first requirement before remaining gameplay feature implementation proceeds.

Status values:

- **PROVEN** — architecture exists and repository implementation/tests already demonstrate the boundary.
- **CONTRACTED** — detailed architecture contract is now defined; code implementation may still be pending.
- **PENDING** — architecture contract still missing/contradictory; feature code must not proceed past this boundary.

## Cross-cutting architecture matrix

| Area | Status | Canonical contract / evidence |
|---|---|---|
| Stable player identity | PROVEN | `AUTHORITY_MODEL.md`, existing player identity/session repositories |
| Single-writer session ownership/version fencing | PROVEN | `PLAYER_STATE_AND_TRANSFERS.md`, existing session/transfer integration tests |
| Zone/backend routing | PROVEN | `WORLD_ZONES_INSTANCES.md`, existing control/transfer repositories |
| PostgreSQL migrations/operation locking | PROVEN | `DATA_MODEL.md`, `TRANSACTIONS_AND_ANTI_DUPE.md`, existing migration/operation-lock code |
| Unique-item identity/custody/provenance | PROVEN | `ITEMS_AND_INVENTORY.md`, existing unique-item authority/provenance tests |
| Pending unique delivery | PROVEN | existing pending-delivery authority/tests |
| Coin wallet + append-only economic evidence | PROVEN | `ECONOMY.md`, existing wallet/evidence repositories/tests |
| Fixed-price Auction House custody/settlement | PROVEN | `ECONOMY.md`, existing AH repository/tests |
| Protected Bank Manager semantics | CONTRACTED | `ECONOMY.md`, `TRANSACTIONS_AND_ANTI_DUPE.md`, `DATA_MODEL.md` |
| Bazaar order-book semantics | CONTRACTED | `ECONOMY.md`, `TRANSACTIONS_AND_ANTI_DUPE.md`, `DATA_MODEL.md` |
| Crafting exactly-once settlement | CONTRACTED | `ITEMS_AND_INVENTORY.md`, `TRANSACTIONS_AND_ANTI_DUPE.md` |
| Persistent normalized rolled-item quality | CONTRACTED | `ITEMS_AND_INVENTORY.md`, `CONFIGURATION.md` |
| Upgrade/salvage separation | CONTRACTED | `ITEMS_AND_INVENTORY.md`, `TRANSACTIONS_AND_ANTI_DUPE.md` |
| Generic skills + staged active caps 50/75/100 | CONTRACTED | `SKILLS_GATHERING_AND_MODIFIERS.md`, `CONFIGURATION.md` |
| Authorized gathering source boundary | CONTRACTED/partially existing | `SKILLS_GATHERING_AND_MODIFIERS.md` |
| Portal/Map run lifecycle | CONTRACTED | `PVE_MAPS_AND_BOUNTIES.md` |
| Map difficulty/version/reward semantics | CONTRACTED | `PVE_MAPS_AND_BOUNTIES.md`, `CONFIGURATION.md` |
| PvE historical clear/leaderboard source | CONTRACTED | `PVE_MAPS_AND_BOUNTIES.md`, `DATA_MODEL.md` |
| Bounty family/tier/contract/summon/reward lifecycle | CONTRACTED | `PVE_MAPS_AND_BOUNTIES.md` |
| Bounty materials/pouches/tradability | CONTRACTED | `PVE_MAPS_AND_BOUNTIES.md`, `ITEMS_AND_INVENTORY.md`, `ECONOMY.md` |
| Clan treasury/shared storage custody | CONTRACTED | `CLANS_PVP_WAR.md`, `DATA_MODEL.md` |
| Ranked PvP isolation | CONTRACTED/existing design | `CLANS_PVP_WAR.md` |
| Clan-war economic custody/settlement | CONTRACTED/existing design | `CLANS_PVP_WAR.md`, `TRANSACTIONS_AND_ANTI_DUPE.md` |
| Expansion voting/ballot/result authority | CONTRACTED | `WORLD_VOTING_AND_HISTORY.md` |
| Feature state/world era | CONTRACTED | `WORLD_VOTING_AND_HISTORY.md`, `DATA_MODEL.md` |
| Ordinary player-built district no-blueprint rule | CONTRACTED | `WORLD_VOTING_AND_HISTORY.md`, `COMMUNITY_PROJECTS.md`, `WORLD_ZONES_INSTANCES.md` |
| Optional explicit Community Project boundary | CONTRACTED | `COMMUNITY_PROJECTS.md` |
| Chronicle/historical-event source model | CONTRACTED | `WORLD_VOTING_AND_HISTORY.md`, `DATA_MODEL.md` |
| Staff/permission/recovery boundaries | CONTRACTED | `PERMISSIONS.md`, `FAILURE_RECOVERY.md` |
| Configuration/version validation | CONTRACTED | `CONFIGURATION.md` |
| Analytics/observability separation from authority | CONTRACTED | `ANALYTICS.md` |
| Backup/restore/failure semantics | CONTRACTED | `FAILURE_RECOVERY.md` |
| Extension/new-content reuse rules | CONTRACTED | `EXTENSION_POINTS.md` |

## Architecture-document gate

The architecture-document layer is complete when every settled V1 system has:

1. explicit durable authority;
2. identity/custody semantics;
3. transaction/idempotency boundary where persistent state changes;
4. live-instance versus persistent-state separation;
5. configuration/version boundary;
6. failure/recovery rule;
7. permission/admin boundary;
8. acceptance/integrity proof target.

As of this architecture pass, the table above contains **no PENDING product-contract rows**.

## Next: code-level architecture scaffold

Before implementing full feature behavior, add the missing shared code/data primitives so later repositories/adapters do not invent incompatible shapes independently.

Priority scaffolding:

1. Bank account/tier/interest-period value types and persistence migration boundary.
2. Commodity ownership/balance/escrow primitives sufficient for Bazaar.
3. Generic skill ID/progression/active-cap value types.
4. Roll profile + normalized roll-quality + upgrade-state types integrated with item definitions/instances.
5. Craft recipe/result/operation contracts.
6. Map definition/item/run/status/objective/modifier/version value types.
7. Bounty family/tier/contract/status/summon authorization/material-family/pouch value types.
8. Clan treasury/storage custody permission value types where not already represented generically.
9. Expansion vote/candidate/ballot/result/feature-state/world-era value types.
10. Shared integrity-verification result interfaces/diagnostic boundaries.

Only after those cross-cutting primitives compile together should full Bazaar/Bank/Skills/Maps/Bounties/Voting feature repositories be implemented.

## Rule

Architecture scaffolding may be intentionally boring. Prefer small immutable records/enums/interfaces with strict validation over speculative framework abstractions. Do not create microservices/modules solely because these systems are large on the roadmap.