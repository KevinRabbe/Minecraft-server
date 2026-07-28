# Authority Model

Every piece of state must have one explicit authority. Ambiguous authority is a duplication/corruption risk.

## State classes

### Persistent player state
Follows the player across zones/backends:

- identity
- persistent inventory/equipment representation
- spendable/pocket Coin balance
- protected bank state where player-owned
- skills/XP and staged-cap progression
- bounty-family progression/contracts tied to the player
- clan membership/role
- PvP rating/history references
- unique-item ownership/provenance
- durable logical location

Durable authority: PostgreSQL. One active Paper backend may operate on loaded live state while holding the exclusive ownership lease.

### Global persistent state
Network-shared state:

- Bazaar/AH
- secure trades/commissions
- bank tier/config references and interest-period evidence where global
- clans/treasuries/shared storage
- PvE Map run/clear source records
- leaderboards source data/read-model watermarks
- expansion votes/ballots
- feature states/world eras
- explicit community projects
- historical events/entitlements
- rating/war history

Durable authority: PostgreSQL.

### Persistent world state
Physical geography whose block state matters:

- canonical City/starter geography
- player-built ordinary district structures
- protected/archived explicit project builds where used
- future persistent player property if introduced

Authority: persistent world storage plus database metadata where needed. Backups must cover persistent world + database state coherently.

Important: physical ordinary district form is **not** authoritative proof that a hidden canonical blueprint/minimum size was completed. Feature/vote state lives separately.

### Instance runtime state
Disposable live state:

- mobs
- resource respawn timers
- temporary drops
- particles
- loaded chunks
- local encounter timers
- Map/Bounty boss entities and moment-to-moment combat state
- resettable terrain mutations

Authority: the active zone/encounter instance for moment-to-moment runtime only. This state may vanish when the instance is destroyed unless an outcome is promoted/committed through persistent authority.

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
| Pocket Coin balance | PostgreSQL transaction |
| Protected bank balance/interest evidence | PostgreSQL transaction |
| Skills/XP/bounty progression | loaded single-writer state and/or transactional PostgreSQL state according to subsystem contract |
| Active skill cap / feature progression cap | global feature/config state |
| Persistent inventory/equipment representation | loaded single-writer state + committed PostgreSQL state |
| Unique item identity/ownership/roll quality | PostgreSQL |
| Commodity custody/quantity | authoritative inventory/container/economy state backed by PostgreSQL contract |
| Bazaar/AH/trade/commission | PostgreSQL transaction |
| Clan treasury/shared-storage custody | PostgreSQL transaction/custody |
| Map item identity | PostgreSQL unique-item authority |
| Map run lifecycle/completion settlement | PostgreSQL source record + live instance runtime for moment-to-moment combat |
| Bounty contract/summon/reward state | PostgreSQL source state + live encounter runtime for combat |
| Expansion vote/ballots/result | PostgreSQL |
| Feature accessibility/world era | PostgreSQL |
| Explicit Community Project state/history | PostgreSQL |
| Chronicle/historical source records | PostgreSQL/reference to authoritative source records |
| Leaderboards | derived read model from persistent authoritative source state |
| City/district physical build | persistent world storage + metadata where needed |
| Resettable zone mobs/resources | live zone instance |
| Ranked-PvP temporary loadout | PvP instance only |
| War custody/settlement | PostgreSQL authority; match runtime consumes isolated representation |
| Backend/instance placement | control plane |

## Single-writer rule

Exactly one backend may mutate a player's persistent live state at a time.

A player cannot legitimately be owned by two Paper backends concurrently. Cross-backend movement explicitly freezes, commits, releases, and claims ownership.

Subsystems that mutate globally authoritative transactional state (markets, bank, Map opening, bounty contract/summon settlement, voting, war settlement) must not bypass their PostgreSQL operation/locking/idempotency contracts merely because one Paper backend currently owns the player's live state.

## Persistent location rule

Persistent player state stores logical gameplay location such as `zone_id` and optional named entry point. It must not require an old disposable `instance_id` or backend ID to exist.

If a saved zone is unavailable/locked, routing falls back to a safe location such as the City.

## Map authority rule

A persistent Map item and a live Map run are separate authorities:

- Map item ownership/custody is persistent;
- opening the Map atomically establishes at most one persistent run identity;
- live mobs/objective timers are disposable runtime;
- completion/failure/reward/history are committed through persistent run/operation state.

A surviving entity or client message cannot prove a Map completion after persistent state says otherwise.

## Bounty authority rule

A bounty contract/summon authorization is persistent player/global workflow state.

Eligible mob/boss entities are runtime representations. They may advance/complete the persistent workflow only through validated server-observed events and idempotent operations.

## Voting authority rule

Ballots/results/feature transitions are PostgreSQL-authoritative. Discord messages, GUI counts, scoreboards, signs, physical buildings, or admin preference are representations/context only.

Ordinary district physical construction does not become a hidden feature-unlock authority unless a separately explicit Community Project contract says so.

## Representation versus authority

Minecraft ItemStacks, scoreboards, NPC displays, world blocks, GUIs, boss bars, portal entities, vote displays, and leaderboards are representations. They do not become durable authority merely because the player can see/interact with them.

When a representation conflicts with authoritative persistent state, persistent authority wins and the representation is rejected/quarantined/rebuilt.

## Staff rule

Staff tooling cannot bypass authority invariants. Recovery/admin actions that create/move economic value, alter vote/feature state, grant historical authenticity, or settle PvE/war outcomes must use audited controlled operations rather than raw `/give`, invisible SQL edits, or manual scoreboard changes.