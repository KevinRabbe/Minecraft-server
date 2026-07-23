# Local Windows development network

V1 is developed and first tested on the developer PC. Hosting is rented only after real player demand justifies the recurring cost.

## Prerequisites

- Windows PowerShell
- Java 25 on `PATH`
- Docker Desktop running
- Internet access for first-time Paper, Velocity and Gradle downloads
- Minecraft Java Edition compatible with the selected Paper version

No global Gradle installation is required. `setup.ps1` uses the repository wrapper if present, then an installed Gradle if present, otherwise downloads a local Gradle distribution under the ignored runtime folder.

## First setup

From the repository root:

```powershell
.\infra\local\setup.ps1
```

The script:

1. verifies Java 25 and Docker,
2. downloads stable Paper `26.1.2` and stable Velocity `4.0.0` through PaperMC's downloads service,
3. generates a random local Velocity forwarding secret,
4. creates seven loopback-only Paper backends,
5. boots each backend once so Paper generates its own current configuration,
6. enables Velocity modern forwarding,
7. builds and deploys the shared Paper/Velocity plugins,
8. starts local PostgreSQL through Docker Compose.

Generated worlds, secrets, downloaded server JARs and runtime data live under `infra/local/runtime/` and are intentionally ignored by Git.

## Start the whole network

```powershell
.\infra\local\start.ps1
```

During development this rebuilds/deploys the plugins before startup. To start without rebuilding:

```powershell
.\infra\local\start.ps1 -SkipBuild
```

Connect Minecraft to:

```text
localhost:25565
```

The proxy initially sends players to `city-01`. During M0, Velocity's built-in `/server` command can be used to prove routing to the other configured backends.

Keep the PowerShell supervisor window open. Press `Ctrl+C` there to gracefully stop Velocity and every Paper backend.

## Local topology

```text
Velocity  127.0.0.1:25565
├─ city-01    127.0.0.1:25566
├─ mine-01    127.0.0.1:25567
├─ forest-01  127.0.0.1:25568
├─ farm-01    127.0.0.1:25569
├─ nether-01  127.0.0.1:25570
├─ pvp-01     127.0.0.1:25571
└─ war-01     127.0.0.1:25572

PostgreSQL runs from infra/compose.
```

All backend ports are bound to loopback. Only Velocity is a player entry point, and for local-only testing Velocity itself is also bound to loopback.

## M0 acceptance

M0 is complete only when:

1. one command starts PostgreSQL, all seven Paper roles and Velocity,
2. Minecraft can connect through `localhost:25565`,
3. the player initially reaches City,
4. `/server` can route to each logical backend,
5. every backend loads the same Paper plugin with its configured `SERVER_ID` and `SERVER_ROLE`,
6. direct backend access is unavailable from other machines because the backend listeners bind to `127.0.0.1`,
7. `Ctrl+C` shuts the local network down cleanly.

Only after this boundary works do we begin M1 player-session/state ownership.
