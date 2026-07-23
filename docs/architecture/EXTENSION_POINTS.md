# Extension Points

The architecture should let future content reuse existing mechanisms rather than create parallel foundations.

## New gameplay zones

A future zone should primarily add:

- stable `zone_id`
- template/version
- access/feature requirement
- persistence mode
- capacity policy
- resource/mob/build rules
- entry/exit definitions

It should reuse routing, instance lifecycle, player-state transfer, and analytics.

Examples:

- advanced Woodcutting regions
- deeper Mining regions
- later Nether regions
- Fishing regions
- dungeon entrances/instances

## Future features

A feature can exist in code/content while remaining inaccessible.

Use feature state plus generic project completion actions rather than tying availability to whether a backend process happens to be running.

Typical flow:

`implemented -> locked -> community project/content event -> available -> zone instances activated on demand`

## Fishing

Fishing later should reuse:

- skill framework
- zone/instance routing
- item/resource definitions
- authorized source validation
- pouches if needed
- Bazaar/AH rules
- leaderboards
- community-project feature unlock (for example Harbour)

Do not create a separate economy/progression framework for Fishing.

## Nether

Nether reuses:

- feature unlock/project lifecycle
- compact zone templates and horizontal instancing
- Mining/Combat/Farming benefit/XP-source separation where relevant
- Bazaar commodity rules
- Witch bootstrap fallback

## Dungeons

Simple early PvE zones do not require a dungeon framework.

When true dungeons are introduced, they may add party/objective/boss/loot/match lifecycle while reusing:

- logical zone routing
- temporary/isolated instance lifecycle
- persistent player state
- item definitions/provenance
- transactional reward issuance
- analytics

## Museum

A future Museum should reuse:

- provenance/item identity
- historical entitlement/history
- project unlock/build archive
- persistent City/community systems

Do not prebuild an empty Museum system/building in V1.

## New item content

Prefer adding item definitions/recipes/enchantments inside stable categories over inventing new top-level systems.

## New markets

Do not create a new market type simply because a new content category appears. Default remains stackability -> Bazaar/AH. Add a new market mechanism only when its transaction semantics genuinely differ.

## New backends/machines

Scaling out should not change gameplay identities.

A new Paper backend registers capacity and hosts zone instances; persistent players still request the same logical zones.

## New infrastructure services

Only extract a dedicated scheduler/cache/message broker/service when measured load/availability/dependency boundaries demonstrate that the current in-process/control-plane implementation is inadequate.

## Schema evolution

Use migrations and stable IDs. Do not encode display names, backend names, or storage paths as long-lived identity.

## Extension rule

**New content should mostly compose existing systems. A new foundational primitive requires a demonstrated problem that existing primitives cannot solve cleanly.**
