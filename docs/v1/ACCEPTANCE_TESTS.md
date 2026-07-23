# V1 Acceptance Tests

These are behavioral acceptance tests. Exact balance numbers are intentionally excluded.

## A. Network/state correctness

1. Create one unique item instance.
2. Connect to City.
3. Transfer City -> Mine -> Forest -> Farm -> Nether -> City.
4. Disconnect and reconnect.
5. Restart one backend and reconnect.
6. Restart the whole network and reconnect.
7. Verify exactly one valid copy of the item exists and player state/version is correct.

Repeat while intentionally killing source or target backends during transfer. Failure may delay/recover the transfer but must not duplicate or silently lose committed state.

## B. Gathering exploit boundary

For each activity world:

- legitimate authorized resource actions produce configured resources
- allowed XP sources produce the correct skill XP
- resources that only receive skill bonuses (for example Sand if configured that way) do not accidentally train the skill
- player-placed block loops do not mint economic resources/XP
- piston/water/hopper/explosion/automation paths do not create unauthorized custom economic output

## C. Skills and item use

- level progression persists across reconnect/server transfer
- a player may own/trade a high-level item below its use requirement
- use is rejected below the required skill level
- the same item becomes usable after the requirement is met
- production skill and use skill are independent
- Enchanting XP amplifier affects other skills but never Enchanting itself

## D. Compression

- normal -> Enchanted conversion consumes exactly configured units
- decompression returns exactly the configured underlying units
- repeated compress/decompress grants no skill XP
- normal and Enchanted forms remain independent Bazaar commodities

## E. Money

- balances use fixed-point integer storage
- decimal display/settlement remains exact
- balance can never become negative
- concurrent transactions cannot double-spend the same funds

## F. NPC salvage

- selling eligible items destroys the submitted items and pays exact configured salvage output
- splitting a sale into many small transactions never creates a rounding advantage
- compressed/uncompressed conversion cannot create NPC arbitrage profit

## G. Bazaar

Test:

- buy order placement/reservation
- sell order placement/escrow
- instant buy/sell
- partial fills
- price/time priority
- offline settlement
- cancel/fill races
- reconnect/restart during settlement

No item or Coin may settle twice.

## H. Auction House

Test non-stackable item listing, fixed-price purchase, cancellation, timeout/expiry if enabled, and restart/crash during settlement. Unique item ownership must remain singular.

## I. Direct trade

Two players exchange items and Coins. Both confirm final state. Disconnect/crash during confirmation/settlement must resolve atomically without duplication or partial ownership.

## J. Enchanting and XP economy

- Lapis-backed XP-bottle recipe consumes exact inputs
- bottle is tradable according to native stack behavior
- using bottle grants normal Minecraft XP
- normal enchanting consumes normal Minecraft XP/Lapis as configured
- baseline NPC books are available at configured fixed Coin prices
- custom/rare books excluded from NPC catalog cannot be minted through the baseline path
- tier combination cannot bypass an explicitly restricted rare-tier boundary

A peaceful player must be able to obtain XP/enchanting capability entirely by producing/trading economic goods rather than killing mobs.

## K. Brewing/Nether

- required potion ingredients have legitimate V1 sources
- player may personally gather or buy all inputs
- Glowstone/Quartz and other configured resources route through correct skill/XP-source rules
- Brewing Stand behavior produces only allowed V1 potion outputs

## L. Clans and ranked PvP

- clan create/invite/leave/kick/roles/chat persist
- ranked 1v1 gives both players standardized temporary combat state regardless of permanent progression
- temporary ranked inventory/state is discarded/restored correctly afterward

## M. Clan war settlement

- real source items lock before battle
- WAR receives battle state without taking unrestricted source ownership
- durability/consumable use records settle exactly once
- killing WAR during battle or settlement cannot duplicate gear or consume settlement twice

## N. Community projects/history

Complete one real project through contribution/build/review/completion.

Verify:

- contributions are attributable
- final region snapshot exports successfully
- metadata/checksum are stored
- project version is immutable
- qualifying blueprint entitlement is recorded
- historical reward issuance is idempotent

Attempt reward issuance repeatedly; exactly one authentic reward may exist per entitlement.

## O. Backup/restore

Back up PostgreSQL, permanent City world, project schematics/metadata, resource-pack/content files, and configuration. Restore into a clean environment and repeat critical state/economy checks.

## End-to-end V1 journey

A new player must be able to:

`join City -> gather -> gain skill XP -> return -> trade -> refine/craft or buy -> use skill-gated equipment when eligible -> obtain normal Minecraft XP -> enchant -> brew/buy potions -> join clan -> ranked PvP -> opt-in clan war -> contribute to community project -> receive historical entitlement -> transfer servers -> logout -> restart network -> reconnect`

All durable state must remain correct.
