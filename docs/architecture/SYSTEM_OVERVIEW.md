# System Overview

## Product architecture

The game is a persistent multiplayer layer built on Minecraft/Paper rather than a conventional open-ended survival server.

Players experience compact authored places, persistent progression, a player-driven economy, social systems, opt-in competition, and server history. Minecraft provides the low-level interaction substrate; project code provides the network/game rules around it.

## Runtime planes

### Gameplay plane
Paper hosts live gameplay and one or more zone instances.

Examples:

- City/starter region
- Starter Woods instance 1/2/3
- Mine instance 1/2
- PvE area instances
- ranked-PvP match instances
- clan-war instances

### Control plane
The smallest useful control plane tracks and routes runtime capacity:

- zone catalog
- backend registry
- instance registry
- instance lifecycle
- zone router
- feature accessibility state

V1 does not require a separate orchestration service. These responsibilities may live inside the existing application modules until measured scale justifies separation.

### Persistent plane
PostgreSQL is durable authority for persistent network state and critical transactions:

- player identity/session ownership
- persistent inventory/equipment representation
- wallet/ledger
- skills/progression
- unique item identity/provenance
- markets/trades/escrow/delivery
- clans/ratings/war settlement
- community projects/history
- feature unlocks
- derived-data source records

### Persistent world plane
Physical geography whose block state matters is stored/backed up as world data, principally the canonical City/community-building space.

## Core responsibility rule

**PostgreSQL remembers. Velocity decides where you go. Paper decides what happens while you are there.**

This is a responsibility summary, not permission for Paper to become durable authority for critical persistent state.

## Player-facing topology

Players interact with logical places such as `Starter Woods`, `Mine`, `City`, or `Arena`.

They do not choose `paper-03` or `woods-07` as gameplay concepts.

The routing stack resolves:

`requested zone -> suitable live zone instance -> backend hosting that instance`

## Scaling model

A gameplay zone is intentionally compact. Its capacity is scaled horizontally by running additional equivalent instances only when players are actually using that zone.

Example:

```text
500 players online

Starter Woods: 14 active -> 1 instance
Mine: 63 active -> 3 or 4 instances (depending on tuned capacity)
Farm: 8 active -> 1 instance
Wolf area: 0 active -> no extra instance required
```

Total network population never directly determines the number of Woodcutting/Mine/etc. instances.

## Initial deployment

Development and first playtests run on the user's Windows PC.

The architecture supports multiple Paper backends but does not require them immediately. One backend may host several zone instances. Additional processes/machines are added only for measured load, isolation, restart, or operational reasons.

## Non-goals

Do not introduce these without an observed requirement:

- Kubernetes
- service mesh
- Kafka/message-broker architecture
- Redis cluster
- per-zone microservices
- one Paper process per gameplay area by default
- massive open-world terrain as a capacity mechanism
