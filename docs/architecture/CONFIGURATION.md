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
- invariants such as non-negative currency and single-writer ownership
- validation rules required for correctness

## Balance/content configuration

Examples:

- XP curves
- Enchanting XP-amplifier curve
- gathering speed/luck curves
- authorized resource-source definitions
- respawn/reset timers
- tool/use/craft requirements
- multi-block limits
- pouch tiers/capacities
- recipes and processing durations
- compression ratios
- NPC salvage values
- Witch/bootstrap prices/allowlist
- potion allowlist
- clan cap
- PvP rating constants
- war costs/rewards
- zone soft/hard capacities
- instance idle timeouts
- project contribution requirements
- item definitions/content references

## Content definitions

Use stable IDs and boring version-controlled structured data. JSON/YAML/TOML or another simple format is sufficient.

Do not build a custom scripting language, visual content editor, or arbitrary hot-reload engine until real workflow demands it.

## Startup validation

Fail early on invalid content/configuration.

Validate at minimum where applicable:

- IDs are unique
- referenced items/skills/zones/features/enchantments exist
- quantities/prices/capacities are valid
- impossible stack sizes are rejected
- recipes cannot trivially produce configured server arbitrage loops
- requirements use valid skills/levels
- market/compression definitions are internally consistent
- project completion actions reference known features/actions

## Versioning

Persistent state must remain interpretable across content changes.

Use stable definition IDs. Display names/models/balance numbers may change without changing identity.

Zone templates and important content/resource-pack releases should carry versions where needed for updates, archival, and recovery.

## Secrets

Passwords/API secrets/private keys do not belong in committed balance/content files. Use environment/deployment secret mechanisms.

## Environment-specific configuration

Operational values such as ports, database connection, backend ID, and local paths are environment/deployment configuration, not game-design content.

## Hot reload

Only implement hot reload for configuration categories where it is demonstrably safe. Correctness/state-machine changes should normally require controlled restart/migration rather than being mutated underneath live transactions.
