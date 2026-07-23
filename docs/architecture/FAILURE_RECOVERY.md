# Failure and Recovery

Failure behavior is part of architecture, not an implementation afterthought.

## General rule

Prefer losing a small bounded amount of ordinary uncheckpointed progress over duplicating or double-settling valuable persistent state.

Persistent correctness wins over pretending every last runtime event survived.

## Paper/backend crash

If a Paper backend dies:

- its hosted zone instances become unavailable/failed;
- its player ownership leases are not valid forever;
- persistent recovery uses the latest committed player-state version;
- reconnect/reroute claims ownership only after fencing/lease rules permit it;
- stale backend writes remain rejected;
- resettable instance runtime (mobs, resource timers, temporary drops) may be lost;
- critical already-committed transactions remain valid.

## Zone-instance failure

For resettable/temporary zones:

- mark instance unavailable;
- stop routing new players;
- replace/recreate from canonical template when needed;
- route affected/reconnecting players to another instance or safe fallback;
- never restore persistent player/economic state from the disposable world copy.

## Velocity/proxy failure

Velocity is routing/connection infrastructure, not persistent gameplay authority.

After restart:

- backend/player persistent state remains authoritative in PostgreSQL;
- new connections rebuild routing decisions;
- transfer tickets expire/single-use rules prevent replay;
- ambiguous interrupted transfers resolve to the last committed authoritative state/ownership rule.

## PostgreSQL unavailable

Critical persistent mutations cannot safely continue without durable authority.

Behavior should fail closed for operations that would move/commit valuable state:

- Bazaar/AH/trade/commission settlement
- historical reward issuance
- war settlement
- project contributions
- irreversible progression/value operations

Read-only or non-persistent local gameplay may degrade differently, but the server must not invent local shadow authority that later conflicts with PostgreSQL.

If safe continuation cannot be guaranteed, stop/deny the affected action or gracefully remove players from risky contexts.

## Player disconnect

Clean disconnect attempts to commit dirty player state and release ownership.

Unclean disconnect follows lease/recovery semantics. A disconnect must not make two simultaneous owners valid.

## Transfer interruption

Possible points:

- before source commit
- after source commit/before route
- during target connection
- after target claim/before visible resume

Recovery uses transaction/ticket/state-version evidence. Retrying a transfer must not duplicate inventory/state.

## Bazaar/AH/transaction interruption

All critical settlement is atomic/idempotent.

If the application loses the response after the database commits, retrying the same operation ID returns/reconstructs the committed result rather than executing again.

## Clan-war crash

War runtime is disposable; economic custody/settlement is not.

On match failure:

- never let the match instance become the only copy of real gear state;
- use persisted custody/snapshot to determine valid recovery;
- settle/return according to explicit failure policy exactly once;
- rating/reward updates require a valid finalized match outcome or explicit administrative recovery path.

Exact match-abort policy is configuration/design detail, but duplication is never a valid recovery strategy.

## Controlled restart

Before planned backend shutdown:

1. mark/drain backend/instances where appropriate;
2. stop new transfers/admissions;
3. commit dirty player state;
4. complete or safely pause/abort critical workflows;
5. release session ownership cleanly;
6. shut down.

## Backups

Back up at minimum:

- PostgreSQL
- persistent City/world state
- project build archives/schematics
- content/config/resource-pack versions needed to interpret state
- schema migrations/deployment configuration

Resettable activity worlds are less valuable because they can be recreated from templates.

## Coherent restore

Do not restore an old database together with newer persistent world/economic representations without analyzing the consistency boundary.

A restore procedure must define which database/world snapshot pair is authoritative and how post-snapshot transactions/builds are handled.

## Restore testing

Before public alpha, perform an actual restore rehearsal. A backup that has never been restored is not a proven recovery system.

## Safe fallback

If a player's saved zone cannot be loaded/unlocked/routed, use a known safe destination (normally City) rather than stranding the session.

## Recovery audit

Manual recovery actions that alter persistent value/history require explicit audit records and dedicated capability; avoid raw invisible database/item edits where possible.
