# Infrastructure

## Deployment principle

**Design for horizontal capacity, deploy the minimum actually needed.**

Development and first real playtests run on the developer's Windows PC. Do not rent production hosting before real player demand justifies recurring cost.

Canonical architecture: [`../docs/architecture/SYSTEM_OVERVIEW.md`](../docs/architecture/SYSTEM_OVERVIEW.md) and [`../docs/architecture/WORLD_ZONES_INSTANCES.md`](../docs/architecture/WORLD_ZONES_INSTANCES.md).

## Runtime model

The logical runtime is:

```text
Velocity
   |
logical zone router / instance registry
   |
Paper backend(s)
   └─ one or more live zone instances

PostgreSQL
```

Do **not** assume one permanent Paper process per `CITY/MINE/FOREST/FARM/NETHER/PVP/WAR` role.

A backend is infrastructure capable of hosting zone instances. At small scale one Paper backend may host several zones/instances. Additional backends are introduced only for measured load, isolation, restart, or operational reasons.

Existing `SERVER_ID` / `SERVER_ROLE` configuration remains useful as runtime metadata/compatibility during the transition, but gameplay code must target logical zones rather than backend IDs. A defined role/feature does not imply that its backend must be active.

In particular, Nether is architecturally supported but not player-accessible/required to run at launch. War/PvP infrastructure may also be activated only when used.

## Hosting transition

Move to rented hosting only when actual player usage/investment makes it worthwhile.

The move must not change gameplay identities or persistent-state semantics. Logical zones, PostgreSQL state, transactions, Velocity routing, and plugin artifacts should move from the local PC to hosted capacity without redesigning the game.

A hosted deployment may still begin on one physical machine and split processes/hosts only from measured demand.

## Local Windows network

`infra/local/` contains the current local runtime bootstrap/config/stop helpers. Generated runtime data under `infra/local/runtime/` is ignored by Git.

The scripts/configuration have been prepared, but end-to-end execution on the developer's actual PC still needs real verification before claiming the local runtime boundary is proven.

Prefer graceful shutdown through the supervised startup flow; the emergency stop helper is a fallback.

## Local PostgreSQL

From `infra/compose`:

```bash
docker compose up -d postgres
```

Development defaults are intentionally local-only. Override `POSTGRES_PASSWORD` outside source control for any shared/non-local environment.

## Java / server baseline

- Stable Paper target: 26.1.2 on Java 25.
- Velocity requires Java 21+; the repository uses Java 25 consistently.
- Backend Paper ports are local/private behind Velocity and must not be exposed publicly once forwarding is configured.
- Use modern Velocity forwarding at the proxy/backend trust boundary.

## Scaling rule

Do not add Kubernetes, Redis clusters, brokers, or an advanced scheduler because the architecture *could* use them. Introduce infrastructure only when measured load/availability requirements justify it.
