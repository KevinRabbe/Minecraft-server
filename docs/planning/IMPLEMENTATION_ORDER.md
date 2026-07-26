# Implementation Order

Status: **V1 vertical implementation active.** The architecture-alignment gate is established for the implemented authorities. Continue outward from proven systems; do not reopen or rebuild settled contracts merely because the milestone list below is chronological.

See [`MASTER_ROADMAP.md`](MASTER_ROADMAP.md) for the complete milestone map.

## Current implementation checkpoint — 2026-07-27

The active branch has moved materially beyond the original architecture-only checkpoint:

- economy/value Paper surfaces are live for Bank Manager, Bazaar, Auction House, secure direct trade, salvage, personal crafting, and crafting commissions;
- persistent MMO clans expose membership/roles, treasury, and shared commodity/unique-item storage using the existing custody/delivery authorities;
- starter gathering and ordinary managed PvE feed authoritative commodity/XP progression; the first Bounty family has contract, kill-progress, summon/boss, reward, and pouch gameplay bridges;
- Portal/Maps now have individualized persistent Map identity, exact open consumption, disposable-instance reservation/handoff, auto-pinned transfer evidence, managed Forest/Spider Extermination gameplay, exactly-once successor-Map rewards, persisted return routing, abandoned/completed recovery, `/map open`, and Persistent-MMO Map leaderboards;
- the first-Map acquisition source remains intentionally unresolved in [`OPEN_DECISIONS.md`](OPEN_DECISIONS.md); do not create a hidden mandatory Bounty/crafting/vendor route just to make the first Map appear;
- player-directed expansion voting already has authoritative schedule/open/ballot/resolve, feature/world-era consequences, historical evidence, a bounded player read projection, and `/vote` ballot access. Candidate scheduling/content remains a separate configuration/operations decision;
- Ranked Arena has end-to-end isolated 1.8.9 dispatch/routing/admission, a config-driven disposable symmetric 1v1 arena and standardized temporary kit, materialization-gated combat/lease renewal, disconnect pause/no-show recovery, bounded no-winner timeout, and death -> exactly-once result/rating settlement;
- Clan War has challenge/roster/custody/frozen-loadout transport plus a structurally qualified baseline 1.8.9 control-point runtime: exact identity-free roster/loadout snapshot, code-bound `war.legacy_1_8_9@1` starter-sword representation, exact non-truncating inventory projection, deterministic separate arena/spawns, death isolation, control progress, timeout/failure recovery, trusted result/rating settlement, and capability-gated dispatch. Production `supports_clan_war` remains `FALSE` until the accepted V1 gear representation set and deferred real-client acceptance are proven; broader rolled/upgraded/equipment translation remains intentionally fail-closed.

Treat this checkpoint as the execution state, while the numbered sections below remain the dependency/reference order.

## 0 — Planning consistency gate

Before more feature code:

1. `V1_SCOPE.md`, `MASTER_ROADMAP.md`, `OPEN_DECISIONS.md`, design laws, and acceptance criteria must agree;
2. previously locked V1 systems must not disappear merely because a newer roadmap omitted them;
3. newly settled systems must not remain marked Deferred/Open in older documents;
4. balance examples must not become architectural requirements;
5. legacy `docs/v1` content remains non-canonical compatibility material.

## 1 — Architecture completion gate

**Do this before implementing the remaining gameplay milestones.**

The cross-cutting architecture must define stable contracts for:

1. identity and ownership;
2. PostgreSQL persistence/migrations;
3. single-writer player state and cross-backend transfer;
4. atomic/idempotent value movement, operation locking, escrow, pending delivery, provenance, and ledgers;
5. commodity quantities versus unique-item identities;
6. configuration/catalog validation and balance-version handling;
7. zone/instance/backend topology and lifecycle;
8. wallet + protected-bank semantics;
9. generic skills with staged 50 -> 75 -> 100 active caps;
10. crafting and persistent normalized rolled-item quality;
11. Portal/Map run lifecycle and historical clear records;
12. bounty-family/tier/contract/summon/material/pouch contracts;
13. clan authority, treasury/storage permissions, ranked PvP and clan-war isolation;
14. world expansion voting, feature-access state, world eras, and Chronicle/history;
15. verification, backup/recovery, concurrency, crash-injection, and adversarial-test boundaries.

Existing architecture that already proves these contracts is retained. Missing contracts are added before corresponding feature code proceeds.

**Architecture completion criterion:** implementation can answer, for every V1 persistent system, who owns the state, what operation/transaction mutates it, what survives a crash, what is configurable, and what historical evidence remains.

## 2 — Existing foundation checkpoint

Already implemented/proven work on `main` includes substantial portions of:

- PostgreSQL migrations and persistent player identity;
- backend/zone registry and routed cross-backend transfer;
- exclusive session leases/version fencing;
- authoritative carried inventory persistence;
- strict item definitions;
- unique-item identity/provenance and live ItemStack validation;
- fixed-point Coin wallets and append-only economic evidence;
- pending unique-item delivery;
- fixed-price Auction House authority.

Do not rebuild these merely to match milestone lettering. Extend them only where the architecture-alignment pass identifies a real missing contract.

## 3 — Value/economy completion

1. commodity quantity authority where not already complete;
2. protected Bank Manager storage and explicit interest/death-loss transaction semantics;
3. Bazaar order book/escrow/matching/fills/cancellation;
4. direct trade;
5. salvage/value sinks;
6. invariant/economy verification tools.

## 4 — Crafting, rolls, and skills

1. generic recipe transaction authority;
2. persistent normalized roll quality for individualized gear;
3. roughly 10–30% configured low-to-high relevant value spread per item family;
4. upgrade-state separation from intrinsic roll quality;
5. generic skill/XP framework;
6. active cap 50 with tested future transitions to 75 and 100;
7. Mining/Crafting first vertical slice, then other launch skills.

## 5 — Starter gameplay/world bridge

1. persistent starter City shell;
2. compact Wood/Mining/Farming/ordinary-PvE spaces;
3. authorized renewable gathering/mob sources;
4. live gameplay -> persistent commodity/XP transaction bridge;
5. dynamic per-zone replication only where concurrent demand requires it.

## 6 — Portal/Map PvE

1. generic disposable PvE instance lifecycle;
2. unique tradable Map object;
3. map-open consumption and one-run creation;
4. configurable numeric difficulty independent of player level;
5. local map-chain progression;
6. environments/enemy families/objectives/modifiers/elites;
7. Map materials/rewards;
8. authoritative solo/group historical clear records and leaderboards.

## 7 — Bounties

1. generic bounty family/tier framework;
2. contract-fee transaction;
3. category mob-kill progress;
4. summon authorization/consumption;
5. boss encounter/reward once;
6. tiered family materials;
7. Bazaar integration for all bounty materials;
8. category pouches;
9. specialized family gear.

Start with one family/tier end-to-end, then add Spider/Zombie/Golem content through data/config where possible.

## 8 — Clans and opt-in competition

1. clan identity/membership/roles/chat;
2. treasury and shared storage with strict permissions/auditability;
3. standardized ranked 1v1 PvP;
4. clan-war challenge/roster/custody/match/settlement;
5. global ranking/history read models.

## 9 — Player-directed world progression

1. authoritative expansion candidate/vote lifecycle;
2. deterministic one-player/one-valid-vote rules as configured;
3. feature-access/world-era transitions;
4. ordinary districts have no developer-authored physical blueprint or minimum build size;
5. Chronicle/history records vote results, unlocks, and major achievements;
6. Nether/End remain later major power milestones chosen/unlocked through the player-directed world flow.

Generic project/contribution/archive infrastructure may be used for explicitly defined major projects but must not become a hidden mandatory progress bar for ordinary districts.

## 10 — V1 content pass

Only after the systems above are structurally proven:

1. roughly 25–30 meaningful launch items/equipment;
2. initial recipes/resources/consumables;
3. initial Map combination pool;
4. first bounty families/tiers/materials;
5. first expansion candidate pool;
6. skill rewards/unlocks;
7. approximate balance values.

Numbers need to be plausible, not perfect.

## 11 — Simulation, scale, and destruction testing

1. economy/faucet/sink simulation;
2. concurrent Bazaar/AH/crafting/storage tests;
3. zone/instance churn and entity-load tests;
4. transfer/restart/database-failure tests;
5. duplicate/replay/race/crash injection;
6. integrity-verifier runs after adversarial scenarios;
7. backup + disposable restore proof.

## 12 — Private alpha

Friends/family/trusted testers. Disposable worlds. Validate ordinary usability, progression comprehension, gross balance, and operational recovery.

## 13 — Adversarial closed beta

Normal testers plus a deliberately selected breaker/red-team cohort. Their job is to find structural exploits, not merely play normally. Creator footage may be embargoed. Beta progress never becomes canonical launch progress.

## 14 — Release candidate

Structural feature freeze. Correctness, exploit, performance, packaging, and tuning work only. Do not add another large subsystem because it sounds interesting.

## 15 — Pre-launch and Day 0

Announce the canonical opening well in advance; exact duration is a launch decision (~30 days is an example, not a rule). Discord/clans/alliances may organize before launch.

The public countdown ends at Day 0. From that point, the canonical world and its player-created history matter.

## Rule for every milestone

A milestone may implement a documented mechanism or tune configuration. It must not silently introduce a new authority model, asset-identity rule, transaction semantic, forced progression route, physical district blueprint, or developer-steered world outcome.
