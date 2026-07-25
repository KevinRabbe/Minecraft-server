# Architecture Completion Gate

This file tracks the architecture-first requirement before remaining gameplay feature implementation proceeds.

Status values:

- **PROVEN** — architecture exists and repository implementation/tests demonstrate the authority boundary. A PROVEN common authority may still need additional Paper/UI/runtime adapters before a player can exercise every path.
- **CONTRACTED** — detailed architecture contract is defined; implementation may still be pending at one or more authority boundaries.
- **PENDING** — architecture contract is missing/contradictory; feature code must not proceed past this boundary.

## Cross-cutting architecture matrix

| Area | Status | Canonical contract / evidence |
|---|---|---|
| Stable player identity | PROVEN | `AUTHORITY_MODEL.md`, player identity/session repositories and integration tests |
| Single-writer session ownership/version fencing | PROVEN | `PLAYER_STATE_AND_TRANSFERS.md`, session/transfer integration tests, Paper authoritative-state mutation lane |
| Zone/backend routing | PROVEN | `WORLD_ZONES_INSTANCES.md`, control/transfer repositories; single-active-instance bootstrap attachment is implemented while multi-instance scheduling remains explicit future routing work |
| PostgreSQL migrations/operation locking | PROVEN | `DATA_MODEL.md`, `TRANSACTIONS_AND_ANTI_DUPE.md`, migration/operation-lock code |
| Unique-item identity/custody/provenance | PROVEN | `ITEMS_AND_INVENTORY.md`, unique-item authority/provenance tests |
| Pending unique delivery | PROVEN | pending-delivery authority/tests plus generic Paper materialization through fenced serialized player state |
| Coin wallet + append-only economic evidence | PROVEN | `ECONOMY.md`, wallet/evidence repositories/tests |
| Fixed-price Auction House custody/settlement | PROVEN | `ECONOMY.md`, AH authority/tests; richer player-facing market UI remains adapter work |
| Protected Bank Manager semantics | PROVEN | Bank account/tier/transfer/upgrade/interest authority and integration tests |
| Bazaar order-book semantics | PROVEN | buy/sell reserve, price-time matching, partial fill, cancellation, delivery and concurrency tests |
| Crafting exactly-once settlement | PROVEN | personal crafting + commissions, exact ingredient-state verification, persistent output issuance, Crafting-XP recovery, first Paper `/craft` bridge |
| Persistent normalized rolled-item quality | PROVEN | immutable normalized roll state on individualized craft output; derived effective combat-stat application remains a separate modifier/runtime layer |
| Upgrade/salvage separation | CONTRACTED/partially proven | upgrade-state contract exists; irreversible salvage authority and tests are PROVEN |
| Generic skills + staged active caps 50/75/100 | PROVEN | skill authority, cap transitions, no hidden above-cap XP, integration tests |
| Authorized gathering source boundary | PROVEN | renewable source-cycle authority, fulfillment recovery, Paper Mining/Woodcutting/Farming bridge and restart-derived visual state |
| Authorized ordinary-PvE entity source boundary | PROVEN | source-cycle→spawn-ID→entity-UUID binding, exact kill claim, no-reward death/expiry recovery, managed Zombie Paper bridge |
| Portal/Map run lifecycle | PROVEN common authority | exact Map item provenance, one Map→one run, participant/start/clear/failure lifecycle; Paper inventory-coupled Map opening is still pending |
| Map difficulty/version/reward semantics | PROVEN | immutable run definition, reward settlement/grant/fulfillment authorities and retry tests |
| PvE historical clear/leaderboard source | PROVEN | immutable Map clear records with participants/configuration/world-era context |
| Bounty family/tier/contract/summon/reward lifecycle | PROVEN | paid start, kill progress, summon preparation/lease/reclaim, completion/failure and reward tests |
| Bounty materials/pouches/tradability | PROVEN authority | bounty rewards and family-pouch custody/economic path; additional Paper UX/content remains adapter work |
| Clan membership/roles/leadership | PROVEN | invite/accept/cancel, one-clan membership, committed leader invariant, role changes and atomic leadership transfer |
| Clan treasury/shared storage custody | PROVEN | Coin treasury plus commodity/individualized storage custody and permission tests |
| Ranked PvP isolation | PROVEN common authority | standardized match lifecycle, rating policy and concurrency/idempotency tests; combat runtime/version remains under evaluation |
| Clan-war economic custody/settlement | PROVEN common authority | roster/loadout/custody/snapshot/lifecycle/resolution authority and tests; combat runtime adapter remains pending |
| Expansion voting/ballot/result authority | PROVEN | immutable candidates, effective ballots, authoritative close/tie/cancel/runoff and capability unlock tests |
| Feature state/world era | PROVEN | expansion result + serialized world-era authority/history |
| Ordinary player-built district no-blueprint rule | CONTRACTED | `WORLD_VOTING_AND_HISTORY.md`, `COMMUNITY_PROJECTS.md`, `WORLD_ZONES_INSTANCES.md` |
| Optional explicit Community Project boundary | CONTRACTED | `COMMUNITY_PROJECTS.md` |
| Hidden Artifact discovery + Attunement | PROVEN | persistent definitions/location revisions/discoveries/profile authority plus first Paper interaction and `/attune` bridge |
| Chronicle/historical-event source model | PROVEN | append-only Chronicle authority and source uniqueness tests |
| Economy integrity diagnostics | PROVEN | bounded read-only verifier for Coin, pending delivery, AH/trade custody and destroyed/salvage evidence |
| Staff/permission/recovery boundaries | CONTRACTED | `PERMISSIONS.md`, `FAILURE_RECOVERY.md` |
| Configuration/version validation | PROVEN for current content lanes | strict item/skill/resource/crafting/attunement/placement loaders; broader operational configuration remains contracted |
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

The canonical architecture layer currently contains **no PENDING product-contract rows**. Remaining CONTRACTED rows are implementation/adaptation work, not unresolved product-law contradictions.

## Current implementation frontier

The repository has moved well beyond the original code-level scaffold. Common authority is already proven for the core economy, progression, Maps/Bounties, clans/competitive state, voting/history, Artifacts, gathering and ordinary-PvE source identity.

The first real Paper gameplay verticals now include:

- authoritative Mining, Woodcutting and Farming source interactions;
- durable commodity delivery into fenced persistent inventory;
- managed ordinary-PvE Zombie spawn/kill/reward flow;
- hidden Artifact discovery and Attunement selection;
- personal crafting from exact persistent ingredients into durable commodity/individualized output;
- generic pending unique-item materialization into fenced persistent inventory.

The next high-value adapter boundary is **inventory-coupled Map opening**: the existing Map authority consumes exact item custody atomically with run creation, but Paper must also remove that same ItemStack from serialized player state in the same transaction before the live Portal/Map runtime is exposed.

## Rule

Architecture scaffolding may be intentionally boring. Prefer small immutable records/enums/interfaces with strict validation over speculative framework abstractions. Do not create microservices/modules solely because these systems are large on the roadmap.
