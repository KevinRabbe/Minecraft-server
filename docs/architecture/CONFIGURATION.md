# Configuration and Content Data

## Rule

**Code implements mechanics. Data implements balance/content.**

Do not hardcode tuning values into architecture when they should change through playtesting.

## Code/correctness constants

Keep these stable in code/domain contracts:

- state-machine semantics
- transaction/idempotency rules
- authority rules
- permission/capability identifiers
- stable skill/zone/feature/category IDs
- asset identity/custody rules
- vote uniqueness/resolution semantics
- Map/Bounty lifecycle semantics
- invariants such as non-negative currency, one Map -> at most one run, one summon authorization -> configured number of boss attempts, and single-writer ownership
- validation rules required for correctness

## Balance/content configuration

Examples:

### Progression
- XP curves
- staged active skill cap/world-era mapping (launch 50, later 75, much later 100 is locked; exact timing is content)
- Enchanting XP-amplifier curve
- gathering speed/luck/yield curves
- use/recipe requirements
- bounty progression thresholds

### Economy
- Bank Manager capacities/upgrade costs
- bank interest rates
- PvE pocket death-loss curve
- Bazaar/AH/direct-trade fees
- NPC salvage values
- Witch/bootstrap prices/allowlist
- recipes and crafting/refining costs/durations
- compression ratios

### Rolled gear
- item roll property definitions
- low/high value ranges within the locked broad 10–30% relevant-value envelope unless explicitly redesigned
- roll distributions
- upgrade costs/stat curves
- salvage outputs

### World/resources
- authorized resource-source definitions
- respawn/reset timers
- multi-block limits
- gathering/bounty pouch tiers/capacities
- zone soft/hard capacities
- instance idle timeouts

### Maps
- supported visible/system difficulty range
- health/damage/defense/reward scaling curves
- Map-drop progression variance
- environment definitions
- enemy-family definitions
- objective definitions
- modifier definitions/compatibility
- elite traits/weights
- reward tables/material tiers
- party/time/failure parameters

### Bounties
- family definitions
- tier definitions
- contract fees
- eligible mob categories
- kill requirements
- summon/boss definitions
- reward/material tables
- pouch progression
- family-specific gear/recipe references

### Social/competition/world
- clan cap
- PvP rating constants
- war costs/rewards/team size
- expansion candidate sets
- vote timing/eligibility policy where configurable
- feature actions associated with candidates
- world-era boundaries for major power changes
- explicit Community Project requirements only for projects that intentionally use them

## Content definitions

Use stable IDs and boring version-controlled structured data. JSON/YAML/TOML or another simple format is sufficient.

Do not build a custom scripting language, visual content editor, or arbitrary hot-reload engine until real workflow demands it.

Content families should be independently loadable/validatable enough that adding a new Map environment, modifier, bounty family/tier, item, recipe, or expansion candidate normally does not require new authority code.

## Startup validation

Fail early on invalid content/configuration.

Validate at minimum where applicable:

- IDs are unique;
- referenced items/skills/zones/features/enchantments exist;
- quantities/prices/capacities/rates are valid and bounded;
- impossible stack sizes are rejected;
- recipes/NPC prices/bank rates/compression/salvage cannot trivially produce deterministic server arbitrage loops;
- requirements use valid skills/levels and do not exceed the supported progression model unintentionally;
- staged cap definitions cannot expose 75/100 early by accident;
- roll profiles are valid, bounded, and reference supported stat properties;
- Map difficulty/scaling arithmetic is safe across supported range;
- Map environments/enemy families/objectives/modifiers/elites reference valid IDs;
- invalid modifier combinations are rejected;
- bounty families/tiers/materials/pouches reference valid IDs;
- configured bounty materials intended for Bazaar are fungible/market-compatible;
- vote candidate sets contain valid unique candidate IDs and feature actions;
- ordinary district candidate definitions do not sneak in canonical blueprint/minimum-block requirements;
- explicit project completion actions reference known features/actions.

## Versioning

Persistent state must remain interpretable across content changes.

Use stable definition IDs. Display names/models/balance numbers may change without changing identity.

Version context is especially important for:

- item definition/roll interpretation;
- Map generation and combat/balance context;
- bounty family/tier encounter definitions;
- expansion candidate sets;
- world-era transitions;
- persistent zone templates;
- important content/resource-pack releases;
- archival/recovery.

A balance update must not silently reroll existing individualized items or mutate already-started run semantics underneath players.

## World-era configuration

World era is an authoritative persistent state transition, not merely a config file value.

Configuration may define which feature transitions qualify as a new era, but enabling that era occurs through an explicit authoritative feature/vote/project action.

## Secrets

Passwords/API secrets/private keys do not belong in committed balance/content files. Use environment/deployment secret mechanisms.

## Environment-specific configuration

Operational values such as ports, database connection, backend ID, local paths, and machine/process placement are environment/deployment configuration, not game-design content.

## Hot reload

Only implement hot reload for categories where it is demonstrably safe.

Potentially safe with validation/version boundaries:

- some future reward/display values
- some read-only presentation data

Usually require controlled restart/new-version activation rather than mutating active workflows:

- transaction semantics
- item roll-profile interpretation
- Map generation/combat rules for active runs
- bounty lifecycle/tier semantics for active contracts
- vote candidate sets after a vote has opened
- feature/world-era transitions
- authority/permission rules

Correctness/state-machine changes require controlled migration/restart, not live mutation underneath active transactions.