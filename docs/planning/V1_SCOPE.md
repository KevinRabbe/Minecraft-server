# V1 Scope

Status: **Canonical V1 product scope.** Architecture contracts explain how these systems stay correct; `MASTER_ROADMAP.md` defines implementation order. Detailed launch combat/Map/equipment/death mechanics are locked in [`V1_CONTENT_DETAILS.md`](V1_CONTENT_DETAILS.md). Exact tuning belongs in configuration/playtests unless explicitly locked here.

## Objective

V1 proves a persistent Minecraft-first multiplayer ecosystem in which players can gather, specialize, trade, craft individualized gear, run scalable PvE, form clans, compete voluntarily, vote on world expansion, physically build districts, and return later with exact authoritative state intact.

The world is intentionally path-dependent: developers define valid systems and choices; players determine prices, specialization, expansion order, physical districts, social structures, records, and history.

## Day-0 world

V1 does **not** use endless vanilla wilderness and does not require a large finished developer-built capital. Day 0 begins from one compact persistent **Hub Region** containing a simple functional starter settlement plus directly walkable starter activity spaces:

- starter Woodcutting/Foraging area;
- starter Farming area;
- starter Mining area;
- starter Combat area;
- all essential launch NPCs/services, including Bazaar, Auction House, Bank Manager, crafting/production access, clan/social access, Map access, and world-expansion voting surfaces.

The launch civic core is deliberately simple and utilitarian because later districts are built by players. A player-built district/build project costs the **actual Minecraft blocks builders place**; ordinary district construction must not also require a second abstract material-deposit progress bar for the same build.

### Regional portal-chain rule

Normal-world geography is made from **small, dense regional worlds connected by spatial portal chains**. Players normally walk through the current region to reach its onward portal/gate rather than choosing every destination from one central portal menu.

The initial combat chain is:

`Hub / starter Combat -> Rootborn Region -> Ashbound Region -> Veilborn Region`

Each destination is a normal-world activity region, not a dungeon. Onward portals should sit at meaningful destinations inside/at the far side of the region so the player still traverses and uses the regional map.

The same pattern can extend gathering branches later:

`Hub starter activity -> portal -> specialized region -> portal -> deeper region`

Portals may align with Paper/backend boundaries, but backend topology is an implementation detail and must not turn the player-facing world into a destination-selection lobby.

Gameplay regions stay deliberately compact. Concurrent capacity scales by additional equivalent instances only when that specific region needs them.

## Progression and specialization

### Skills

Launch uses a generic skill/progression framework with an **active cap of 50**. A later expansion raises the active cap to 75; a much later progression era raises it to 100. XP does not accumulate invisibly above the active cap.

Launch/early progression may include:

- Mining;
- Woodcutting/Foraging;
- Farming;
- Combat-related progression where useful;
- Refining;
- Crafting;
- Enchanting;
- bounty-family progression for Rootborn, Ashbound, and Veilborn.

Fishing is not required on Day 0 and may enter through a player-selected Fishing expansion/district.

Skills create specialization through time investment, capability unlocks, efficiency, and QoL rather than permanent classes. Players can later broaden into other skills.

Enchanting retains normal Minecraft XP as its operational resource where suitable. Its skill progression may amplify configured other-skill XP but never itself; it follows the same staged active-cap framework rather than exposing 100 levels at launch.

### Ownership versus use

Players may own, store, trade, or economically acquire items/materials without personally completing the branch that produced them. Use requirements may still gate equipment or activities where explicitly configured.

## Money and banking

Coins use integer/fixed-point authoritative accounting.

### Pocket/spendable money

- money outside the protected bank is immediately spendable;
- ordinary persistent combat death in the Rootborn, Ashbound, and Veilborn regions destroys **5%** of current pocket Coin, rounded down to the minor unit;
- the Hub/starter Combat area, gathering-only areas, capital-M Maps, Ranked Arena, and Clan War do not use this ordinary death-loss rule;
- lost Coin is burned through authoritative ledger evidence rather than dropped into the world;
- managed carried items and protected Bank balance remain safe from this mechanic.

The initial percentage is policy-versioned configuration, but the V1 mechanic/eligibility boundary above is locked.

### Initial Coin faucet

A fresh economy starts from zero through **successful Map clears**:

- the starter Combat area contains the renewable **Ruinbound Champion** elite, which awards the canonical difficulty-1 `Forgotten Bastion + Relic Guard + Extermination + no modifier` Map without requiring Coins, Bounty completion, a vendor purchase, or crafting;
- successful Map clears award each eligible participant a bounded Coin payout in addition to normal Map rewards/successor-Map progression;
- failed Maps do not pay the success reward;
- exact payout and difficulty curve are balance data;
- Coin creation uses the central wallet authority with deterministic/idempotent per-run/player operation identity and completion recovery.

Build projects are not a Coin faucet. Their material demand comes from the blocks builders physically use.

### Bank Manager

- deposited money is protected from ordinary PvE death loss;
- Bank Manager progression increases protected capacity;
- upgraded levels may provide small daily interest;
- exact capacities, upgrade costs, and rates are balance/config (a top-end value around 0.3% daily is only a current tuning example, not a locked number).

Bank upgrades, bounty fees, market fees, salvage/crafting sinks, and other explicit operations can remove Coins from circulation. Interest and configured rewards are explicit faucets. Currency creation/destruction must remain measurable.

## Economy

### Bazaar

The Bazaar is the order-book market for fungible/stackable commodities:

- ores/crops/ordinary resources;
- processed materials;
- Map materials;
- bounty-family materials of every tier;
- Fishing/district resources when those systems exist;
- other standardized ingredients.

Use buy/sell orders with player-discovered prices, escrow, deterministic matching, partial fills, offline settlement, and explicit fees/sinks.

Bounty materials remain fully Bazaar-tradable. The game should not require personal completion of every bounty family merely to economically obtain its materials.

### Auction House

The Auction House handles individualized/non-fungible items, especially:

- rolled weapons/armor/equipment;
- artifacts/active-use equipment where individualized;
- other unique items;
- tradable individualized Map objects where applicable.

Listings use authoritative item custody/escrow. Finished equipment is not soulbound by default.

### Direct trade / salvage

Secure direct trade remains supported. V1 salvage is a deliberately poor guaranteed material exit for unwanted individualized equipment: it destroys the exact item, refunds no Coin/boss components/upgrade investment, ignores roll quality/upgrade level for yield, and returns only configured ordinary/common/mid-tier recipe material families substantially below reconstruction cost.

## Crafting and individualized gear

V1 locks a deliberately narrow pool of **28 meaningful items/equipment pieces** rather than a large catalog of filler.

Launch categories are:

- six weapons;
- five wearables/defensive equipment pieces;
- five artifacts/active-use equipment pieces;
- four consumables;
- eight gathering/logistics/QoL pieces.

The exact allowlist and recipe-source graph are locked in issue #104. Recipes intentionally cross-connect ordinary resources, Map materials, and Bounty materials. Exact ingredient quantities and Crafting-XP values remain balance data.

Three scarce Bounty boss components are reserved for signature launch recipes rather than becoming generic ingredients:

- Heartwood Core -> Thornhook;
- Kilnheart -> Kilnbreaker;
- Gate Fragment -> Phase Anchor.

### Rolled items

V1 keeps the intrinsic roll surface intentionally small:

- weapons roll exactly one `damage` property in the initial `10000..12000` multiplier range;
- wearables roll exactly one `defense` property in the initial `10000..11500` range;
- Forester Axe, Deepvein Pick, and Harvest Sickle roll exactly one `gathering_speed` property in the initial `10000..12000` range;
- active equipment, consumables, Prospector Lantern, Packframe, and family pouches have no intrinsic roll in V1.

Normalized quality remains the persistent historical state and creation uses the existing uniform `0..10000` quality distribution. Most rolls remain usable; perfect rolls are luxury optimization rather than progression requirements.

Rolled V1 items use a deterministic **+0..+5** upgrade track. Each level adds 2% at the rolled-stat upgrade stage, for +10% at +5. Upgrades never reroll/destroy/downgrade the item, and fixed utility mechanics do not scale with upgrade level. Upgrade costs consume Coin plus recipe-related non-boss materials; Heartwood Core/Kilnheart/Gate Fragment are not repeat upgrade costs. Exact costs remain balance data.

Intrinsic roll quality and later upgrade investment remain separate concepts. Balance changes must not reroll historical item quality.

## Portal / Map PvE

Capital-M **Maps** are a major V1 PvE pillar and are separate from normal-world Bounty regions.

A Map is an individualized/tradable challenge definition built from a compact combination of:

- numeric difficulty;
- environment;
- Map enemy package;
- objective;
- modifiers;
- elite composition/generation data.

### Difficulty rule

Map difficulty measures encounter power; it is **not** a player-level requirement. Players may attempt content above their realistic strength.

The system may define difficulty values that current gear cannot beat. Available gear, rolls, build quality, mechanical skill, party composition, and later power expansions determine the practical ceiling.

Nether/End do not unlock particular difficulty numbers. Their stronger gear can raise the practical difficulty players are able to clear.

### V1 Map content

The canonical launch pool is deliberately compact:

- **environments:** Forgotten Bastion, Flooded Depths, Windscar Ruins;
- **Map-only enemy packages:** Relic Guard, Deep Brood, Ruin Raiders;
- **objectives:** Extermination, Elite Hunt;
- **modifiers:** Fortified, Relentless, Swarming;
- **elite traits:** Bulwark, Hunter, Volatile;
- **Map materials:** Relic Alloy, Resonant Crystal, Waystone Shard.

The Map enemy packages are not Rootborn/Ashbound/Veilborn Bounty families. The existing Forest + Spider + Extermination content remains development/proof fixture content rather than canonical launch identity.

V1 Map terrain uses compact **authored templates with deterministic encounter/spawn anchors**, not procedural terrain generation. The exact environment flows, Map-package roles/technical bases, objective semantics, modifier/elite capabilities, and implementation order are locked in `V1_CONTENT_DETAILS.md`.

V1 does not need separate `T1/T2/T3` copies of Map materials. Difficulty/reward policy can vary their quantity/scarcity instead.

The current Paper runtime may implement the locked pool incrementally; unsupported objective/modifier mechanics are implementation work, not a reason to reopen the content identity.

### First Map acquisition and continuation

The renewable **Ruinbound Champion** lives in a side watchyard near the far end of the Hub's walkable starter Combat path, before but not blocking the first Rootborn portal. A qualifying authoritative player kill issues one individualized difficulty-1 Forgotten Bastion/Relic Guard/Extermination Map with no modifier. The source remains renewable so failed/consumed Maps cannot permanently lock a player out of the system.

Successful Map completion can then generate successor Maps around the cleared difficulty and produce Map materials/Coins, enabling push/farm/trade loops without requiring Dungeons at launch.

## PvE leaderboards

Maps provide server-authoritative competitive records such as:

- highest solo clear;
- highest group clear;
- fastest clears at meaningful difficulty points.

Historical clear records retain difficulty, time, participants, Map configuration, relevant loadout context, balance version, timestamp, and world/power era.

When major vertical gear becomes available later, older pre-power-jump records remain historically meaningful rather than being erased by the new ceiling.

Leaderboard prestige must not grant mandatory combat power.

## Bounties

Bounties are the second major V1 PvE pillar and are organized by **original enemy ecosystems**, not one-to-one vanilla Minecraft mob categories.

The initial V1 families are:

- **Rootborn**;
- **Ashbound**;
- **Veilborn**.

Each family contains multiple normal creatures, specialist/support variants, elites, and a boss identity. Vanilla Minecraft entities may be heavily modified and reused as technical bases where useful, and custom models may replace their presentation later, but the player-facing identity is the original family and its creatures rather than the underlying vanilla entity type.

The exact launch technical bases and canonical combat-language/role mechanics for all three families and their bosses are locked in `V1_CONTENT_DETAILS.md`. Vanilla behavior that conflicts with those readable authored mechanics may be suppressed.

The current Zombie T1 implementation is a development/vertical-slice fixture that proves the generic Bounty authority. It is not canonical launch content.

### Normal-world Bounty rule

Bounty families live in **normal persistent regional activity areas**, analogous to Mining/Woodcutting/Farming regions. They are not Bounty dungeons and are not the default enemy pool for capital-M Maps.

Players travel through the combat portal chain and hunt authored family creatures in those regions. A Bounty contract overlays progression onto those normal-world kills.

The launch family envelope is two tiers per family, with four normal creature roles plus one boss identity per family. Higher tiers add roles, elite variants, combinations, and mechanics rather than only HP/damage.

The exact boss trigger/summon presentation can remain an implementation/content decision as long as contract progression and boss settlement remain authoritative and idempotent.

### Family materials

The locked launch ladders are:

- **Rootborn:** Root Fiber -> Ancient Resin -> Heartwood Core;
- **Ashbound:** Cinder Shard -> Blackglass -> Kilnheart;
- **Veilborn:** Veil Thread -> Phaseglass -> Gate Fragment.

The first material is broadly supplied, the second is a higher-grade Tier-2 material, and the third is a scarce boss component. All are Bazaar-tradable crafting commodities rather than generic progression tokens.

### Family specialization

The initial specialized gear pairs are:

- Rootborn: Heartwood Mantle + Thornhook;
- Ashbound: Blackglass Guard + Kilnbreaker;
- Veilborn: Gatefinder Lens + Phase Anchor.

These counter characteristic family mechanics rather than applying simple flat family-damage multipliers and remain useful outside their source family. The fixed launch mechanics for all 28 items are specified in `V1_CONTENT_DETAILS.md`.

### Bounty pouches

Each bounty family may have its own material pouch. The pouch is specialized QoL/storage; it does not change the material's tradability. Pouch capacity may increase with that bounty progression.

## Clans and emergent division of labor

V1 supports clans with persistent membership, roles/permissions, chat/roster, treasury, shared storage, and auditability where valuable assets are involved.

Large clans may organize members as miners, farmers, bounty specialists, Map pushers, crafters, traders, builders, etc. The game does not assign hard professions; specialization emerges from skill/time investment, equipment, capital, market knowledge, and coordination.

Solo/small-group players remain economically relevant because specialization output is tradable through the wider market.

## Opt-in competition

Previously locked V1 competitive systems remain in scope:

### Ranked PvP

- explicit opt-in;
- isolated standardized 1v1 format initially;
- temporary standardized loadout removes persistent economic power advantage;
- rating/leaderboard result applies exactly once.

### Clan war

- explicit opt-in between clans;
- controlled custody/snapshot of real economic equipment/resources;
- isolated match runtime;
- deterministic settlement and history;
- no uncontrolled open-world full-loot/destruction semantics.

Prestige/rating may matter socially but must not grant mandatory progression power.

## World expansion voting and districts

Player voting is a core persistent-world system, not a deferred civic poll.

Players vote on the **capability/theme that becomes available next**. Developers guarantee valid choices and correct vote execution but do not steer which valid choice should win.

The first locked expansion-candidate pool is:

- Deeper Woodcutting Region;
- Deeper Mining Region;
- Deeper Farming Region.

Each winning choice activates the next compact portal-chain region from the corresponding starter branch rather than enlarging the Hub into a huge continent. Closed/inactive portal hooks can make those future branches visible before they are selected.

Future expansion themes may include Fishing, production/logistics, specialized combat/economy content, or major later milestones.

### Physical district rule

Players are **not given a canonical blueprint** for the physical district and the server does not require a developer-authored minimum block count or visual form for ordinary districts.

If players build a huge harbor for a Fishing District, that is their Fishing District. If they build a small 500-block fishing area and decide that is sufficient, that is also valid.

The voted capability and its mechanical content are authoritative; the physical expression belongs to players. Construction consumes the blocks actually placed by builders rather than an abstract duplicate resource payment.

Most ordinary districts expand horizontally through QoL, specialization, resources, production, or sidegrades. Selected milestones such as Nether and End may introduce much larger vertical gear power.

## Community projects and history

The generic project/history infrastructure may still support contributions, protected builds, archival, feature actions, and historical attribution where a specific feature genuinely requires them.

It must **not** be silently applied as a hidden blueprint/progress-bar requirement to ordinary voted districts.

Major events and decisions become authoritative history, including:

- Day-0 world opening;
- expansion votes/results;
- feature unlocks;
- significant PvE first clears/records;
- important project completion where projects are explicitly used;
- historical participation/recognition.

History records what players actually did. It does not fabricate a predetermined narrative.

## Nether and End direction

Nether remains architecturally supported but inaccessible on Day 0. End is also a later major progression milestone.

Their exact player-vote/project activation flow is specified separately and may evolve through implementation, but these constraints are locked:

- developers do not force the population to choose them at a preferred time;
- their availability introduces substantially stronger gear/content than ordinary horizontal districts;
- Map difficulty itself remains ungated;
- physical player construction is not required to match a developer-authored blueprint.

Before Nether access, any limited bootstrap inputs needed to keep existing Minecraft systems functional may come from explicit controlled sources such as the Witch/Apothecary system.

## Infrastructure scope

- Velocity is the entry/routing layer.
- PostgreSQL is durable authority for persistent network state and critical value movement.
- Paper hosts live gameplay while holding explicit player-state ownership.
- one backend may host several zone instances;
- more processes/machines are introduced only from measured load/isolation/operational need;
- persistent valuable state never depends on the lifetime of a disposable instance.

## Explicitly deferred / not required for Day-0 launch

- endless vanilla wilderness;
- Dungeons/raids as full handcrafted PvE systems (Maps + Bounties are the launch PvE backbone);
- Nether access until players unlock/choose it through the later world-progression flow;
- End access until its later world-progression stage;
- generic AFK resource generation;
- factory/logistics automation as a separate large system unless a voted expansion explicitly introduces a bounded version;
- uncontrolled open-world/full-loot PvP destruction;
- huge custom-enchantment catalogs;
- sockets/gems/pets unless later evidence justifies them;
- extra currencies/compression tiers without an observed need;
- huge launch city or large empty travel spaces;
- central portal-selector lobby topology for ordinary regional progression.

## Definition of done

V1 is complete only when the acceptance journey in `../reference/ACCEPTANCE_CRITERIA.md` survives ordinary concurrency, cross-backend transfer, instance replacement, reconnects, controlled restarts, intentional failure, transaction retries, market races, Map/Bounty retries, clan permission boundaries, and world-vote resolution without duplication, double rewards, ambiguous ownership, or persistent-state corruption.
