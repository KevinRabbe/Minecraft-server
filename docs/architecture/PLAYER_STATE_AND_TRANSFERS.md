# Player State and Transfers

## Persistent player state

At minimum:

- stable internal player ID and Minecraft UUID
- inventory/equipment
- Coin balance
- skills/XP
- progression/artifact state
- clan membership/role
- PvP rating/history
- unique-item ownership/provenance
- durable logical location

## Session states

Conceptual states:

- `ACTIVE`
- `TRANSFERRING`
- `DISCONNECTED`
- `RECOVERING`

## Ownership lease

Exactly one backend owns the right to mutate the live persistent runtime state for an active player.

Lease fields include owner backend, session identity, state version, heartbeat, and expiry.

## Transfer protocol

1. Player requests a target logical `zone_id`.
2. Source verifies transfer is allowed (not inside unresolved atomic settlement/forbidden transition).
3. Source freezes persistent mutations.
4. Source commits dirty state and increments `state_version`.
5. A short-lived single-use transfer ticket is created.
6. Zone router selects a suitable live target instance/backend or starts one if needed.
7. Velocity connects the target.
8. Target validates ticket/session/version.
9. Target atomically claims ownership.
10. Target loads/renders committed state and resumes gameplay.

A transfer ticket should carry logical intent and expected committed state, not make permanent gameplay depend on a backend ID.

## State versioning

Committed player state uses monotonically increasing versions.

A stale backend must not overwrite newer state. A write based on an obsolete version is rejected rather than merged blindly.

## Checkpointing

Ordinary dirty runtime state may be batched/checkpointed on a short configurable cadence.

Force durable settlement/commit at boundaries such as:

- cross-backend transfer
- clean logout
- controlled shutdown
- Bazaar/AH settlement
- secure trade
- community contribution
- historical reward issuance
- irreversible progression/artifact action
- war loadout custody/settlement

## Logout

Clean logout:

1. freeze/finish allowed mutations;
2. commit dirty persistent state;
3. mark session disconnected/release ownership;
4. next login creates/claims a valid new session and routes from logical location.

## Crash recovery

If the owning backend disappears:

- its lease is not valid forever;
- recovery waits/uses lease-expiry or equivalent fencing semantics;
- the latest committed state version is authoritative;
- reconnect/reroute claims ownership on a healthy backend;
- old/stale writers remain fenced out.

Ordinary uncheckpointed low-value progress may be lost within the accepted checkpoint window. Duplication or double settlement is not acceptable.

## Location persistence

Persistent/resettable zone example:

- save `zone_id`
- optionally save named entry/spawn point
- do not require old `instance_id` to exist after restart

Persistent City may additionally retain exact coordinates where safe/useful.

If a saved destination is unavailable, locked, invalid, or cannot start, route to the configured safe fallback (normally City).

## Ranked PvP and war

Ranked PvP uses temporary standardized state isolated from normal persistent inventory.

Clan war uses real economic value but enters through explicit custody/snapshot/settlement, not by letting a disposable match instance become the only authoritative copy.
