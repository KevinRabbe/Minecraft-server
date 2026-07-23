# Persistent Player State

## Authority rule

The network owns persistent player state. A Paper backend only operates on that state while it holds the active ownership lease.

Exactly one backend may mutate a player's persistent runtime state at a time.

## Persistent state

At minimum:

- stable internal player ID plus preserved Minecraft UUID
- carried inventory and equipment
- Coin balance
- skill levels and XP
- progression/artifact state
- clan membership/role
- PvP rating and history
- item provenance/unique item ownership

## Session model

Conceptual fields:

- `player_id`
- `network_session_id`
- `owner_server_id`
- `state_version`
- `status`
- `lease_expires_at`
- `last_heartbeat_at`

Statuses:

- `ACTIVE`
- `TRANSFERRING`
- `DISCONNECTED`
- `RECOVERING`

## Transfer protocol

1. Player requests a target logical role.
2. Source verifies transfer is safe (not inside an atomic trade/settlement/other forbidden state).
3. Freeze persistent mutations.
4. Commit final authoritative state and increment version.
5. Create a single-use transfer ticket.
6. Velocity resolves and connects the target backend.
7. Target validates ticket, session, and state version.
8. Target atomically claims session ownership.
9. Load committed state.
10. Resume gameplay.

A failed transfer leaves the last committed version authoritative. Retry/cancel must not duplicate state.

## Checkpointing

Ordinary dirty runtime state may be checkpointed in batches (initial target roughly every few seconds; tune later).

Force immediate durable commit on:

- backend transfer
- logout/disconnect handling
- controlled shutdown
- Bazaar/AH settlement
- secure trade
- community-project contribution
- artifact/progression irreversible action
- historical reward issuance
- war loadout lock/settlement

## Unique items

Valuable non-fungible items receive stable `item_instance_id` values. Persistence must reject or quarantine duplicated instance IDs rather than silently accepting both copies.

## Recovery invariant

After a backend crash, recovery uses the last committed authoritative version. Lease expiry permits a different backend to recover ownership; two backends must never concurrently become valid writers.
