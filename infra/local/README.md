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

1. validates every backend that declares `RequireResourceContent = $true` against the exact authored resource zone/template plus block/entity placements;
2. verifies Java 25 and Docker;
3. downloads stable Paper `26.1.2` and stable Velocity `4.0.0` through PaperMC's downloads service;
4. generates a random local Velocity forwarding secret;
5. creates the currently configured loopback-only Paper backends from `settings.ps1`;
6. boots each backend once so Paper generates its own current configuration;
7. enables Velocity modern forwarding;
8. builds and deploys the shared Paper/Velocity plugins;
9. starts local PostgreSQL through Docker Compose.

Generated worlds, secrets, downloaded server JARs, backups and runtime data live under `infra/local/runtime/` and are intentionally ignored by Git.

`setup.ps1` and `start.ps1` both refuse to run while `runtime/restore.in-progress` exists. This prevents a partially restored database/world pair from accidentally becoming live.

## Start the whole network

```powershell
.\infra\local\start.ps1
```

During development this rebuilds/deploys the plugins before startup. To start without rebuilding:

```powershell
.\infra\local\start.ps1 -SkipBuild
```

The supervisor does not open Velocity merely because the Paper TCP ports are reachable. Before starting the proxy it waits until PostgreSQL shows every configured backend as `ONLINE`, its exact configured zone/template as `ACTIVE` under the backend's current incarnation, and at least one registered `resource_sources` row for every backend that declares `RequireResourceContent = $true`. A Paper process whose plugin failed or whose required resource authority did not materialize therefore fails closed before players can enter through Velocity.

Connect Minecraft to:

```text
localhost:25565
```

Keep the PowerShell supervisor window open. Press `Ctrl+C` there to gracefully stop the network. The supervisor stops Velocity first so no new local sessions can enter; once the proxy is stopped, it keeps Paper alive for a bounded 10-second logout-drain window so `PlayerQuit` final checkpoints can finish before backend `stop` is sent. An already-crashed/stopped Velocity process uses the same drain window. If Velocity itself fails to stop promptly, the supervisor warns and proceeds with backend shutdown rather than hanging indefinitely.

## Coherent local backup

The first recovery implementation deliberately uses an **offline coherent snapshot**. It does not pretend that an arbitrary live filesystem copy is synchronized with PostgreSQL.

1. Gracefully stop the network with `Ctrl+C` in the supervisor.
2. From the repository root run:

```powershell
.\infra\local\backup.ps1
```

The backup script refuses to run while Velocity or any configured Paper backend port is reachable. It creates:

- a PostgreSQL custom-format dump;
- every discovered Paper world containing `level.dat` under the configured backend runtime directories;
- snapshots of versioned common/Paper content, database migrations, and local topology settings;
- repository commit/dirty-state metadata when Git is available;
- `manifest.json`;
- SHA-256 checksums for all trusted backup files;
- a `COMPLETE` marker written only after the full snapshot succeeds.

The default output is:

```text
infra/local/runtime/backups/<UTC timestamp>/
```

A dirty repository is rejected by default because a clean commit is the preferred code/content recovery identity. `-AllowDirtyRepository` is an explicit development escape hatch, not the normal production procedure.

Backups contain authoritative player/economy/world state and must be treated as private operational data.

## Restore a coherent snapshot

Restore is destructive and requires an explicit switch:

```powershell
.\infra\local\restore.ps1 `
  -BackupPath .\infra\local\runtime\backups\<snapshot> `
  -ConfirmRestore
```

Before changing PostgreSQL or live world directories, restore verifies:

1. the `COMPLETE` marker;
2. manifest schema/mode;
3. every SHA-256 checksum;
4. the repository commit/dirty-state recovery identity unless explicitly overridden;
5. every staged world contains `level.dat`.

It then creates `runtime/restore.in-progress`, restores PostgreSQL, installs the staged world snapshot, and removes the marker only after the complete operation succeeds.

If restore fails after destructive work begins, **do not start the network**. The marker remains intentionally; rerun the selected restore until it completes successfully. Both `setup.ps1` and `start.ps1` enforce this fence.

`-AllowVersionMismatch` is an explicit recovery escape hatch for a deliberately reviewed migration/version scenario. It must not be used merely to make a mismatched backup restore proceed.

After a successful restore:

1. start the local network;
2. reconnect through Velocity;
3. run `/integrity 100`;
4. run the representative recovery acceptance checks in `docs/testing/DEFERRED_EMPIRICAL_ACCEPTANCE.md`.

The scripts and CI syntax checks do **not** by themselves prove real-machine restore correctness. The actual Windows/Docker rehearsal remains required before release.

## Capture incarnation authority evidence

During the issue #58 restore rehearsal, capture the durable backend/zone/session ownership graph at each important process boundary:

```powershell
.\infra\local\capture-runtime-authority.ps1 `
  -EvidencePath .\infra\local\runtime\restore-rehearsal-evidence\<evidence-id> `
  -Label pre-shutdown
```

Repeat with distinct labels after restart/replacement and after restore, for example:

```powershell
.\infra\local\capture-runtime-authority.ps1 `
  -EvidencePath .\infra\local\runtime\restore-rehearsal-evidence\<evidence-id> `
  -Label post-restart

.\infra\local\capture-runtime-authority.ps1 `
  -EvidencePath .\infra\local\runtime\restore-rehearsal-evidence\<evidence-id> `
  -Label post-restore
```

The helper is read-only. It records CSV snapshots for backend incarnations, zone ownership, and live player-session ownership, then hashes those files into `manifest.json`. It requires a clean repository and refuses to overwrite an existing label. The snapshots must be interpreted together with the supervisor/process transcripts; they are evidence inputs, not an automatic PASS decision.

## Local topology

The current `settings.ps1` development topology is:

```text
Velocity  127.0.0.1:25565
├─ paper-01 / city           127.0.0.1:25566
├─ paper-02 / starter-woods  127.0.0.1:25567
└─ paper-03 / starter_pve    127.0.0.1:25568
PostgreSQL runs from infra/compose.
```

`starter_pve` is present so the local network can exercise the authoritative managed-Zombie Combat-XP path and the Bounty boss runtime instead of relying on natural vanilla mobs, which intentionally carry no MMO reward authority.

A backend with `RequireResourceContent = $true` opts into a fail-fast setup contract: its configured logical zone and template must select authored renewable-resource definitions, and those definitions must have matching physical block/entity placements. Backends without that flag may intentionally be resource-free. The same validator runs in Windows PowerShell CI, including a negative template-drift proof.

All backend ports are bound to loopback. Only Velocity is a player entry point, and for local-only testing Velocity itself is also bound to loopback.

The topology is intentionally a development harness, not a declaration that V1 needs one permanent backend per gameplay zone. Add processes only from measured capacity/isolation needs.

## Local-runtime acceptance

The local development boundary is accepted only when:

1. one command starts PostgreSQL, all currently configured Paper backends and Velocity;
2. Minecraft can connect through `localhost:25565`;
3. the player initially reaches the configured City backend;
4. persistent-state transfer uses the trusted transfer flow rather than direct backend switching;
5. every backend loads the same Paper plugin with its configured backend/zone identity;
6. direct backend access remains loopback-only;
7. `Ctrl+C` stops Velocity, permits the bounded Paper logout-drain window, and then shuts every backend down cleanly;
8. the backup/restore rehearsal has demonstrated that PostgreSQL and persistent world state can be recovered to the same selected recovery point without duplication or authority corruption.
