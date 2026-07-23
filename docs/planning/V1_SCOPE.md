# V1 Scope

## Objective

V1 proves a persistent multiplayer economy/social loop with safe movement between compact gameplay zones and exact authoritative persistent state.

A player must be able to join, choose an activity, progress, trade, acquire and use skill-gated equipment, participate in social/competitive systems if desired, contribute to persistent community work, leave, and later return without item, Coin, skill, reward, ownership, or provenance corruption.

## Launch world model

V1 does **not** use an endless vanilla survival world.

The launch experience is a deliberately small persistent starter region centered on the City/Town. Around it are compact first-step activity pockets such as:

- starter Woodcutting area
- starter Farming area
- starter Mining entrance/area
- early PvE/combat pocket(s)
- a small cave/ruin/wolf-type combat area
- Witch/Apothecary district
- community-project construction space

The exact visual layout is original to this project. Other servers are references for proven structural patterns such as compact zones and horizontal instancing, not content specifications.

Gameplay zones are intentionally small. Capacity grows by creating additional instances of the zone only when players are actually using that zone.

## Launch-accessible systems

### Progression
- Mining
- Woodcutting
- Farming
- Combat
- Refining
- Crafting
- Enchanting
- separate skill leaderboards

Fishing is deferred until its own feature/area is introduced.

### Economy
- fixed-point decimal Coins
- Bazaar for stackable commodities
- Auction House for non-stackable items
- secure direct trade
- NPC salvage as a deliberately poor guaranteed exit
- player-determined prices
- compression only where quantity requires it

### Production and item systems
- gathering with authorized economic sources
- refining
- crafting
- skill-gated item use with unrestricted ownership/trading
- specialized gathering pouches when throughput justifies them
- unique-item identity/provenance where individuality matters

### Enchanting and brewing
- normal Minecraft XP as the actual enchanting resource
- Minecraft enchanting/anvil mechanics retained where suitable
- Enchanting as a 1-100 skill whose main role is an XP amplifier for other skills, never itself
- Witch/Apothecary as expensive bootstrap source for basic enchant books and basic Nether-derived brewing inputs
- Minecraft brewing retained where suitable

### Social/competitive
- clans
- clan chat/roster/roles
- clan War Rating and leaderboard
- standardized opt-in ranked 1v1 PvP with temporary isolated equipment
- opt-in clan wars using real economic equipment through controlled custody/settlement

### Persistent community/world systems
- community-project lifecycle
- controlled construction regions
- project contribution history
- build/schematic archival for significant completed projects
- immutable historical entitlements/rewards
- feature unlock actions such as `ENABLE_FEATURE(feature_id)`

## Nether status

The Nether is **architecturally supported but not player-accessible at launch**.

Before Nether access:

- Witch/Apothecary supplies expensive guaranteed basic Nether-derived brewing inputs.
- A small authorized Nether Wart source may exist in the starter region.
- Natural mass supply from the Nether does not exist yet.

Later:

1. the Nether feature is implemented and remains locked;
2. a Nether Entry/Gate community project is announced;
3. players contribute/build it;
4. completion archives the build and contributor history;
5. `NETHER_ACCESS` becomes available;
6. Nether zone instances/backends are activated only when needed.

## Infrastructure scope

The architecture supports many zone instances and multiple Paper backends, but the first development/playtest deployment is local-PC-first.

V1 does not require one Paper process per gameplay zone. A single backend may host several zone instances. Additional backends/processes are introduced only when measured load or isolation requires them.

Velocity is the entry/routing layer. PostgreSQL is durable authority for persistent network state and critical transactions.

## Explicitly deferred / not launch-accessible

- open-ended vanilla wilderness
- Fishing and Fishing Harbour
- Museum
- full dungeon/boss system (simple persistent PvE pockets are allowed)
- Nether access until community unlock
- player voting on civic projects
- generic backpacks/warehouses/material banks
- factory/logistics automation
- AFK resource generation
- full-loot PvP/open-world clan destruction
- large custom-enchantment catalog
- random equipment quality/affixes/sockets/gems/pets
- extra compression tiers without observed volume need
- auto-compression artifact without observed inventory/compression friction
- huge launch city or large empty travel spaces

## Definition of done

V1 is complete only when the acceptance journey in `../reference/ACCEPTANCE_CRITERIA.md` survives zone transfers, instance replacement, reconnects, controlled restarts, intentional backend failure, transaction retries, and ordinary player concurrency without duplication or state corruption.
