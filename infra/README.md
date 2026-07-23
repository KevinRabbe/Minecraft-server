# Infrastructure

## V1 deployment principle

Design distributed, deploy consolidated.

The initial network may run on one physical host as separate processes:

```text
Velocity
├─ CITY-01
├─ MINE-01
├─ FOREST-01
├─ FARM-01
├─ NETHER-01
├─ PVP-01
└─ WAR-01

PostgreSQL
```

All Paper backends run the same plugin. Each process receives at least:

- `SERVER_ID` (for example `mine-01`)
- `SERVER_ROLE` (`CITY`, `MINE`, `FOREST`, `FARM`, `NETHER`, `PVP`, or `WAR`)

Do not create separate gameplay plugin codebases per role.

## Local PostgreSQL

From `infra/compose`:

```bash
docker compose up -d postgres
```

Development defaults are intentionally local-only. Override `POSTGRES_PASSWORD` outside source control for any shared/non-local environment.

## Java / server baseline

- Stable Paper target: 26.1.2 on Java 25.
- Velocity currently requires Java 21+, but the repository uses Java 25 consistently across modules/processes.
- Backend Paper ports must not be exposed publicly once Velocity forwarding is configured.

## Not yet in M0

The checked-in repository currently establishes the code/config contract and PostgreSQL service. Paper/Velocity runtime download/configuration, modern forwarding secrets, plugin deployment, and one-command network startup belong to the remaining M0 bootstrap work and must be completed before M1 begins.
