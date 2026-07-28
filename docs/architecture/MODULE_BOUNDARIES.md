# Module Boundaries

The repository currently uses `common`, `paper`, and `velocity`. Architectural responsibilities should map cleanly onto those modules without forcing premature service decomposition.

## `common`

Shared domain contracts and infrastructure-neutral logic.

Expected responsibilities:

- stable IDs/enums/value types
- zone/feature/world-era definitions
- session/transfer contracts
- item definition/instance/roll-quality contracts
- skill/progression/staged-cap rules
- transaction/idempotency value types
- bank/wallet domain rules
- market/order domain rules
- crafting recipe/result contracts
- Map/run/difficulty/objective/modifier contracts
- bounty family/tier/contract/summon/pouch contracts
- clan/war/vote/project/history domain contracts
- configuration validation models
- domain-event definitions
- integrity-verification domain results where infrastructure-neutral

`common` must not depend on Paper or Velocity APIs.

## `paper`

Minecraft gameplay adapter and backend runtime.

Expected responsibilities:

- host zone instances/worlds
- host Map/Bounty/PvP/war encounter runtime
- translate Paper events into validated domain actions
- enforce local build/resource/mob rules
- render authoritative player state into Minecraft inventory/equipment/UI
- operate live player state only while owning the session lease
- checkpoint dirty state
- execute zone-local respawn/reset logic
- expose backend/instance health/registration
- observe authoritative eligible mob kills/boss completion events and submit persistent transitions
- render Bazaar/AH/Bank/skills/vote/leaderboard interfaces without becoming durable authority
- ranked-PvP and war match runtime isolation

Paper is not durable authority for wallet/bank/markets/Map run results/Bounty contracts/votes/history merely because it displays or triggers them.

## `velocity`

Network entry and routing adapter.

Expected responsibilities:

- connection entrypoint
- logical-zone/activity routing
- backend selection
- transfer coordination
- party/friend-instance preference where supported
- reject/redirect destinations that are unavailable or feature-locked

Velocity should remain thin and must not become a general gameplay, voting, market, or PvE-result engine.

## PostgreSQL persistence/application layer

Persistence code may initially live in `common`/application code rather than a separate service.

Responsibilities include:

- authoritative persistent records
- transactions/locking/idempotency
- session leases/state versions
- wallet/bank/ledgers/escrow
- item custody/provenance/pending delivery
- Bazaar/AH/trade/crafting settlement
- Map open/run/clear source records
- Bounty contract/summon/reward source records
- clan treasury/storage/rating/war history
- expansion ballots/resolution/feature/world-era state
- Chronicle/project/history source records
- schema migrations

A dedicated backend service is not required merely because these responsibilities exist.

## Content/configuration layer

Structured version-controlled data defines:

- item/recipe/roll profiles
- skills/XP/caps/rewards
- bank/economy rates
- zone definitions
- Map environments/families/objectives/modifiers/difficulty/rewards
- bounty families/tiers/materials/pouches/boss references
- expansion candidate sets/feature actions
- competitive tuning

Validation logic belongs in shared code; content files themselves do not gain authority over live persistent state.

## Resource pack

Presentation only:

- models/textures/UI assets
- stable resource references from content definitions

Resource-pack assets never define item authenticity, vote results, Map difficulty, or persistent identity.

## Infrastructure

`infra/` owns development/runtime bootstrap and deployment support:

- local Windows launch/stop/config
- PostgreSQL development environment
- future deployment manifests/scripts
- backups/restore tooling
- test harness support where operational

## Dependency direction

Prefer:

```text
common domain/application contracts
      ^
      |
paper / velocity adapters
      |
PostgreSQL / runtime integrations
```

Minecraft API objects should not leak into core persistent domain identity where avoidable.

## Package/feature organization

Feature packages may exist inside modules (e.g. `economy`, `item`, `map`, `bounty`, `skill`, `clan`, `worldprogression`) to keep responsibility clear.

Do not turn each package into a separate Gradle module/service unless a real dependency/operational boundary appears.

## Rule

Split a new module/service only when it creates a real operational or dependency boundary. Do not create abstractions or services merely because the final system may one day be large.