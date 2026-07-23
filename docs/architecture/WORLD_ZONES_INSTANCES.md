# World, Zones, Instances, and Backends

## Locked world philosophy

The game does not depend on a huge open Minecraft world. It uses **small, dense, purpose-built gameplay zones** with deliberate boundaries.

The first persistent world is centered on the City/Town and contains only enough surrounding geography to support the first meaningful activities. The visual/content design is original; the structural pattern is compact zones plus horizontal instancing.

## Zone

A zone is a stable gameplay definition.

Conceptual fields:

- `zone_id`
- template/map reference and template version
- persistence mode
- feature/access requirements
- soft capacity
- hard capacity
- minimum warm instances
- maximum instances (if bounded)
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

## Backend

A backend is a Paper process that can host one or more zone instances.

Backend identity is infrastructure only. Persistent gameplay must not store `paper-07` as a meaningful player destination.

## Instance scaling

**Instance count is driven by concurrent players in that zone only.**

Players elsewhere on the network do not create instances for a zone they are not using.

Each zone has independently tuned density limits. Social spaces can tolerate/benefit from much higher density than contested resource/combat spaces.

Routing should prefer the smallest number of healthy instances that preserves good gameplay. Do not spread ten players over ten copies unnecessarily.

A soft capacity represents the desired gameplay density. A hard capacity is the temporary ceiling. Exact numbers are configuration.

## Lifecycle

Minimum lifecycle:

```text
STARTING -> ACTIVE -> DRAINING -> STOPPED
                    \-> FAILED
```

- `STARTING`: loading/preparing; no normal player admission yet.
- `ACTIVE`: accepts routing.
- `DRAINING`: receives no new players; existing players leave/transfer.
- `STOPPED`: no longer live.
- `FAILED`: runtime instance/backend failure requiring recovery/reroute.

An empty resettable instance may remain idle briefly before retirement to avoid churn. Exact timeout is configuration.

## Persistence modes

### Persistent
Physical world state matters and is backed up. Main example: canonical City/community-construction space.

### Resettable
Renewable activity state may be recreated from a canonical template. Examples: gathering and ordinary persistent-PvE zones.

### Match temporary
Isolated short-lived competitive context. Examples: ranked PvP and clan war.

## Compact starter region

The launch persistent region should be intentionally small. It may contain:

- City/Town
- tiny first Woodcutting pocket
- tiny first Farm pocket
- first Mine entrance/area
- compact early combat pocket(s)
- Witch/Apothecary district
- community-project construction region(s)

Do not make forests/farms/mines realistic-sized merely for visual scale. An area is only as large as its mechanic needs.

## Travel

The player requests/enters a logical zone; routing resolves a suitable live instance.

Transitions may be presented as:

- walking through a boundary/gate/path
- elevator
- carriage
- portal
- ship
- NPC/travel interaction

The presentation may look geographically continuous even if the destination runs in another world/backend.

Persistent player location should therefore store logical zone/entry information rather than rely on a disposable instance forever.

## Friends/parties

Routing may prefer a party/friend's existing suitable instance before normal packing. This is secondary to correctness and can remain simple in V1.

## City exception

City benefits from player density and visible prestige/economy/community activity, so it should use a much higher capacity than resource zones and remain at least one persistent instance.

If City eventually needs multiple copies, global civic state (project progress, leaderboards, markets) remains one network state. Physical synchronization/canonical build behavior must be designed deliberately rather than assumed.

## Template versioning

Resettable/temporary zones use versioned templates. New instances use the current template. Old instances can drain naturally during updates rather than being silently mutated underneath active players.

## Core rule

**Scale concurrency by replication, not geography.**
