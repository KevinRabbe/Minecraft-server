# Network Architecture

## Initial topology

```text
ONE PHYSICAL MACHINE

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

One physical host does not imply one Minecraft server process. Each backend is an independent Paper process using the same Paper plugin with role-specific configuration.

## Responsibilities

### PostgreSQL
Durable authority for persistent network state and critical transactions.

### Velocity
Connection entrypoint, backend selection, routing, and network-level transfer coordination. It should remain small and must not become a gameplay server.

### Paper
Owns moment-to-moment gameplay for the player while that backend owns the player's active session.

Summary: **PostgreSQL remembers. Velocity decides where you go. Paper decides what happens while you are there.**

## Role routing

Players interact with logical activities (`Mine`, `Forest`, `Farm`, `Nether`, `Arena`), not backend IDs. Velocity resolves the concrete server instance. This permits later horizontal duplication such as `MINE-02` without changing game semantics.

## Persistence classes

- `CITY`: permanent and carefully backed up.
- `MINE`, `FOREST`, `FARM`, `NETHER`: renewable/replaceable activity terrain; economic state is authoritative outside arbitrary world blocks.
- `PVP`, `WAR`: resettable competitive worlds; ratings/results/settlement are durable, maps are not.

## Security

Use modern Velocity forwarding. Backend Paper ports must not be publicly reachable. The proxy/backend boundary is a trust boundary and all cross-server state claims must be validated.

## Scaling rule

Design distributed, deploy consolidated, scale out only from measured demand. Do not add Redis, message brokers, Kubernetes, or separate microservices before a demonstrated requirement exists.
