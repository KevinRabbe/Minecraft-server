# V1 Scope

## Goal

V1 proves a persistent multiplayer economy/social loop with safe cross-server state. A player must be able to join, choose an activity, progress, trade with other players, acquire and use skill-gated equipment, participate in social/competitive systems if desired, contribute to persistent community work, leave, and later return with exact authoritative state.

## Launch server roles

- `CITY` — permanent social/economic/build hub.
- `MINE` — renewable extraction area.
- `FOREST` — renewable woodcutting area.
- `FARM` — renewable farming/livestock activity area.
- `NETHER` — renewable Nether gathering/combat area.
- `PVP` — resettable standardized ranked-PvP arena.
- `WAR` — resettable clan-war arena.

The initial deployment may run all roles on one physical machine behind Velocity.

## Launch skills

- Mining
- Woodcutting
- Farming
- Combat
- Refining
- Crafting
- Enchanting

Fishing is intentionally deferred until fishing itself is introduced/unlocked.

## Launch economy

- Decimal Coins stored as fixed-point integers.
- NPC salvage as a deliberately poor guaranteed exit.
- Bazaar for stackable items.
- Auction House for non-stackable items.
- Secure direct player trade.
- Player-driven prices; no server target price.
- First compression tier for resources where needed.

## Launch gameplay systems

- Skills + leaderboards.
- Gathering -> refining -> crafting specialization.
- Minecraft enchanting plus baseline Enchanter/Witch book sales.
- Enchanting meta-skill that amplifies XP earned in other skills but never itself.
- Experience-bottle economy using normal Minecraft XP after bottle use.
- Minecraft brewing/potions and required Nether access.
- Clans from day one.
- Standardized ranked 1v1 PvP.
- Opt-in clan wars using real economic gear through escrow/snapshot settlement.
- Community project lifecycle.
- Region/build lifecycle plus blueprint preservation.
- Provenance/history and immutable event rewards.

## Explicitly not V1

- Fishing and Fishing Harbour.
- Museum.
- Dungeons and bosses.
- Player voting on future civic projects.
- Generic backpacks/warehouses/material banks.
- Factory/logistics automation.
- AFK resource generation.
- Full-loot PvP or open-world clan destruction.
- Large custom-enchantment catalog.
- Random affix/quality/socket/gem/pet-stat systems.
- More compression tiers unless real V1 testing requires them.
- Auto-compression artifact unless real high-throughput play proves manual compression is already friction.

## Definition of done

V1 is done only when the complete acceptance journey in `ACCEPTANCE_TESTS.md` survives transfers, reconnects, controlled restarts, and intentional backend failures without item, Coin, skill, reward, or ownership corruption.
