# PvE Maps and Bounties

## Purpose

V1 PvE is built primarily from two reusable but spatially distinct systems:

1. **capital-M Map runs** — scalable, tradable, combinatorial instanced PvE whose numeric difficulty measures encounter power;
2. **Bounties** — enemy-family progression layered over authored creatures living in normal persistent world regions, producing family materials, specialization, boss encounters, and Coin sinks.

Dungeons are intentionally deferred until these systems are mature.

The exact launch encounter language, technical entity bases, authored Map-template flows, first-Map elite, modifier/elite semantics, and implementation order are locked in [`../planning/V1_CONTENT_DETAILS.md`](../planning/V1_CONTENT_DETAILS.md). This architecture document defines the durable/system boundaries those mechanics must respect.

## Shared rules

- persistent valuable state lives in PostgreSQL-authoritative records;
- live encounter state may be disposable;
- one operation/result identity prevents duplicate rewards;
- client/UI never declares completion authoritatively;
- balance/content definitions are versioned configuration;
- difficulty/tier numbers are not trusted client input;
- exact HP, damage, kill counts, drop rates, fees, modifier values, and reward curves are balance data.

---

# Part I — Map architecture

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
- enemy package/family
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

Locked V1 objective families:

- **Extermination**;
- **Elite Hunt**.

Defense and Assault are later extensions rather than launch requirements.

For V1, Extermination uses sequential authored objective packs and exact remaining-target progress may be shown. Elite Hunt uses bounded guard packs/gates followed by a marked elite target; completion is the authoritative elite kill. Exact mechanics are specified in `V1_CONTENT_DETAILS.md`.

## V1 environments and Map enemy packages

Environments and Map enemy packages are stable content IDs referenced by Maps/runs.

Locked V1 environments:

- **Forgotten Bastion**;
- **Flooded Depths**;
- **Windscar Ruins**.

Locked V1 Map-only enemy packages:

- **Relic Guard**;
- **Deep Brood**;
- **Ruin Raiders**.

These Map enemy identities are separate from the Rootborn/Ashbound/Veilborn Bounty families. Capital-M Maps must not silently turn those normal-world Bounty ecosystems into the default Map enemy pool.

The existing Forest + Spider + Extermination implementation remains useful fixture/proof content and is not canonical launch identity.

V1 terrain is **authored compact templates with deterministic encounter/spawn anchors**, not procedural terrain generation. The Map seed reproduces bounded encounter choices such as anchors, package composition, elite assignment, and supported modifier variation.

Environment definitions may supply:

- template/generation profile;
- spawn geometry rules;
- objective compatibility;
- hazard rules;
- presentation references.

Map enemy-package definitions may supply:

- spawn pool;
- elite compatibility;
- category tags;
- reward/drop profile;
- behavior capability references.

Do not tie core Map logic to hardcoded entity-type branches where data/registered behavior can express the distinction.

## Modifiers

Modifiers compose through explicit extension points.

Locked V1 modifier pool:

- **Fortified**;
- **Relentless**;
- **Swarming**.

V1 semantics are fixed at the capability level:

- Fortified increases visible armor/guard/knockback-resistance pressure rather than becoming a giant hidden HP multiplier;
- Relentless reduces configured recovery/cadence and may modestly increase movement pressure, with readability floors and no bundled raw-damage multiplier;
- Swarming increases normal-target composition/density under strict live/per-wave caps and never duplicates the objective elite merely because the modifier exists.

Modifier definitions include compatibility/incompatibility metadata where combinations can be invalid.

Avoid arbitrary modifier code that mutates unrelated persistent state.

The current Paper runtime may implement this pool incrementally; unsupported modifier-bearing runs must continue to fail closed until their runtime semantics exist.

## Elites

Elite traits are reusable behavior/stat packages applied to eligible enemies.

Locked V1 elite-trait pool:

- **Bulwark** — visible temporary guard with a flank/guard-break exposure window;
- **Hunter** — marked committed pursuit/lunge whose failed commitment creates recovery;
- **Volatile** — clearly telegraphed delayed post-death burst/hazard, never zero-delay surprise damage.

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

## First Map acquisition and progression

The renewable first-Map source is the **Ruinbound Champion** in the Hub's directly walkable starter Combat area.

Rules:

- the Champion is an authored managed elite using a Husk as its initial technical base, with readable committed-cleave and linebreaker-rush mechanics;
- its watchyard sits near the far end of the starter Combat path before, but does not block, the first Rootborn portal;
- one qualifying authoritative player kill issues exactly one individualized bootstrap Map;
- bootstrap profile is difficulty **1**, **Forgotten Bastion**, **Relic Guard**, **Extermination**, **no modifiers**;
- issuance is recoverable from the authoritative managed-kill operation and retry cannot duplicate the Map;
- the Map remains tradable after issuance;
- it requires no Bounty completion, Coin payment, vendor purchase, or crafting prerequisite;
- the source remains renewable so a failed/consumed Map cannot permanently lock a player out of the system;
- once inside the Map loop, successful runs may create future Map items around the cleared difficulty using a configured bounded distribution;
- generated difficulty is validated within supported range;
- progression jumps are bounded/configured;
- each generated Map gets normal unique-item identity/provenance;
- generation occurs inside or is causally tied to the exactly-once completion settlement;
- failed runs do not silently restore the opened Map.

## Map materials and rewards

Locked V1 Map materials are fungible commodities:

- `material.relic_alloy` — **Relic Alloy**;
- `material.resonant_crystal` — **Resonant Crystal**;
- `material.waystone_shard` — **Waystone Shard**.

V1 does not create a separate numbered rarity/tier ladder for these materials. Difficulty/reward policy changes quantity/scarcity instead.

Successful Map completion may create Map materials, individualized items/Maps, and a bounded Coin payout for eligible participants.

Coin is **not** modeled as a fake Map reward item. Coin payout uses the central `CoinWalletRepository.creditFromSystem` authority with deterministic/idempotent operation identity tied to the completed run/player. Completion recovery must cover the payout so retry/restart cannot mint twice or silently omit an already-committed payout.

Map item/material reward settlement continues through the ordinary economic/value kernel. The Map system never edits wallet/commodity/item tables ad hoc.

## PvE death interaction

V1 Map death **does not also apply the ordinary persistent-world pocket-Coin death-loss rule**. Failing/consuming the Map run is already the Map-specific consequence, so the player is not charged a second ordinary-world death tax.

The 5% pocket-Coin death-loss policy belongs only to explicitly configured deeper persistent combat regions (initially Rootborn, Ashbound, and Veilborn). Protected Bank Coin and managed carried items remain outside that loss mechanism.

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

- highest solo clear;
- highest group clear;
- fastest clear at selected/meaningful difficulty points.

Historical era records remain queryable after stronger gear becomes available.

Leaderboard rewards are prestige/history/cosmetic, not mandatory combat power.

---

# Part II — Bounty architecture

## Normal-world spatial rule

Bounties are **not** Bounty dungeons and are not capital-M Map instances.

Rootborn, Ashbound, and Veilborn inhabit authored normal-world regional activity areas, analogous to Mining/Woodcutting/Farming regions. Players travel through the combat portal chain and encounter family creatures as part of ordinary regional traversal.

Locked initial chain:

`Hub / starter Combat -> Rootborn Region -> Ashbound Region -> Veilborn Region`

The portal chain is player-facing geography, not a central destination selector. Players normally move through each compact region to reach its onward portal.

Backend/process boundaries may align with those regional transitions, but backend identity remains infrastructure rather than gameplay identity.

## Bounty family

A Bounty Family is a stable **enemy-ecosystem progression/economy identity**, not a one-to-one vanilla Minecraft mob category.

The initial V1 families are:

- **Rootborn**;
- **Ashbound**;
- **Veilborn**.

Each family contains multiple creature roles and variants, including normal/common creatures, mobility/ambush roles, heavy/frontline roles, support/control roles, elites, and a boss identity. Families should be mechanically distinguishable enough that learning one ecosystem does not make the others feel like simple reskins.

The exact V1 creature identities, technical bases, and combat-language mechanics are locked in `V1_CONTENT_DETAILS.md`. Vanilla Minecraft entities may be modified/reused as technical bases for movement, hitbox, pathfinding, animation, or other implementation convenience. Underlying entity type is not the player-facing Bounty identity, and conflicting vanilla teleport/spell/AI behavior may be suppressed to preserve readable authored mechanics. Custom models/presentation may replace representation later without changing persistent Bounty authority.

The existing Zombie T1 implementation remains development/vertical-slice fixture content that proves the generic contract, kill-progress, boss authorization, reward, and pouch authorities. It is not canonical launch content.

A family groups:

- eligible authored normal-world creatures/variants and family tags;
- tiers;
- contract definitions;
- boss encounter definition(s);
- family material ladder;
- pouch eligibility;
- family-specialized equipment/recipe references.

## Bounty tier

A tier is one configured step within a family.

V1 uses **two tiers per family**, with four normal creature roles plus one boss identity per family across the launch envelope.

A tier may define:

- progression requirement/access;
- Coin contract fee;
- eligible-hunt target/requirements;
- eligible creature roles/variants;
- boss authorization/encounter definition;
- reward/material profile;
- encounter/boss numeric/mechanic version.

Higher tiers should introduce stronger variants, new roles, new combinations, and additional mechanics rather than being constrained to HP/damage scaling alone.

Exact kill counts and numerical values are balance/content data.

## Contract lifecycle

Conceptually, a contract moves from available -> active hunt -> boss-ready/authorized -> boss attempt -> completed/failed/ended.

Exact state names and the player-facing boss trigger/summon presentation may vary. The persistence rule is what matters: retries must not charge twice, duplicate hunt progress, create two valid boss attempts from one authorization, or settle rewards twice.

## Contract fee

Starting a Bounty contract performs an explicit Coin sink transaction.

The fee buys/activates the **contract/quest**, not a direct boss purchase.

The fee operation must be idempotent. A lost response/retry cannot charge twice or create two active contracts unless the game explicitly supports multiple concurrent contracts.

## Eligible family kills

Only server-observed kills of creatures explicitly tagged/authorized for that family/tier advance a contract.

Each qualifying progression event must be uniquely attributable enough to prevent retry/double-event duplication.

The architecture must support:

- authored ordinary-world family creatures;
- stronger/specialist variants at higher tiers;
- future custom-modeled creatures whose underlying Minecraft entity type is only a runtime implementation detail.

Natural vanilla entities, player-spawned/farmed entities, unrelated mobs, and capital-M Map enemy packages must not accidentally count merely because they share an underlying Minecraft entity type with an authored Bounty creature.

## Boss authorization and encounter

When the configured hunt requirement is satisfied, the contract may gain an explicit boss-encounter authorization according to the definition.

The existing implementation may represent this as summon authorization; that internal mechanism does **not** require the final player-facing content to look like a dungeon key or literal summon altar.

Authorization is persistent value/state and must be consumed atomically with valid boss encounter creation so concurrent requests cannot create two valid bosses from one authorization.

The boss live runtime may be disposable, but persistent contract/authorization/result state is not.

Boss completion is server-authoritative and settles at most once.

Failure policy must be explicit at implementation/content time and remain free of duplication/refund ambiguity.

Bosses belong to the family ecosystem and express the locked family mechanics rather than simply being a larger-stat version of one vanilla base mob.

## Tiered family materials

Locked V1 material ladders:

- **Rootborn:** Root Fiber -> Ancient Resin -> Heartwood Core;
- **Ashbound:** Cinder Shard -> Blackglass -> Kilnheart;
- **Veilborn:** Veil Thread -> Phaseglass -> Gate Fragment.

The first material is broadly supplied by ordinary family encounters, the second is higher-grade Tier-2 material, and the third is a scarce boss component.

All normal Bounty-family materials are Bazaar-tradable crafting commodities, not generic progression tokens.

## Cross-system recipes

Bounty materials intentionally connect to other systems.

The locked V1 recipe graph mixes:

- ordinary resources;
- Map materials;
- Bounty-family materials.

Common family materials retain repeat demand through consumables, pouches, and selected general recipes. Boss components are deliberately limited to three signature launch recipes:

- Heartwood Core -> Thornhook;
- Kilnheart -> Kilnbreaker;
- Gate Fragment -> Phase Anchor.

This encourages specialization/trade rather than forcing every player to complete every branch personally.

## Family-specialized equipment

Locked specialized launch pairs:

- **Rootborn:** Heartwood Mantle + Thornhook;
- **Ashbound:** Blackglass Guard + Kilnbreaker;
- **Veilborn:** Gatefinder Lens + Phase Anchor.

These pieces counter characteristic family mechanics rather than applying flat family-damage multipliers and remain useful outside their source family. Exact launch item mechanics are locked in `V1_CONTENT_DETAILS.md`.

Avoid making one universal set strictly replace all family builds.

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

For every terminal/interrupted state, define whether the contract remains active, boss authorization remains/was consumed, or boss attempt is failed.

Regardless of policy:

- no duplicate fee refund/charge;
- no duplicated hunt progress;
- no double boss attempt from one authorization;
- no double boss reward;
- recovery uses persistent state rather than trusting surviving entities.

---

# Part III — Shared extensibility and verification

## Data/configuration

Map/Bounty content is version-controlled configuration with stable IDs.

Validate at startup where applicable:

- all environment/Map-enemy/objective/modifier/Bounty-family/tier IDs are unique;
- all references exist;
- modifier combinations/compatibility are valid;
- Map difficulty/ranges are bounded;
- Bounty fees/kill counts/reward quantities are non-negative/valid;
- eligible family creature/variant references resolve to registered authored behavior/content;
- Map enemy packages do not silently become Bounty progress sources;
- material definitions exist and are Bazaar-compatible where required;
- pouch family allowlists reference valid commodities;
- boss/run reward definitions reference known items/materials;
- feature/world-era requirements reference known feature IDs.

## Observability

Record enough domain events/metrics to answer:

- Map opens/completions/failures by difficulty/configuration;
- Map Coin payout volume by difficulty/world era;
- clear-time and practical-ceiling distribution;
- Map-item generation/supply;
- Map material creation/consumption;
- Bounty contracts started/completed/failed;
- Coin destroyed by Bounty fees;
- family/tier material supply and Bazaar liquidity;
- boss failure/success rates;
- suspicious duplicate/replay patterns;
- player specialization across Bounty families.

Analytics events do not replace correctness ledgers/state.

## Verification commands/jobs

Architecture should support integrity checks such as:

- no Map item opened into more than one valid run;
- no terminal run settled twice;
- no successful Map participant receives the same Coin payout twice;
- no clear record references an invalid/non-completed run;
- no Bounty authorization produces more boss attempts than configured;
- no completed boss reward settles twice;
- pouch quantities reconcile with central commodity ownership;
- generated Map/Bounty material supply reconciles with authoritative source operations.

## Expansion rule

Add new regions, Map environments, Map enemy packages, modifiers, objectives, Bounty families, tiers, bosses, creature variants, and material definitions through the established extension/data mechanisms.

Do not introduce a new transaction/ownership/progression architecture merely because new PvE content is added.
