# V1 Implementation Milestones

Each milestone ends in a behavioral acceptance boundary. Do not pull later gameplay scope forward unless it is required to satisfy that boundary.

## M0 — Contract and bootstrap

- V1 design docs
- Gradle multi-module repository
- shared `common`, `paper`, `velocity` modules
- Java 25 toolchain
- current Paper/Velocity API baseline
- PostgreSQL local service
- runtime configuration layout
- one-command local network startup
- modern Velocity forwarding configured

**Acceptance:** clean checkout can start Velocity, all logical Paper roles, and PostgreSQL; Velocity can route to each role.

## M1 — Cross-server player correctness

- stable internal player identity
- network session ownership lease
- state versioning
- persistent inventory/equipment
- transfer tickets
- safe role transfer
- reconnect/crash recovery

**Acceptance:** one unique item survives repeated transfers/restarts/backend kills and still exists exactly once.

## M2 — Activity worlds and resource authorization

- City/Mine/Forest/Farm/Nether/PvP/War world policies
- renewable resource-area lifecycle
- authorized economic resource nodes/actions
- player-placed/automation exploit prevention
- skill XP-source definitions

**Acceptance:** legitimate gathering produces configured resources; trivial place/break/automation loops do not.

## M3 — Skills

- Mining, Woodcutting, Farming, Combat, Refining, Crafting, Enchanting
- levels 1-100
- XP curves/config
- Speed/Luck framework where applicable
- milestone unlock/use requirements
- leaderboards
- gathering pouches

**Acceptance:** progression persists exactly; bonuses and XP-source eligibility remain separate; use requirements cannot be bypassed by ownership/trade.

## M4 — Items and equipment

- item definitions
- unique item instances/provenance
- core resource/refining graph
- first compression tier
- custom tools/equipment
- resource pack baseline

**Acceptance:** items retain identity/provenance across transfer/trade/restart; compression is lossless and grants no XP.

## M5 — Economy

- fixed-point Coins
- NPC salvage
- Bazaar
- Auction House
- secure direct trade
- escrow/idempotent settlement
- basic market history

**Acceptance:** concurrent fills/cancels/restarts cannot duplicate Coins or items.

## M6 — Refining and crafting production

- refining jobs/recipes
- crafting recipes
- production vs use requirements
- specialist commission path if retained after first-play usability test

**Acceptance:** a production specialist can create an item they cannot personally use and transfer it safely to a qualified user.

## M7 — Enchanting, XP economy, brewing

- Lapis XP-bottle recipe/economy
- normal Minecraft XP integration
- Enchanting Skill XP Amplifier
- Witch/Enchanter baseline book catalog
- custom/rare enchant framework and tier-source restrictions
- Brewing Stand integration
- V1 potion allowlist and Nether ingredient sources

**Acceptance:** a peaceful player can acquire XP, enchantment books, and potion inputs through trade without being forced into combat.

## M8 — Clans and ranked PvP

- clan lifecycle/roles/chat/history
- standardized 1v1 ranked PvP
- rating/leaderboard

**Acceptance:** permanent progression cannot leak power into standardized ranked loadouts.

## M9 — Clan wars

- challenge/accept/roster
- war loadout lock/snapshot
- objective match
- durable event/settlement records
- rating/history

**Acceptance:** killing WAR during battle/settlement cannot duplicate or incorrectly destroy persistent gear.

## M10 — City regions and community projects

- protected/player/project regions
- contribution tracking
- project lifecycle
- completion actions
- schematic export/checksum/versioning
- blueprint entitlement
- historical reward issuance

**Acceptance:** one real project completes end-to-end with immutable archive and idempotent historical rewards.

## M11 — Hardening and alpha readiness

- capability-based staff mode
- audit log
- exploit/rate protections
- structured analytics events
- backup/restore procedure
- deployment/restart documentation
- config validation
- graceful shutdown/recovery testing

**Acceptance:** clean restore plus the full end-to-end V1 journey in `ACCEPTANCE_TESTS.md` passes.
