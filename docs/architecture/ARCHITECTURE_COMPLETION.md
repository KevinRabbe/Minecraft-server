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
| Single-writer session ownership/version fencing | PROVEN | `PLAYER_STATE_AND_TRANSFERS.md`, session/transfer integration tests, Paper authoritative-state mutation lane, plus bounded reconciliation of live session↔player-state versions, lifecycle custody, owner-instance/backend identity, open transfer tickets and routed target identity |
| Zone/backend routing | PROVEN | `WORLD_ZONES_INSTANCES.md`, control/transfer repositories; single-active-instance bootstrap attachment is implemented while multi-instance scheduling remains explicit future routing work |
| PostgreSQL migrations/operation locking | PROVEN | `DATA_MODEL.md`, `TRANSACTIONS_AND_ANTI_DUPE.md`, migration/operation-lock code |
| Unique-item identity/custody/provenance | PROVEN | `ITEMS_AND_INVENTORY.md`, unique-item authority/provenance tests |
| Pending unique delivery | PROVEN | pending-delivery authority/tests plus generic Paper materialization through fenced serialized player state |
| Coin wallet + append-only economic evidence | PROVEN | `ECONOMY.md`, wallet/evidence repositories/tests |
| Fixed-price Auction House custody/settlement | PROVEN | `ECONOMY.md`, AH authority/tests and player-facing browse/list/buy/cancel bridge |
| Protected Bank Manager semantics | PROVEN | Bank account/tier/transfer/upgrade/interest authority and integration tests |
| Bazaar order-book semantics | PROVEN | buy/sell reserve, price-time matching, partial fill, cancellation, delivery and concurrency tests |
| Crafting exactly-once settlement | PROVEN | personal crafting + commissions, exact ingredient-state verification, persistent output issuance, Crafting-XP recovery, Paper `/craft` bridge |
| Persistent normalized rolled-item quality | PROVEN | definition-owned bounded roll profiles, immutable normalized roll state, validated runtime snapshots/cache, derived Paper/AH presentation, conservative delivery projection and fail-closed intrinsic-damage attribute materialization on join/delivery |
| Equipment upgrade/salvage separation | PROVEN authority | V82 upgrade evidence/integrity, session-fenced atomic carried-item + serialized-state upgrade transition, replay/concurrency/category guards; irreversible salvage authority/tests; exact upgrade economics/power remain content decisions |
| Item use/equip requirements | PROVEN foundation | definition-owned use requirements, catalog validation, bounded fail-closed Paper projection, reconnect/refresh fencing and committed-XP projection updates; action-level enforcement remains intentionally dormant until launch content opts into a real requirement |
| Generic skills + staged active caps 50/75/100 | PROVEN | skill authority, cap transitions, no hidden above-cap XP, mutable-state↔append-only XP reconciliation, current catalog/cap ceilings, and staged-cap source/version reconciliation to append-only `SKILL_CAP_ADVANCE` evidence |
| Authorized gathering source boundary | PROVEN | renewable source-cycle authority, fulfillment recovery, Paper Mining/Woodcutting/Farming bridge and restart-derived visual state |
| Authorized ordinary-PvE entity source boundary | PROVEN | source-cycle→spawn-ID→entity-UUID binding, exact kill claim, no-reward death/expiry recovery, managed Zombie Paper bridge |
| Portal/Map run lifecycle | PROVEN | exact Map item provenance, inventory-coupled one-Map→one-run opening, disposable reservation/handoff, managed encounter runtime, completion/abandon recovery and return routing |
| Map difficulty/version/reward semantics | PROVEN | immutable run definition, reward settlement/grant/fulfillment authorities, successor-Map issuance and retry tests |
| PvE historical clear/leaderboard source | PROVEN | immutable Map clear records with participants/configuration/world-era context plus Paper leaderboard views |
| Bounty family/tier/contract/summon/reward lifecycle | PROVEN | paid start, managed-kill progress, authored summon/materialization, completion/failure and reward tests plus Paper bridge |
| Bounty materials/pouches/tradability | PROVEN | bounty rewards, family-pouch custody/economic path, Bazaar tradability and player-facing pouch withdrawal |
| Clan membership/roles/leadership | PROVEN | invite/accept/cancel, one-clan membership, committed leader invariant, role changes, atomic leadership transfer, bounded clan chat and configurable shared member cap |
| Clan treasury/shared storage custody | PROVEN | Coin treasury plus commodity/individualized storage custody and permission tests/player bridge |
| Ranked PvP isolation | PROVEN baseline runtime | isolated 1.8.9 dispatch/routing/admission, materialization-gated standardized 1v1 runtime, no-show/disconnect/timeout recovery and exactly-once result/rating settlement; real-client combat-feel acceptance remains deferred empirical work |
| Clan-war economic custody/settlement | PROVEN baseline runtime | challenge/roster/custody/frozen loadout, sealed identity-free runtime transport, baseline 1.8.9 control-point runtime, recovery and exactly-once rating/result settlement; broader gear representation and real-client acceptance remain deferred |
| Expansion voting/ballot/result authority | PROVEN | immutable candidates, effective ballots, authoritative close/tie/cancel/runoff, feature/world-era consequences, player-facing ballot/state projection, and resolved-vote reconciliation against append-only resolution evidence + Chronicle + resulting feature/era state |
| Feature state/world era | PROVEN | expansion result + serialized world-era authority/history; resolved expansion consequences are cross-checked against winning feature availability and exact resulting-era source/time |
| Ordinary player-built district no-blueprint rule | CONTRACTED | `WORLD_VOTING_AND_HISTORY.md`, `COMMUNITY_PROJECTS.md`, `WORLD_ZONES_INSTANCES.md` |
| Optional explicit Community Project boundary | CONTRACTED | `COMMUNITY_PROJECTS.md` |
| Hidden Artifact discovery + Attunement | PROVEN | persistent definitions/location revisions/discoveries/profile authority plus Paper interaction and `/attune` bridge |
| Chronicle/historical-event source model | PROVEN | append-only Chronicle authority, source uniqueness tests and player read projection; expansion-resolution Chronicle records are reconciled to the authoritative resolved vote |
| Persistent integrity diagnostics | PROVEN | bounded read-only aggregate verifier across session/transfer authority, economy/custody, item-upgrade definition/evidence, skill-state↔XP-evidence plus staged-cap operation reconciliation/current catalog+cap invariants, expansion resolution/history/feature/world-era consequences, persistent PvE, clans and competitive state; Paper `/integrity` loads bundled item/skill catalogs for catalog-aware checks |
| Implemented operator command isolation | PROVEN | `PERMISSIONS.md`; named `/integrity` and `/devzone` capabilities are enforced inside their executors, YAML capability wiring is regression-tested, and development routing remains additionally closed by runtime policy |
| Value-changing staff/recovery mutation | CONTRACTED | `PERMISSIONS.md`, `FAILURE_RECOVERY.md`; no generic persistent-value repair/mint/override command exists and any future operation requires narrow authorization plus append-only audit evidence |
| Configuration/version validation | PROVEN for current content lanes | strict item/skill/resource/crafting/attunement/placement loaders; broader operational configuration remains contracted |
| Analytics/observability separation from authority | PROVEN baseline | `ANALYTICS.md`; read-only session analytics derives observed player-time plus unique/new/returning participation directly from authoritative `player_sessions`, with partial-window clipping and no duplicate analytics lifecycle; broader question-driven instrumentation remains incremental |
| Backup/restore/failure semantics | CONTRACTED | `FAILURE_RECOVERY.md`; offline coherent backup/restore tooling, checksum/version validation, incomplete-restore startup fencing and bounded proxy-first local logout drain are CI-qualified structurally; actual Windows/Docker restore/shutdown rehearsal remains empirical before release |
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

The canonical architecture layer currently contains **no PENDING product-contract rows**. Remaining CONTRACTED rows are implementation/adaptation or empirical acceptance work, not unresolved product-law contradictions.

## Current implementation frontier

The repository is now well beyond the original scaffold. Core V1 persistent authority is proven across economy, progression, Maps/Bounties, clans/competitive state, voting/history, Artifacts, gathering, ordinary-PvE source identity and individualized gear state.

Player-facing/adapter and operations work already includes:

- authoritative Mining, Woodcutting and Farming source interactions;
- durable commodity and unique-item delivery into fenced persistent inventory;
- managed ordinary-PvE Zombie and Bounty boss flows;
- hidden Artifact discovery and Attunement selection;
- personal crafting, commissions, Bank/Bazaar/Auction House/trade/salvage and clan storage/treasury surfaces;
- inventory-coupled Map opening, managed Map encounter completion/recovery and Persistent-MMO leaderboards;
- expansion voting/Chronicle projections with cross-authority resolved-vote integrity reconciliation;
- isolated Ranked 1v1 and baseline Clan-War 1.8.9 execution paths;
- validated rolled-item runtime snapshots/presentation and fail-closed intrinsic-damage combat materialization plus structurally safe carried-item upgrade authority;
- bounded reconnect-fenced item-use eligibility projection that stays dormant for unrestricted content and advances directly from committed XP results;
- read-only session analytics for observed player-time and new/returning participation without duplicating session authority;
- operator-triggered bounded `/integrity` verification including session/transfer identity+version checks, skill progression XP/cap evidence/catalog checks, and expansion resolution/history/feature/world-era consequence checks, plus fail-closed development-route capability isolation;
- offline coherent local backup/restore scripts with manifest/checksum/version validation, a fail-closed incomplete-restore startup fence, and a bounded proxy-first logout drain before local Paper shutdown.

The highest-value remaining recovery boundary is now **empirical coherent restore proof on the actual Windows/Docker development machine**, not more backup-script architecture. Upgrade economics/power, broader Clan-War gear representation, real-client competitive feel, first-Map acquisition and concrete skill-gated item content remain intentionally behind explicit content/tuning or empirical decisions rather than being guessed into architecture.

## Rule

Architecture scaffolding may be intentionally boring. Prefer small immutable records/enums/interfaces with strict validation over speculative framework abstractions. Do not create microservices/modules solely because these systems are large on the roadmap.
