# PvE Maps and Bounties

## Purpose

V1 PvE is built primarily from two reusable systems:

1. **Portal/Map runs** — scalable, tradable, combinatorial PvE whose numeric difficulty measures encounter power;
2. **Bounties** — enemy-family progression that turns eligible family hunts into boss access, family materials, specialization, and Coin sinks.

Dungeons are intentionally deferred until these systems are mature.

## Shared rules

- persistent valuable state lives in PostgreSQL-authoritative records;
- live encounter state may be disposable;
- one operation/result identity prevents duplicate rewards;
- client/UI never declares completion authoritatively;
- balance/content definitions are versioned configuration;
- difficulty/tier numbers are not trusted client input;
- exact HP, damage, kill counts, drop rates, fees, modifier values, and reward curves are balance data.

---

# Part I — Portal / Map architecture

## Map item identity

A Map is an individualized persistent item when its challenge properties vary per copy.

Conceptual Map instance fields:

- `item_instance_id`
- `definition_id` / Map type
- `difficulty`
- `environment_id`
- `enemy_family_id`
- `objective_id`
- ordered/set modifier IDs
- deterministic generation seed/profile data
- map-generation version
- created source/provenance

A Map may be traded, stored, directly transferred, or listed on the Auction House before it is opened.

## Map difficulty

`difficulty` is a numeric encounter-strength coordinate, not a player-level/access requirement.

Locked semantics:

- the server may define difficulties that current available gear cannot realistically clear;
- players may attempt a Map above their personal historical clear;
- personal skill level does not grant/deny permission merely because its number is lower/higher;
- Nether/End do not unlock Map difficulty numbers;
- stronger later gear raises the practical ceiling players can reach;
- difficulty scaling is configured/versioned rather than hardcoded per level.

## Map opening transaction

Opening a Map must atomically establish that the exact persistent Map item cannot be used/traded again and that at most one run identity exists for it.

Conceptual transition:

`OWNED_MAP -> OPEN/CONSUMED_CUSTODY -> MAP_RUN`

The implementation may represent this as consumption plus a persistent `map_runs` row or another equivalent authority model, but it must satisfy:

- one Map item -> at most one valid run;
- open versus trade/AH/custody race has one winner;
- retries use a stable operation ID and return/reconstruct the original committed result;
- a lost client response after DB commit cannot create another run.

## Map run identity

A persistent Map-run record exists even though the live instance is disposable.

Conceptual fields:

- `run_id`
- `source_map_item_id`
- state: created/active/completed/failed/closed (exact enum may vary)
- participant/party identity
- run-definition snapshot or immutable references + versions
- started/completed timestamps
- completion/failure reason
- reward-settlement operation ID
- leaderboard-clear reference when applicable

The run record is the persistent source for settlement/history; the live Paper instance is not.

## Run-definition snapshot

At run creation/start, preserve enough context that the run remains interpretable after later balance/content changes.

Include where relevant:

- difficulty
- environment
- enemy family
- objective
- modifiers
- generation seed/profile
- Map generation version
- combat/balance version
- relevant feature/world era

An active run should not silently change because configuration is edited while players are inside it.

## Participant policy

V1 should start with a simple deterministic policy, e.g. participant/party set locked at run start.

Late join rules, party size, spectator behavior, revive/life rules, and contribution thresholds are content/gameplay decisions. Whatever policy is chosen must be server-authoritative and recorded enough for settlement/leaderboard correctness.

## Objective interface

Map objectives should implement a shared lifecycle rather than one-off instance code.

Conceptual contract:

- initialize objective state from validated run definition;
- consume authoritative gameplay events;
- expose player-facing progress where appropriate;
- determine complete/fail state;
- never directly mint persistent rewards;
- emit one authoritative run-completion/failure decision.

Initial objective families:

- Extermination
- Elite Hunt
- Defense
- Assault

## Environment and enemy families

Environments and enemy families are stable content IDs referenced by Maps/runs.

Environment definitions may supply:

- template/generation profile
- spawn geometry rules
- objective compatibility
- hazard rules
- presentation references

Enemy-family definitions may supply:

- spawn pool
- elite compatibility
- category tags (including bounty-family relevance)
- reward/drop profile
- behavior capability references

Do not tie core Map logic to hardcoded `if SPIDER` / `if FOREST` branches where data/registered behavior can express the distinction.

## Modifiers

Modifiers compose through explicit extension points.

Examples of modifier effects:

- enemy move/attack speed
- health/armor/regeneration
- healing effectiveness
- elite weighting
- spawn density within bounded performance limits
- environmental pressure

Modifier definitions include compatibility/incompatibility metadata where combinations can be invalid.

Avoid arbitrary modifier code that mutates unrelated persistent state.

## Elites

Elite traits are reusable behavior/stat packages applied to eligible enemies.

Initial traits may include examples such as Juggernaut, Assassin, Warden, Summoner, Leech, Exploder.

Higher content may combine traits if validation/performance allows. Elite composition is part of the run context when needed for reproduction/history.

## Difficulty scaling contract

Keep separate configurable curves/controls for at least:

- enemy effective health/defense pressure;
- enemy damage/survival pressure;
- elite frequency/strength;
- reward scaling;
- modifier count/intensity rules where tied to difficulty.

Do not require health and damage to share one curve.

Scaling arithmetic must be bounded/checked across the supported system range.

Entity count should remain bounded enough that server performance does not become the accidental primary difficulty mechanic.

## Map progression/drop generation

A successful run may create future Map items around the cleared difficulty using a configured bounded distribution.

Rules:

- generated difficulty is validated within supported range;
- progression jumps are bounded/configured;
- each generated Map gets normal unique-item identity/provenance;
- generation occurs inside or is causally tied to the exactly-once completion settlement;
- failed runs do not silently restore the opened Map.

## Map materials and rewards

Map rewards may create fungible Map materials and/or individualized items/Maps.

V1 should keep Map-material families compact. Exact names/tier thresholds are content decisions.

Reward settlement uses the ordinary economic/value kernel. The Map system never edits wallet/commodity/item tables ad hoc.

## PvE death interaction

A PvE death inside a Map may trigger the configured pocket-Coin loss mechanism through the central death/economy boundary.

The Map run does not implement a second copy of wallet logic.

Protected bank Coins are unaffected by ordinary PvE pocket-loss semantics.

## Run failure/restart

A full backend restart may abort non-completed disposable runs in V1 unless resumable instances are explicitly implemented later.

Regardless of runtime policy:

- one opened Map cannot become reusable due to ambiguous cleanup;
- a completed reward cannot apply twice;
- a failed/unsettled run remains explainable from persistent records;
- players route back to a safe persistent location;
- stale live instance events cannot settle after a terminal persistent state is committed.

## PvE clear records and leaderboards

A completed qualifying Map run may produce one immutable/append-oriented clear record.

Conceptual fields:

- `clear_id`
- `run_id`
- participant identities / solo-group classification
- difficulty
- elapsed time
- Map/run context references
- relevant loadout snapshot/references
- world era
- balance/version context
- completion timestamp

Leaderboards are derived read models from clear records.

Core views:

- highest solo clear
- highest group clear
- fastest clear at selected/meaningful difficulty points

Historical era records remain queryable after stronger gear becomes available.

Leaderboard rewards are prestige/history/cosmetic, not mandatory combat power.

---

# Part II — Bounty architecture

## Bounty family

A Bounty Family is a stable **enemy-ecosystem progression/economy identity**, not a one-to-one vanilla Minecraft mob category.

The initial V1 families are:

- **Rootborn**;
- **Ashbound**;
- **Veilborn**.

Each family may contain multiple creature roles and variants, for example normal/common creatures, mobility/ambush roles, heavy/frontline roles, support/control roles, elites, and one or more bosses. Families should be mechanically distinguishable enough that learning one ecosystem does not make the others feel like simple reskins.

Vanilla Minecraft entities may be modified/reused as technical bases for movement, hitbox, pathfinding, animation, or other implementation convenience. That underlying entity type is not the player-facing bounty identity. Custom models/presentation may replace the representation later without changing persistent Bounty authority.

The existing Zombie T1 implementation remains development/vertical-slice fixture content that proves the generic contract, kill-progress, summon, boss, reward, and pouch authorities. It is not canonical launch content.

A family groups:

- eligible authored creatures/variants and family tags;
- tiers;
- contract definitions;
- boss encounter definition(s);
- family material ladder;
- pouch eligibility;
- family-specialized equipment/recipe references.

## Bounty tier

A tier is one configured step within a family.

A tier may define:

- progression requirement/access;
- Coin contract fee;
- eligible-hunt target/requirements;
- eligible creature roles/variants;
- summon/boss definition;
- reward/material profile;
- encounter/boss numeric/mechanic version.

Higher tiers should be allowed to introduce stronger variants, new roles, new combinations, and additional mechanics rather than being constrained to HP/damage scaling alone.

Exact kill counts and tier numbers are balance/content data.

## Contract lifecycle

Conceptual lifecycle:

`AVAILABLE -> ACTIVE_HUNT -> SUMMON_READY -> SUMMONED/CONSUMED -> COMPLETED | FAILED/ENDED`

Exact state names may vary, but transitions are authoritative and versioned/idempotent where retries are possible.

## Contract fee

Starting a bounty contract performs an explicit Coin sink transaction.

The fee buys/activates the **contract/quest**, not a direct boss spawn.

The fee operation must be idempotent. A lost response/retry cannot charge twice or create two active contracts unless the game explicitly supports multiple concurrent contracts.

## Eligible family kills

Only server-observed kills of creatures explicitly tagged/authorized for that family/tier advance a contract.

Each qualifying progression event must be uniquely attributable enough to prevent retry/double-event duplication.

The architecture must support:

- authored ordinary-world family creatures;
- eligible Map creatures if configured;
- stronger/specialist variants at higher tiers if configured;
- future custom-modeled creatures whose underlying Minecraft entity type is only a runtime implementation detail.

Natural vanilla entities, player-spawned/farmed entities, or unrelated mobs must not accidentally count merely because they share the same underlying Minecraft entity type with an authored family creature.

## Summon authorization

When the configured hunt requirement is satisfied, the contract gains one or more explicit summon authorization units according to the definition.

V1 default should be simple: one completed contract -> one summon attempt.

Summon authorization is persistent value/state and must be consumed atomically with boss encounter creation so concurrent requests cannot create two valid bosses from one authorization.

## Boss encounter

The boss live runtime may be disposable, but persistent contract/summon/result state is not.

Boss completion is server-authoritative and settles at most once.

Failure policy (e.g. authorization consumed on failed attempt) is content design; the architecture must support the chosen terminal state without duplication/refund ambiguity.

Bosses belong to the family ecosystem and should express its mechanics rather than simply being a larger-stat version of one vanilla base mob.

## Tiered family materials

Family materials are fungible commodities.

A family may expose a compact ladder of physically/thematically meaningful materials from that ecosystem rather than generic numbered tokens.

Exact names/tiers are content data.

All normal bounty-family materials are Bazaar-tradable unless a future explicit exception is approved. Do not soulbind them by default.

## Cross-system recipes

Bounty materials intentionally connect to other systems.

A specialized item may require combinations of:

- normal gathered/processed resources;
- district resources;
- Map materials;
- one or more bounty-family materials.

This encourages specialization/trade rather than forcing every player to complete every branch personally.

## Family-specialized equipment

Equipment may provide family-specific advantages such as:

- resistance to family mechanics/debuffs;
- mobility/control suited to the family;
- family damage/armor interaction;
- specialized utility.

Avoid making one universal set strictly replace all family builds.

Higher bounty tiers can become significantly easier/possible through specialized gear, but gear remains tradeable economic output.

## Bounty-family progression

Family progression may gate:

- higher-tier contract access;
- family pouch capacity/QoL;
- family-specific recipe/use unlocks.

It should not be the sole source of raw power; equipment/build choices remain important.

## Bounty pouches

Each family may define one specialized persistent pouch.

Rules:

- accepts only configured family commodities;
- uses central commodity authority;
- capacity is configured/progression-driven;
- direct drop routing may occur only after authoritative reward creation;
- Bazaar sell/transfer can consume pouch quantity through the same economic kernel;
- pouch storage never changes fungibility or tradability.

## Bounty failure/restart

For every terminal/interrupted state, define whether the contract remains active, summon authorization remains/was consumed, or boss attempt is failed.

Regardless of policy:

- no duplicate fee refund/charge;
- no duplicated hunt progress;
- no double summon from one authorization;
- no double boss reward;
- recovery uses persistent state rather than trusting surviving entities.

---

# Part III — Shared extensibility and verification

## Data/configuration

Map/Bounty content is version-controlled configuration with stable IDs.

Validate at startup where applicable:

- all environment/enemy/objective/modifier/bounty family/tier IDs are unique;
- all references exist;
- modifier combinations/compatibility are valid;
- Map difficulty/ranges are bounded;
- bounty fees/kill counts/reward quantities are non-negative/valid;
- eligible family creature/variant references resolve to registered authored behavior/content;
- material definitions exist and are Bazaar-compatible where required;
- pouch family allowlists reference valid commodities;
- boss/run reward definitions reference known items/materials;
- feature/world-era requirements reference known feature IDs.

## Observability

Record enough domain events/metrics to answer:

- Map opens/completions/failures by difficulty/configuration;
- clear-time and practical-ceiling distribution;
- Map-item generation/supply;
- Map material creation/consumption;
- bounty contracts started/completed/failed;
- Coin destroyed by bounty fees;
- family/tier material supply and Bazaar liquidity;
- boss failure/success rates;
- suspicious duplicate/replay patterns;
- player specialization across bounty families.

Analytics events do not replace correctness ledgers/state.

## Verification commands/jobs

Architecture should support integrity checks such as:

- no Map item opened into more than one valid run;
- no terminal run settled twice;
- no clear record references an invalid/non-completed run;
- no bounty authorization produces more summons than configured;
- no completed boss reward settles twice;
- pouch quantities reconcile with central commodity ownership;
- generated Map/bounty material supply reconciles with authoritative source operations.

## Expansion rule

Add new environments, modifiers, objectives, enemy families, bounty families, tiers, bosses, creature variants, and material definitions through the established extension/data mechanisms.

Do not introduce a new transaction/ownership/progression architecture merely because new PvE content is added.