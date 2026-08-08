# Implementation Order

Status: **V1 vertical implementation active.** The architecture-alignment gate is established for the implemented authorities. Continue outward from proven systems; do not reopen or rebuild settled contracts merely because the milestone list below is chronological.

See [`MASTER_ROADMAP.md`](MASTER_ROADMAP.md) for the complete milestone map.

## Current implementation checkpoint — 2026-08-08

The active branch has moved materially beyond the original architecture-only checkpoint:

- economy/value Paper surfaces are live for Bank Manager, Bazaar, Auction House, secure direct trade, salvage, personal crafting, and crafting commissions;
- individualized gear now has definition-owned normalized roll profiles, deterministic intrinsic roll resolution, strict crafting/profile ownership, validated runtime stat snapshots, derived lore, Auction House roll/upgrade inspection, conservative lower-bound rendering before exact authority refresh, fail-closed intrinsic-damage materialization on join/post-delivery, append-only upgrade evidence plus integrity reconciliation, and a replay-/concurrency-safe carried-item upgrade authority that atomically advances serialized player-state authority version and exact item `state_version`/`upgrade_level` under the owning session; exact upgrade economics/progression/power remain intentionally unresolved rather than guessed;
- item definitions now also support explicit skill-based use/equip requirements that remain separate from ownership/crafting/trade: missing skill rows project as level 0, relevant skill levels can be loaded in one bounded read, eligibility returns exact unmet requirements, bundled item requirements cross-validate against the skill catalog, equipment lore and Auction House inspection expose static requirements, and a bounded reconnect-fenced Paper eligibility projection now refreshes asynchronously on player lifecycle only when the catalog contains a real requirement; action-level enforcement remains dormant until launch content opts real items into requirements;
- persistent MMO clans expose membership/roles/roster, network-wide bounded clan chat, a shared concurrency-safe configurable member cap (bundled `100` remains provisional tuning), treasury, shared commodity/unique-item storage, and player-facing Clan-War rating/history/leaderboard read models; clan member-count authority is reconciled by the global integrity verifier;
- starter gathering and ordinary managed PvE feed authoritative commodity/XP progression; the development Zombie Bounty fixture has contract, kill-progress, boss-authorization/boss, reward, and pouch gameplay bridges;
- capital-M Maps now have individualized persistent Map identity, exact open consumption, disposable-instance reservation/handoff, auto-pinned transfer evidence, managed Forest/Spider Extermination fixture gameplay, exactly-once successor-Map rewards, persisted return routing, abandoned/completed recovery, `/map open`, and Persistent-MMO Map leaderboards;
- the canonical first-Map route is now locked: a renewable authored elite in the Hub's walkable starter Combat area awards a low-difficulty launch Map without a Coin/Bounty/vendor/crafting prerequisite; successful Maps then continue the successor-Map loop;
- the launch Map content identities are locked to Forgotten Bastion / Flooded Depths / Windscar Ruins, Relic Guard / Deep Brood / Ruin Raiders, Extermination + Elite Hunt, Fortified / Relentless / Swarming, Bulwark / Hunter / Volatile, and Relic Alloy / Resonant Crystal / Waystone Shard; current Paper runtime support must be extended incrementally and fail closed for unsupported combinations;
- successful Map clears are the initial player-facing Coin faucet. Coin payout must use `CoinWalletRepository.creditFromSystem` with deterministic run/player operation identity and remain inside completed-Map recovery before terminal reservation release;
- Bounties are normal-world regional activity ecosystems, not dungeon/Map instances. Canonical launch families are Rootborn, Ashbound, and Veilborn, arranged initially as the combat portal chain `Hub / starter Combat -> Rootborn Region -> Ashbound Region -> Veilborn Region`;
- the launch item allowlist is locked at 28 meaningful items, including family-specialized gear, artifacts/active equipment, consumables, gathering/logistics equipment and family pouches; their source-material relationships are locked while exact quantities/rolls/XP remain tuning data;
- player-directed expansion voting already has authoritative schedule/open/ballot/resolve, feature/world-era consequences, historical evidence, a bounded player read projection, and `/vote` ballot access. The initial candidate pool is locked to Deeper Woodcutting Region vs Deeper Mining Region vs Deeper Farming Region;
- the normal-world layout is compact: a small starter civic core with essential NPCs/services and walkable starter Combat/Woodcutting/Mining/Farming spaces feeds spatial portal chains. Player-built districts consume the actual Minecraft blocks builders place rather than a duplicate abstract project-material deposit;
- Ranked Arena has end-to-end isolated 1.8.9 dispatch/routing/admission, a config-driven disposable symmetric 1v1 arena and standardized temporary kit, materialization-gated combat/lease renewal, disconnect pause/no-show recovery, bounded no-winner timeout, and death -> exactly-once result/rating settlement;
- Clan War has challenge/roster/custody/frozen-loadout transport plus a structurally qualified baseline 1.8.9 control-point runtime: exact identity-free roster/loadout snapshot, code-bound `war.legacy_1_8_9@1` starter-sword representation, exact non-truncating inventory projection, deterministic separate arena/spawns, death isolation, control progress, timeout/failure recovery, trusted result/rating settlement, and capability-gated dispatch. Production `supports_clan_war` remains `FALSE` until the accepted V1 gear representation set and deferred real-client acceptance are proven; broader rolled/upgraded/equipment translation remains intentionally fail-closed;
- read-only analytics now derives observed player-time/retention, classified Coin supply/movement with explicit coverage gaps, and Bazaar current-book plus execution microstructure directly from durable authority without creating duplicate session, currency, or market state;
- destruction testing now includes deterministic zone/backend routing churn, explicit mid-transaction rollback fault injection, post-commit acknowledgement-loss retry proof, aggregate persistent-integrity verification after adversarial recovery, and a managed-entity authority burst proving 64 independent sources through two rewarded cycles (128 exact kill/harvest/fulfillment/delivery chains) with 16 workers contending for an 8-connection pool and resource integrity clean; real Paper TPS/MSPT/client entity rendering plus real process/Windows recovery gates remain empirical rather than being imitated by weaker in-process tests.

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
11. capital-M Map run lifecycle and historical clear records;
12. Bounty-family/tier/contract/boss-authorization/material/pouch contracts;
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

1. compact persistent starter civic core with essential launch NPCs/services;
2. directly walkable starter Woodcutting/Mining/Farming/Combat spaces inside the Hub region;
3. spatial portal-chain hooks from those starter activities to later compact regions rather than a central destination-selector lobby;
4. authorized renewable gathering/mob sources;
5. live gameplay -> persistent commodity/XP transaction bridge;
6. dynamic per-zone replication only where concurrent demand requires it;
7. ordinary player-built districts use the actual placed Minecraft blocks as their construction material cost; do not add duplicate abstract build deposits.

## 6 — capital-M Map PvE

1. generic disposable PvE instance lifecycle;
2. unique tradable Map object;
3. Map-open consumption and one-run creation;
4. configurable numeric difficulty independent of player level;
5. renewable first Map from an authored starter-Combat elite, then local successor-Map progression;
6. V1 environments: Forgotten Bastion, Flooded Depths, Windscar Ruins;
7. V1 Map-only enemy packages: Relic Guard, Deep Brood, Ruin Raiders;
8. V1 objectives: Extermination and Elite Hunt;
9. V1 modifiers: Fortified, Relentless, Swarming;
10. V1 elite traits: Bulwark, Hunter, Volatile;
11. V1 Map materials: Relic Alloy, Resonant Crystal, Waystone Shard;
12. successful-clear Coin payout through the central wallet authority with deterministic recovery-safe operation identity;
13. authoritative solo/group historical clear records and leaderboards.

The existing Forest/Spider/Extermination path remains fixture/proof content. Unsupported V1 combinations must fail closed until their runtime semantics are implemented.

## 7 — Bounties

1. generic Bounty family/tier framework;
2. contract-fee transaction;
3. eligible authored normal-world family-mob kill progress;
4. boss authorization/consumption without requiring a dungeon-style player-facing presentation;
5. boss encounter/reward once;
6. two launch tiers per family with stronger roles/combinations/mechanics rather than only stat scaling;
7. Rootborn material ladder: Root Fiber -> Ancient Resin -> Heartwood Core;
8. Ashbound material ladder: Cinder Shard -> Blackglass -> Kilnheart;
9. Veilborn material ladder: Veil Thread -> Phaseglass -> Gate Fragment;
10. Bazaar integration for all Bounty materials;
11. one family pouch per launch family;
12. specialized family gear using the locked Rootborn/Ashbound/Veilborn item set.

Launch content is **Rootborn, Ashbound, and Veilborn** in normal-world regional activity areas. The current Zombie path remains development fixture content and must not be promoted back into canonical launch identity.

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
4. initial V1 candidate pool: Deeper Woodcutting Region, Deeper Mining Region, Deeper Farming Region;
5. winning regional expansions activate the next compact portal-chain segment rather than enlarging one giant Hub landmass;
6. ordinary districts have no developer-authored physical blueprint or minimum build size;
7. Chronicle/history records vote results, unlocks, and major achievements;
8. Nether/End remain later major power milestones chosen/unlocked through the player-directed world flow.

Generic project/contribution/archive infrastructure may be used for explicitly defined major projects but must not become a hidden mandatory progress bar for ordinary districts.

## 10 — V1 content pass

Only after the systems above are structurally proven:

1. author the locked 28-item launch allowlist and its exact stable IDs/representations;
2. implement the locked recipe-source graph across ordinary resources, Map materials and Bounty materials, choosing only balance quantities/XP values during implementation;
3. implement the locked Map combination pool and first-Map acquisition route;
4. implement Rootborn/Ashbound/Veilborn rosters, two-tier content, material ladders, pouches and specialized gear;
5. configure the locked first expansion candidate pool;
6. apply the locked launch use-requirement direction to gathering tools and any later explicitly justified requirement;
7. fill plausible approximate balance values and iterate by simulation/playtesting.

Numbers need to be plausible, not perfect. Stable content identity and source relationships are no longer open design questions.

## 11 — Simulation, scale, and destruction testing

1. economy/faucet/sink simulation;
2. concurrent Bazaar/AH/crafting/storage tests;
3. zone/instance churn and entity-load tests;
4. transfer/restart/database-failure tests;
5. duplicate/replay/race/crash injection;
6. integrity-verifier runs after adversarial scenarios;
7. backup + disposable restore proof.

Current structural coverage now includes concurrent value/custody races, deterministic zone/backend routing churn, transfer fencing/recovery, injected pre-commit database failure with full rollback, injected post-commit acknowledgement loss with exact idempotent replay, aggregate integrity verification after adversarial recovery, and a high-cardinality managed-entity persistence burst: 64 independent sources each complete two rewarded entity cycles under 16-worker/8-connection contention, producing 128 exact kill claims, harvests, fulfillments and durable deliveries with no unresolved PENDING/ACTIVE spawn rows and the resource-source integrity verifier clean. Remaining stage-11 work should target genuinely unproven boundaries such as real Paper entity/TPS/MSPT behavior, question-driven economy simulation once launch numbers exist, and the actual disposable Windows/Docker restore rehearsal—not duplicate the same transaction or persistence load proof under another name.

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
