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
- later Nether/End regions
- Fishing regions
- future dungeon entrances/instances

## Future features / expansion candidates

A feature can exist in code/content while remaining inaccessible.

Use persistent feature state plus the player-directed expansion-voting contract rather than tying availability to whether a backend process happens to be running.

Typical ordinary expansion flow:

`implemented/registered -> locked candidate -> player vote resolves -> feature available -> runtime zones activated on demand -> players physically build whatever district they choose`

Do not insert a hidden Community Project/material/build threshold unless that candidate is explicitly designed to use one.

## New expansion candidates

A new candidate should primarily provide:

- stable candidate ID
- player-facing capability/theme description
- referenced feature/content actions
- eligibility/dependency metadata where genuinely required
- optional world-era transition flag if it materially changes the power ecosystem
- optional explicit Community Project reference only when intentionally used

It must not require a canonical ordinary-district blueprint/minimum build size.

## New Map content

New Map content should reuse `PVE_MAPS_AND_BOUNTIES.md`.

### Environment
Add stable environment definition/template/generation profile and compatibility metadata.

### Enemy family
Add stable family ID, spawn/behavior pools, elite compatibility, reward/category tags.

### Objective
Implement/register the shared objective lifecycle rather than special-casing the Map engine.

### Modifier / elite trait
Use explicit composable extension points and compatibility validation.

### New difficulty/reward tuning
Change configuration/versioned curves; do not create another Map progression system.

## New bounty families/tiers

A new bounty family should reuse:

- generic contract lifecycle
- Coin fee transaction
- eligible-category kill tracking
- summon authorization
- boss-attempt/result settlement
- fungible Bazaar material model
- family pouch model
- specialized gear/recipe integration

Adding Vampire/Enderman/etc. later should be content/configuration plus encounter behavior, not a new economy/ownership architecture.

## Fishing

Fishing should reuse:

- skill framework/staged caps
- zone/instance routing
- item/resource definitions
- authorized source validation
- pouches where needed
- Bazaar/AH rules
- expansion voting/feature state
- player-created district model
- leaderboards if the content benefits from them

Do not create a separate economy/progression framework for Fishing.

## Nether / End

Major vertical-power milestones reuse:

- expansion voting/feature state/world-era transition
- optional explicit Community Project only if separately designed
- compact zone templates and horizontal instancing
- existing skills/benefit/XP-source separation where relevant
- Bazaar/AH/crafting/rolled gear rules
- Map/Bounty integration
- pre-unlock bootstrap sources where required to keep older systems functional

They do not create a new Map-difficulty permission system.

## Dungeons

Maps + Bounties are the launch PvE backbone.

When true Dungeons are introduced later, reuse:

- logical zone routing
- temporary/isolated instance lifecycle
- participant/party contracts
- persistent player state
- item definitions/provenance/rolled gear
- transactional reward issuance
- Map/Bounty materials/gear ecosystem where useful
- historical clear/analytics patterns

A Dungeon may add handcrafted encounter sequencing/mechanics, not a parallel wallet/market/progression authority.

## Museum / future historical systems

A future Museum should reuse:

- provenance/item identity
- historical entitlement/Chronicle source records
- expansion voting/feature state
- project/archive infrastructure where useful
- persistent City/player-built district systems

Do not prebuild an empty Museum system/building merely because it may be a future candidate.

## New item content

Prefer adding item definitions/recipes/roll profiles/enchantments inside stable categories over inventing new top-level item systems.

New rolled equipment must use the same persistent normalized roll-quality and Auction House custody model.

## New markets

Do not create a new market type simply because a new content category appears.

Default remains:

- fungible/stackable -> Bazaar
- individualized/non-fungible -> Auction House
- direct bilateral exchange -> secure trade

Add a new market mechanism only when its transaction semantics genuinely differ.

## New clan/social content

Reuse clan identity/roles/treasury/storage permissions and ordinary player voting rules. Do not give a new clan feature hidden economic ownership or weighted civic votes without a separately locked design.

## New backends/machines

Scaling out should not change gameplay identities.

A new Paper backend registers capacity and hosts zone/encounter instances; persistent players still request the same logical activities/places.

## New infrastructure services

Only extract a dedicated scheduler/cache/message broker/service when measured load/availability/dependency boundaries demonstrate that the current in-process/control-plane implementation is inadequate.

## Schema evolution

Use migrations and stable IDs. Do not encode display names, backend names, item prices, physical paths, or district appearance as long-lived identity.

## Extension rule

**New content should mostly compose existing systems. A new foundational primitive requires a demonstrated problem that existing primitives cannot solve cleanly.**