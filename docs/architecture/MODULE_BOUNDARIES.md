# Module Boundaries

The repository currently uses `common`, `paper`, and `velocity`. Architectural responsibilities should map cleanly onto those modules without forcing premature service decomposition.

## `common`

Shared domain contracts and infrastructure-neutral logic.

Expected responsibilities:

- stable IDs/enums/value types
- zone/feature definitions
- session/transfer contracts
- item definition/instance contracts
- skill/stat/modifier rules
- transaction/idempotency value types
- market/order domain rules
- clan/war/project domain contracts
- configuration validation models
- domain-event definitions

`common` must not depend on Paper or Velocity APIs.

## `paper`

Minecraft gameplay adapter and backend runtime.

Expected responsibilities:

- host zone instances/worlds
- translate Paper events into validated domain actions
- enforce local build/resource/mob rules
- render authoritative player state into Minecraft inventory/equipment/UI
- operate live player state only while owning the session lease
- checkpoint dirty state
- execute zone-local respawn/reset logic
- expose backend/instance health/registration
- ranked-PvP and war match runtime isolation

Paper is not durable authority for wallet/markets/history merely because it currently displays or manipulates them.

## `velocity`

Network entry and routing adapter.

Expected responsibilities:

- connection entrypoint
- logical-zone routing
- backend selection
- transfer coordination
- party/friend-instance preference where supported
- reject/redirect destinations that are unavailable or locked

Velocity should remain thin and must not become a general gameplay engine.

## PostgreSQL persistence layer

Persistence code may initially live in shared/application code rather than a separate service.

Responsibilities:

- authoritative persistent records
- transactions/locking/idempotency
- session leases/state versions
- ledgers and escrow
- durable project/market/clan/rating history
- schema migrations

## Resource pack

Presentation only:

- models/textures/UI assets
- stable resource references from content definitions

Resource-pack assets never define item authenticity or persistent identity.

## Infrastructure

`infra/` owns development/runtime bootstrap and deployment support:

- local Windows launch/stop/config
- PostgreSQL development environment
- future deployment manifests/scripts
- backups/restore tooling when introduced

## Dependency direction

Prefer:

```text
common domain contracts
      ^
      |
paper / velocity adapters
      |
PostgreSQL / runtime integrations
```

Minecraft API objects should not leak into core persistent domain identity where avoidable.

## Rule

Split a new module/service only when it creates a real operational or dependency boundary. Do not create abstractions or services merely because the final system may one day be large.
