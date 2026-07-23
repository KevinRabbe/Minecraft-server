# Terminology

Use these terms consistently across code and documentation.

## World/gameplay topology

### Zone
A logical gameplay place with stable identity and rules, for example `STARTER_WOODS`, `STARTER_MINE`, `CITY`, or `WOLF_RUINS`. A zone defines the gameplay contract, not where it is hosted.

### Zone instance
One live copy of a zone. Multiple instances of the same zone have equivalent gameplay rules and exist only to keep concurrent player counts manageable.

### Backend
A Paper server process capable of hosting one or more zone instances. Backend identity is operational infrastructure and should not leak into persistent progression.

### Template
Versioned world/map content used to create resettable or temporary zone instances.

### Persistent zone
A zone whose physical world state matters and is backed up, such as the canonical City/community-construction space.

### Resettable zone
A renewable activity zone whose runtime terrain/entities may be recreated from a template.

### Match-temporary zone
An isolated competitive/instance context such as ranked PvP or a clan war.

## State and authority

### Persistent player state
Network-owned state that follows the player across zones/backends: inventory/equipment, wallet, skills, clan membership, ratings, progression, unique-item ownership, and similar state.

### Global state
Persistent state shared by the network rather than owned by one player, such as markets, clans, leaderboards, project progress, feature unlocks, and historical records.

### Persistent world state
Physical state tied to durable geography, such as community-built structures in the City.

### Instance runtime state
Disposable local state: mobs, tree respawn timers, temporary drops, loaded chunks, particles, and other moment-to-moment state.

### Ownership lease
The temporary exclusive right of one backend to mutate a player's live persistent runtime state.

### State version
Monotonically increasing version of committed player state used to reject stale writes and safe transfer/recovery.

## Items and value

### Item definition
Stable type definition: ID, Minecraft material/model, stackability, category, base stats, requirements, recipe references, and other type-level rules.

### Item instance
Stable identity for an individual non-fungible item when individuality/provenance matters.

### Commodity
Fungible stackable value represented as `definition_id + quantity`, not one database row per unit.

### Escrow
Authoritative temporary custody that removes value from normal player control while a transaction/workflow is unresolved.

### Pending delivery
Durable destination for already-owned value that cannot yet be placed safely into a player's physical Minecraft inventory.

### Provenance
Persistent origin/history metadata proving where a unique or historical item came from.

## Product/game design

### Bootstrap supplier
Expensive guaranteed NPC source that makes an early system usable before natural/player supply is unlocked. It is not intended to dominate the long-term economy.

### Community project
Persistent global contribution/build lifecycle that can unlock features and create attributable server history.

### Feature state
Logical accessibility of a feature (for example locked vs available), separate from whether infrastructure for it is currently active.
