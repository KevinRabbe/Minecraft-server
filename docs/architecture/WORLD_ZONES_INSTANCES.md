# World, Zones, Instances, and Backends

## Locked world philosophy

The game does not depend on a huge open Minecraft world. It uses **small, dense, purpose-built gameplay zones** with deliberate boundaries.

The first persistent world is centered on the City/Town and contains only enough surrounding geography to support the first meaningful activities. The visual/content design is original; the structural pattern is compact zones plus horizontal instancing.

Ordinary player-built districts are persistent-world outcomes around unlocked capabilities, not developer-authored canonical blueprints.

## Zone

A zone is a stable gameplay definition.

Conceptual fields:

- `zone_id`
- template/map reference and template version where applicable
- persistence mode
- feature/access requirements
- soft capacity
- hard capacity
- minimum warm instances
- maximum instances if bounded
- idle retirement policy
- spawn/entry points
- exits/travel destinations
- build policy
- resource-source policy
- mob/spawn policy
- reset/respawn rules

Examples are structural placeholders such as `CITY`, `STARTER_WOODS`, `STARTER_MINE`, `STARTER_FARM`, `EARLY_PVE_A`.

## Zone instance

A zone instance is one live copy of a zone.

Conceptual runtime fields:

- `instance_id`
- `zone_id`
- `template_version`
- `backend_id`
- lifecycle state
- current player count
- health/heartbeat
- started/last-active timestamps

Instance identity has no progression meaning. `STARTER_WOODS` instance 2 is not a harder or better forest than instance 1.

## Encounter/run instance

Portal/Map runs, Bounty boss encounters, ranked PvP, and clan wars may use short-lived encounter instances derived from a logical activity definition rather than a long-lived resource zone.

An encounter instance may carry runtime references such as:

- persistent `run_id` / `attempt_id` / match ID
- generated/template version
- participant set
- objective/boss runtime state

The persistent run/contract/match record remains separate. Destroying the live encounter instance must not erase committed economic/history state.

## Backend

A backend is a Paper process that can host one or more zone/encounter instances.

Backend identity is infrastructure only. Persistent gameplay must not store `paper-07` as a meaningful player destination.

## Instance scaling

**Instance count is driven by concurrent players in that activity/zone only.**

Players elsewhere on the network do not create instances for a zone/activity they are not using.

Each zone/activity has independently tuned density limits. Social spaces can tolerate/benefit from much higher density than contested resource/combat spaces.

Routing should prefer the smallest number of healthy instances that preserves good gameplay. Do not spread ten players over ten copies unnecessarily.

A soft capacity represents desired gameplay density. A hard capacity is the temporary ceiling. Exact numbers are configuration.

## Lifecycle

Minimum reusable runtime lifecycle:

```text
STARTING -> ACTIVE -> DRAINING -> STOPPED
                    \-> FAILED
```

- `STARTING`: loading/preparing; no normal player admission yet.
- `ACTIVE`: accepts routing/participants according to the activity contract.
- `DRAINING`: receives no new normal players; existing players leave/transfer/finish according to policy.
- `STOPPED`: no longer live.
- `FAILED`: runtime failure requiring recovery/reroute/abort.

An empty resettable instance may remain idle briefly before retirement to avoid churn. Exact timeout is configuration.

## Persistence modes

### Persistent
Physical world state matters and is backed up. Main example: canonical City/player-built district/community space.

### Resettable
Renewable activity state may be recreated from a canonical template. Examples: gathering and ordinary repeatable PvE zones.

### Encounter temporary
Isolated short-lived PvE runtime tied to a persistent workflow/run, such as a Portal/Map run or Bounty boss attempt.

### Match temporary
Isolated short-lived competitive context such as ranked PvP or clan war.

Both temporary modes are disposable runtime; they differ in gameplay/settlement semantics, not durability.

## Compact starter region

The Day-0 persistent region should be intentionally small. It may contain:

- City/Town
- tiny first Woodcutting/Foraging pocket
- tiny first Farm pocket
- first Mine entrance/area
- compact early combat pocket(s)
- Bazaar/AH/Bank/crafting/social/voting interaction surfaces
- Witch/Apothecary bootstrap presence where needed
- player building space and explicit Community Project regions only where such projects actually exist

Do not make forests/farms/mines realistic-sized merely for visual scale. An area is only as large as its mechanic needs.

## Player-built districts

When players unlock an ordinary expansion capability, they may physically create whatever district form they choose in allowed persistent-build space.

The zone/build system may enforce:

- where building is allowed
- protected server infrastructure
- ownership/claim/permission rules
- anti-grief/safety constraints

It must not enforce a hidden canonical district blueprint, appearance, or minimum block count unless a separately explicit Community Project says otherwise.

## Travel

The player requests/enters a logical zone/activity; routing resolves a suitable live instance.

Transitions may be presented as:

- walking through a boundary/gate/path
- elevator
- carriage
- portal
- ship
- NPC/travel interaction

The presentation may look geographically continuous even if the destination runs in another world/backend.

Persistent player location should therefore store logical zone/entry information rather than rely on a disposable instance forever.

## Portal/Map travel

Opening a Map creates/authorizes one persistent Map run first; routing/instance creation then hosts its live encounter.

The visible portal is representation. It is not the authority that the source Map was consumed or the run exists.

## Friends/parties

Routing may prefer a party/friend's existing suitable instance before normal packing. Map/match participant rules may override general party routing once an encounter starts.

## City exception

City benefits from player density and visible economy/community/history, so it should use a much higher capacity than resource zones and remain at least one persistent instance.

If City eventually needs multiple rendered copies, global state such as markets, ballots/results, feature state, leaderboards, clan data, and explicit project progress remains one network state.

Physical synchronization/canonical persistent geography must be designed deliberately rather than assuming independent copies are authoritative.

## Template/versioning

Resettable/temporary zones use versioned templates/generation definitions.

New instances use the active version. Old instances can drain naturally during updates rather than being silently mutated underneath active players.

Map runs additionally preserve the relevant run-definition/balance version needed for historical interpretation.

## Core rule

**Scale concurrency by replication, not geography; keep persistent authority separate from disposable runtime.**