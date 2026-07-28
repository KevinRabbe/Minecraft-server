# Terminology

Use these terms consistently across code and documentation.

## World/gameplay topology

### Zone
A logical gameplay place with stable identity and rules, for example `STARTER_WOODS`, `STARTER_MINE`, `CITY`, or a PvE activity zone. A zone defines the gameplay contract, not where it is hosted.

### Zone instance
One live copy of a zone. Multiple instances of the same zone have equivalent gameplay rules and exist only to keep concurrent player counts manageable.

### Backend
A Paper server process capable of hosting one or more zone instances. Backend identity is operational infrastructure and should not leak into persistent progression.

### Template
Versioned world/map content used to create resettable or temporary zone instances.

### Persistent zone
A zone whose physical world state matters and is backed up, such as the canonical City/community-building space.

### Resettable zone
A renewable activity zone whose runtime terrain/entities may be recreated from a template.

### Match-temporary zone
An isolated competitive/instance context such as ranked PvP or a clan war.

### Portal run / Map run
One isolated execution of a persistent Map item/configuration. The run is temporary gameplay state; its source Map identity, completion result, rewards, and historical clear record are persistent where applicable.

## State and authority

### Persistent player state
Network-owned state that follows the player across zones/backends: inventory/equipment, Coin pocket/bank, skills, bounty progression, clan membership, ratings, progression, unique-item ownership, and similar state.

### Global state
Persistent state shared by the network rather than owned by one player, such as markets, clans, leaderboards, expansion votes, feature state, world eras, projects, and historical records.

### Persistent world state
Physical state tied to durable geography, such as player-built structures in the canonical City/district space.

### Instance runtime state
Disposable local state: mobs, resource respawn timers, temporary drops, loaded chunks, particles, encounter timers, and other moment-to-moment state.

### Ownership lease
The temporary exclusive right of one backend to mutate a player's live persistent runtime state.

### State version
Monotonically increasing version of committed state used to reject stale writes and support safe transfer/recovery.

### World era
A historically meaningful period defined by major changes to the available power/content ecosystem, such as the pre-Nether era versus a later Nether-power era. It is context for records/history, not a seasonal wipe.

## Items and value

### Item definition
Stable type definition: ID, Minecraft material/model, stackability, category, base stats, requirements, recipe references, roll profile, and other type-level rules.

### Item instance
Stable identity for an individual non-fungible item when individuality/provenance matters.

### Commodity
Fungible stackable value represented as `definition_id + quantity`, not one database row per unit.

### Roll quality
Persistent normalized intrinsic quality of an individualized item's rolled property. Current absolute stats may change with balance definitions; the item's historical relative roll quality does not reroll.

### Upgrade state
Player investment applied to an item after creation. It is separate from intrinsic roll quality.

### Escrow
Authoritative temporary custody that removes value from normal player control while a transaction/workflow is unresolved.

### Pending delivery
Durable destination for already-owned value that cannot yet be placed safely into a player's physical Minecraft inventory.

### Provenance
Persistent origin/history metadata proving where a unique or historical item came from.

### Pocket / spendable Coin balance
Coin value immediately usable by the player and subject to configured ordinary PvE death-loss rules.

### Protected bank balance
Coin value deposited with the Bank Manager. It is protected from ordinary PvE pocket-loss rules and subject to configured capacity/interest semantics.

## PvE

### Map item
An individualized tradable PvE challenge definition containing difficulty plus configured environment/enemy-family/objective/modifier/generation properties. Opening the exact Map creates at most one Map run.

### Map difficulty
Numeric encounter-strength measurement. It is not a character/skill-level permission gate and may extend beyond the currently achievable gear ceiling.

### Bounty family
A mob-category progression/economy family such as Spider, Zombie, or Golem. It groups bounty tiers, eligible mob kills, summon access, materials, pouches, and category-specialized equipment.

### Bounty tier
A difficulty/progression step inside a bounty family. Higher tiers may require more/harder category kills and can introduce higher-grade family materials.

### Bounty contract
The paid bounty quest/state that requires eligible category kills before summon access. The fee unlocks the contract; it does not directly purchase the boss spawn.

### Bounty pouch
Specialized storage for fungible materials from one bounty family. Pouch custody is QoL and does not change Bazaar tradability.

## Product/game design

### Bootstrap supplier
Expensive guaranteed NPC source that makes an early system usable before natural/player supply is unlocked. It is not intended to dominate the long-term economy.

### Community project
An explicitly defined persistent contribution/build lifecycle that can unlock features and create attributable server history. It is **not** automatically the lifecycle for every voted district and must not imply a hidden canonical blueprint or minimum build size.

### Expansion vote
Authoritative player decision selecting which valid capability/theme becomes available next. Developers define the candidate/rule set but do not steer which valid option should win.

### District
The player-created physical/social expression around an unlocked capability/theme. Ordinary districts have no developer-authored required blueprint, appearance, or minimum block count.

### Feature state
Logical accessibility of a feature (for example locked vs available), separate from whether infrastructure for it is currently active.