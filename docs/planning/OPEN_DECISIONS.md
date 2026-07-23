# Open Decisions

Only unresolved questions belong here. Do not treat these as implementation requirements until explicitly locked.

## Balance/config values

These are intentionally deferred to playtesting/configuration rather than architecture:

- exact XP curves and Enchanting XP-amplifier curve
- exact zone soft/hard capacities
- instance idle-retirement timeout
- tree/resource/mob respawn rates
- gathering speed/luck curves and multi-block limits
- pouch tiers/capacities/use levels
- recipe ratios and processing durations
- compression ratios if 128:1 is not retained
- NPC salvage return values
- Witch/bootstrap prices and exact bootstrap item allowlist
- clan member cap (current concept around 30)
- PvP rating constants
- clan-war entry/resource costs and reward numbers
- exact leaderboard refresh/cache intervals beyond the current hourly-town-board concept

## World/content decisions

- final name/theme/layout of the starter region and its compact activity zones
- exact first PvE mobs/locations (wolf/zombie/ruin/cave examples are structural placeholders, not copied content)
- whether starter Mine/Forest/Farm transitions are visually seamless world changes or explicit travel interactions
- which zones keep a minimum warm instance versus scale to zero when unused
- final persistent City instance strategy if City concurrency eventually requires more than one copy

## Item/content decisions

- final launch item-definition allowlist
- exact launch equipment families and recipes
- exact custom enchantments, if any, beyond vanilla mechanics
- whether ordinary enchanted equipment always receives network item identity or only items whose individuality matters
- precise high-value/unique-item drop restrictions in disposable zones

## Gameplay decisions

- exact ordinary PvE death consequence (inventory should not use uncontrolled full-loot semantics)
- exact Refining/Crafting commission UX and whether it ships in first public V1 or immediately after base production
- exact mechanism by which Enchanting skill XP is earned from normal Minecraft XP expenditure
- exact rules preventing vanilla enchant combination from bypassing intentionally source-gated enchant tiers if such tiers exist

## Operations decisions

- exact local-PC process layout once first real zone instances exist
- threshold for splitting one Paper process into multiple backends
- exact backend load signals used by later scheduling; V1 may use a trivial assignment policy
- backup cadence and retention policy before public alpha

## Explicit non-decisions

These are already locked and should not be reopened merely because implementation reaches them:

- compact purpose-built zones rather than endless vanilla wilderness
- horizontal zone-instance replication for concurrency
- per-zone demand determines instance count; total network population does not
- backend identity is infrastructure, not gameplay identity
- Nether is locked at launch and later opened by a community project
- player prices are market-discovered
- ownership unrestricted / use skill-gated
- single-writer persistent player state
- PostgreSQL transaction authority for critical value movement
- commodities use quantity accounting; unique items may use stable instance identity
