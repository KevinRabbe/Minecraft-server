# Authority Model

Every piece of state must have one explicit authority. Ambiguous authority is a duplication/corruption risk.

## State classes

### Persistent player state
Follows the player across zones/backends:

- identity
- inventory/equipment
- wallet
- skills/XP
- progression/artifacts
- clan membership/role
- PvP rating/history
- unique-item ownership/provenance
- durable logical location

Durable authority: PostgreSQL. One active Paper backend may operate on the loaded live state while holding the exclusive ownership lease.

### Global persistent state
Network-shared state:

- Bazaar/AH
- secure trades/commissions
- clans
- leaderboards source data
- community projects
- feature unlocks
- historical entitlements
- rating/war history

Durable authority: PostgreSQL.

### Persistent world state
Physical geography whose block state matters:

- canonical City/community construction
- protected completed community builds
- future persistent player property if introduced

Authority: persistent world storage plus database metadata where needed. Backups must cover both coherently.

### Instance runtime state
Disposable live state:

- mobs
- resource respawn timers
- temporary drops
- particles
- loaded chunks
- local encounter timers
- resettable terrain mutations

Authority: the active zone instance. This state may vanish when the instance is destroyed unless explicitly promoted to persistent state.

### Control-plane state
Operational placement:

- backend health
- instance registry
- instance lifecycle
- current player counts
- zone-to-instance routing

Authority: control-plane runtime/registry. Durable persistence is optional unless recovery genuinely needs it.

## Authority table

| State | Authority |
|---|---|
| Player identity | PostgreSQL |
| Session ownership/state version | PostgreSQL transaction/lease |
| Wallet | PostgreSQL transaction |
| Skills/XP | loaded single-writer state + committed PostgreSQL state |
| Persistent inventory/equipment | loaded single-writer state + committed PostgreSQL state |
| Unique item identity/ownership | PostgreSQL |
| Bazaar/AH/trade/commission | PostgreSQL transaction |
| Clan/project/rating/history | PostgreSQL |
| Leaderboards | derived read model from persistent source state |
| City physical build | persistent world storage + metadata |
| Resettable zone mobs/resources | live zone instance |
| Ranked-PvP temporary loadout | PvP instance only |
| War custody/settlement | PostgreSQL authority; match runtime consumes isolated representation |
| Backend/instance placement | control plane |

## Single-writer rule

Exactly one backend may mutate a player's persistent live state at a time.

A player cannot legitimately be owned by two Paper backends concurrently. Cross-backend movement explicitly freezes, commits, releases, and claims ownership.

## Persistent location rule

Persistent player state stores logical gameplay location such as `zone_id` and optional named entry point. It must not require an old disposable `instance_id` or backend ID to exist.

If a saved zone is unavailable/locked, routing falls back to a safe location such as the City.

## Representation versus authority

Minecraft ItemStacks, scoreboards, NPC displays, world blocks, and GUIs are representations. They do not become durable authority merely because the player can see/interact with them.

When a representation conflicts with authoritative persistent ownership, the authoritative persistent state wins and the conflicting representation is rejected/quarantined/rebuilt.

## Staff rule

Staff tooling cannot bypass authority invariants. Recovery/admin actions that create or move economic value must use audited controlled operations rather than raw `/give`-style authenticity.
