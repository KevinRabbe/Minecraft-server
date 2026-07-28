# V1 Scope

Status: **Canonical V1 product scope.** Architecture contracts explain how these systems stay correct; `MASTER_ROADMAP.md` defines implementation order. Exact tuning belongs in configuration/playtests unless explicitly locked here.

## Objective

V1 proves a persistent Minecraft-first multiplayer ecosystem in which players can gather, specialize, trade, craft individualized gear, run scalable PvE, form clans, compete voluntarily, vote on world expansion, and return later with exact authoritative state intact.

The world is intentionally path-dependent: developers define valid systems and choices; players determine prices, specialization, expansion order, physical districts, social structures, records, and history.

## Day-0 world

V1 does **not** use endless vanilla wilderness. Day 0 begins from a compact persistent starter region centered on the City/Town plus small purpose-built activity spaces such as:

- starter Woodcutting/Foraging area;
- starter Farming area;
- starter Mining area;
- early ordinary PvE/combat pockets;
- Portal/Map access;
- Bazaar, Auction House, Bank Manager, crafting/production access, clan/social access, and world-expansion voting surfaces.

Gameplay zones stay deliberately compact. Concurrent capacity scales by additional equivalent instances only when that specific zone needs them.

The exact visual layout is original and is not an architectural dependency.

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
- bounty-family progression such as Spider, Zombie, and Golem.

Fishing is not required on Day 0 and may enter through a player-selected Fishing expansion/district.

Skills create specialization through time investment, capability unlocks, efficiency, and QoL rather than permanent classes. Players can later broaden into other skills.

Enchanting retains normal Minecraft XP as its operational resource where suitable. Its skill progression may amplify configured other-skill XP but never itself; it follows the same staged active-cap framework rather than exposing 100 levels at launch.

### Ownership versus use

Players may own, store, trade, or economically acquire items/materials without personally completing the branch that produced them. Use requirements may still gate equipment or activities where explicitly configured.

## Money and banking

Coins use integer/fixed-point authoritative accounting.

### Pocket/spendable money

- money outside the protected bank is immediately spendable;
- ordinary PvE death may destroy a configurable portion of pocket money;
- exact death-loss numbers are balance/config, not architecture.

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

Secure direct trade remains supported. NPC salvage may provide a deliberately poor guaranteed exit and, later, a controlled sink for unwanted individualized equipment.

## Crafting and individualized gear

V1 targets a deliberately narrow pool of roughly **25–30 meaningful items** across established categories rather than a large catalog of filler.

Content categories include:

- several mechanically distinct weapons;
- combat and utility wearables;
- a small artifact set;
- a small number of active-use equipment items;
- a few consumables;
- gathering/logistics/QoL equipment.

Recipes intentionally cross-connect ordinary resources, district resources, Map materials, and bounty materials so no single activity becomes self-sufficient.

### Rolled items

Individualized gear has persistent bounded roll quality. Depending on the item, low-to-high relevant value should generally span roughly **10–30%**. Exact distributions/ranges are per-item balance data.

Most rolls should be usable. Very high and perfect rolls are luxury optimization and may command extreme Auction House prices without being required for basic viability.

Intrinsic roll quality and later upgrade investment are separate concepts. Balance changes must not reroll historical item quality.

## Portal / Map PvE

Maps are a major V1 PvE pillar.

A Map is an individualized/tradable challenge definition built from a compact combination of:

- numeric difficulty;
- environment;
- enemy family;
- objective;
- modifiers;
- elite composition/generation data.

### Difficulty rule

Map difficulty measures encounter power; it is **not** a player-level requirement. Players may attempt content above their realistic strength.

The system may define difficulty values that current gear cannot beat. Available gear, rolls, build quality, mechanical skill, party composition, and later power expansions determine the practical ceiling.

Nether/End do not unlock particular difficulty numbers. Their stronger gear can raise the practical difficulty players are able to clear.

### V1 combinatorial content

Keep the initial pool small and reusable, for example:

- environments such as Forest/Cave/Ruins/Crypt/Corrupted areas;
- enemy families such as Spider/Undead/Golem-Construct plus a small number of others where useful;
- objectives such as Extermination, Elite Hunt, Defense, and Assault;
- roughly 10–12 strong modifiers after the base framework is proven;
- a small elite-trait pool.

Map completion can generate nearby future Maps and Map materials, enabling push/farm/trade loops without requiring Dungeons at launch.

## PvE leaderboards

Maps provide server-authoritative competitive records such as:

- highest solo clear;
- highest group clear;
- fastest clears at meaningful difficulty points.

Historical clear records retain difficulty, time, participants, Map configuration, relevant loadout context, balance version, timestamp, and world/power era.

When major vertical gear becomes available later, older pre-power-jump records remain historically meaningful rather than being erased by the new ceiling.

Leaderboard prestige must not grant mandatory combat power.

## Bounties

Bounties are the second major V1 PvE pillar and are organized by **mob category/family**, such as Spider, Zombie, and Golem.

Basic loop:

`pay bounty-contract fee -> kill required mobs from that family -> earn summon access -> fight bounty boss -> receive family materials`

The payment unlocks the bounty contract/quest; it does not directly buy a boss spawn. Exact kill requirements, fees, tiers, and boss numbers are balance/content data.

### Tiered family materials

Higher bounty tiers introduce higher-grade materials from the same family, for example:

`Spider Web -> higher-grade Web -> Enchanted Web -> rare high-tier Spider component`

Names and exact tiers are content data. Materials remain Bazaar commodities.

### Category specialization

Equipment may specialize against particular bounty families. This makes dedicated Spider/Zombie/Golem loadouts valuable without forcing every player to personally farm every family.

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

Examples of expansion themes may include Fishing, production/logistics, specialized combat/economy content, or major future milestones.

### Physical district rule

Players are **not given a canonical blueprint** for the physical district and the server does not require a developer-authored minimum block count or visual form for ordinary districts.

If players build a huge harbor for a Fishing District, that is their Fishing District. If they build a small 500-block fishing area and decide that is sufficient, that is also valid.

The voted capability and its mechanical content are authoritative; the physical expression belongs to players.

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
- huge launch city or large empty travel spaces.

## Definition of done

V1 is complete only when the acceptance journey in `../reference/ACCEPTANCE_CRITERIA.md` survives ordinary concurrency, cross-backend transfer, instance replacement, reconnects, controlled restarts, intentional failure, transaction retries, market races, Map/Bounty retries, clan permission boundaries, and world-vote resolution without duplication, double rewards, ambiguous ownership, or persistent-state corruption.