# Failure and Recovery

Failure behavior is part of architecture, not an implementation afterthought.

## General rule

Prefer losing a small bounded amount of ordinary uncheckpointed runtime progress over duplicating, double-settling, or ambiguously rewriting valuable persistent state.

Persistent correctness wins over pretending every last runtime event survived.

## Paper/backend crash

If a Paper backend dies:

- its hosted zone/encounter instances become unavailable/failed;
- player ownership leases are not valid forever;
- persistent recovery uses the latest committed player-state/version evidence;
- reconnect/reroute claims ownership only after fencing/lease rules permit it;
- stale backend writes remain rejected;
- resettable instance runtime (mobs, timers, temporary drops, active encounter entities) may be lost;
- critical already-committed transactions/results remain valid.

## Zone-instance failure

For resettable/temporary zones:

- mark instance unavailable;
- stop routing new players;
- replace/recreate from canonical template when needed;
- route affected/reconnecting players to another instance or safe fallback;
- never restore persistent player/economic/history state from the disposable world copy.

## Velocity/proxy failure

Velocity is routing/connection infrastructure, not persistent gameplay authority.

After restart:

- backend/player persistent state remains authoritative in PostgreSQL;
- new connections rebuild routing decisions;
- transfer tickets expire/single-use rules prevent replay;
- ambiguous interrupted transfers resolve to committed authoritative state/ownership evidence.

## PostgreSQL unavailable

Critical persistent mutations cannot safely continue without durable authority.

Fail closed for operations that would move/commit valuable state, including:

- Bazaar/AH/direct-trade/commission settlement;
- pocket/bank transfers or interest credit;
- crafting/upgrade/salvage of persistent value;
- historical reward issuance;
- Map opening/completion/reward settlement;
- bounty contract payment/progress transitions/summon/reward settlement;
- clan treasury/storage mutations;
- ranked/war settlement;
- expansion ballot mutation/resolution/feature transition;
- explicit project contributions/completion;
- irreversible progression/value operations.

Read-only or non-persistent local gameplay may degrade differently, but the server must not invent local shadow authority that later conflicts with PostgreSQL.

If safe continuation cannot be guaranteed, stop/deny the affected action or gracefully remove players from risky contexts.

## Player disconnect

Clean disconnect attempts to commit dirty player state and release ownership.

Unclean disconnect follows lease/recovery semantics. A disconnect must not make two simultaneous owners valid.

Disconnect does not by itself reverse already-committed market, bank, Map-open, bounty-fee, summon, vote, or settlement operations.

## Transfer interruption

Possible points:

- before source commit;
- after source commit/before route;
- during target connection;
- after target claim/before visible resume.

Recovery uses transaction/ticket/state-version evidence. Retrying a transfer must not duplicate inventory/state.

## Bazaar/AH/transaction interruption

All critical settlement is atomic/idempotent.

If the application loses the response after the database commits, retrying the same operation ID returns/reconstructs the committed result rather than executing again.

Cancel/fill and cancel/purchase races must have one valid database winner.

## Bank interruption

### Deposit/withdraw
Either the transfer commits fully or neither side changes.

### Death-loss race
Pocket death loss competes through authoritative wallet/state versions/transaction locking with simultaneous spend/deposit operations. The same Coin cannot be both deposited safely and destroyed/spent.

### Interest
Interest credit uses one stable period/eligibility key. Lost responses/retries/multiple backends cannot credit the same eligible period twice.

## Craft interruption

A craft either:

- consumes all configured authoritative inputs and creates the exact committed output(s), or
- consumes/creates nothing.

If randomized rolled gear was already committed before the response was lost, a retry returns/references the same unique output and roll quality rather than rolling again.

## Map opening/run failure

### Open transition
One Map item may create at most one valid persistent Map run.

If failure occurs around Map opening:

- before commit: Map remains owned/unopened;
- after commit: persistent run/open evidence exists and the Map cannot be reused.

The system must never lose the Map with no explanatory run/open record or keep the Map while also creating multiple valid runs.

### Active run backend crash
V1 may abort an unfinished disposable run rather than implement resumable encounter state.

Regardless of policy:

- persistent source Map/open state stays consistent;
- a completed run cannot settle twice;
- stale encounter events cannot settle after a terminal persistent state;
- player returns/routes to a safe persistent location;
- exact refund/no-refund policy is explicit rather than inferred from entity survival.

## Bounty interruption

### Contract fee
A committed contract-fee operation cannot charge twice on retry or create multiple contracts accidentally.

### Kill progress
Duplicate/replayed eligible-kill events cannot increment twice.

### Summon
One configured summon authorization cannot create more valid boss attempts than allowed.

### Boss crash/completion
Persistent contract/attempt state decides whether the attempt is failed/consumed/recoverable. Surviving/despawned entities are not authority.

Boss completion/reward settles at most once.

## Clan storage/treasury interruption

Clan shared-value mutations use the same custody/transaction invariants as personal value.

Concurrent withdrawals, role changes, kick/leave operations, or backend crashes cannot duplicate assets or leave ambiguous personal-versus-clan ownership.

## Clan-war crash

War runtime is disposable; economic custody/settlement is not.

On match failure:

- never let the match instance become the only copy of real gear state;
- use persisted custody/snapshot to determine valid recovery;
- settle/return according to explicit failure policy exactly once;
- rating/reward updates require a valid finalized outcome or explicit audited recovery path.

Exact match-abort policy is configuration/design detail; duplication is never a valid recovery strategy.

## Expansion vote/resolution failure

### Ballot mutation
A lost response/retry cannot produce two effective ballots for one uniqueness key.

### Resolution
One vote resolves at most once against its immutable/versioned candidate set.

If failure occurs after resolution commit but before caller response, retry reconstructs/returns the committed winner/result rather than resolving again.

### Feature/world-era action
Feature enable/era transition actions are idempotent and causally reference the resolution/project/action that authorized them.

A backend crash cannot revert a committed global vote or feature state merely because the UI/physical district representation did not update yet.

## Community-project failure

For an explicitly defined Community Project:

- contributions are exactly-once value transactions;
- project progress/history survives zone/backend failure;
- completion/feature actions are idempotent;
- build archives use versioned metadata/checksums;
- ordinary districts that are not explicit projects do not inherit this progress/review lifecycle.

## Controlled restart

Before planned backend shutdown:

1. mark/drain backend/instances where appropriate;
2. stop new transfers/admissions to risky runtime contexts;
3. commit dirty player state;
4. complete or safely pause/abort critical workflows according to their contracts;
5. ensure active Map/Bounty/war instances have a deterministic restart outcome;
6. release session ownership cleanly;
7. shut down.

Global PostgreSQL-authoritative markets/votes/features/history do not depend on one Paper process staying alive.

## Backups

Back up at minimum:

- PostgreSQL;
- persistent City/district world state;
- explicit project/build archives/schematics;
- content/config/resource-pack versions needed to interpret state;
- Map/Bounty/item definition versions where needed for historical interpretation;
- schema migrations/deployment configuration.

Resettable activity/encounter worlds are less valuable because they can be recreated from templates.

### Local-PC implementation

`infra/local/backup.ps1` implements the first executable recovery boundary as an **offline coherent snapshot** for the Windows development deployment.

It intentionally requires Velocity/Paper to be stopped, then captures one recovery set containing:

- a PostgreSQL custom-format dump;
- all discovered Paper worlds under the configured local backend runtime directories;
- version-controlled common/Paper content snapshots;
- database migrations and local topology settings;
- repository commit/dirty-state metadata where Git is available;
- manifest + SHA-256 checksums.

A backup receives `COMPLETE` only after the full snapshot succeeds. An incomplete directory is not a valid restore source.

This conservative local implementation may also copy disposable worlds. That is acceptable: they are not elevated to authority merely because they exist in the backup.

## Coherent restore

Do not restore an old database together with newer persistent world/economic representations without analyzing the consistency boundary.

A restore procedure must define which database/world snapshot pair is authoritative and how post-snapshot transactions/builds/votes/history are handled.

`infra/local/restore.ps1` enforces the local boundary by:

1. requiring an explicit destructive confirmation switch;
2. refusing to operate while Minecraft processes are reachable;
3. requiring `COMPLETE` and validating every SHA-256 checksum;
4. checking code/content recovery identity against the recorded repository state unless deliberately overridden;
5. staging and validating world copies before destructive work;
6. writing `runtime/restore.in-progress` before replacing authority;
7. restoring PostgreSQL and the staged world set;
8. removing the marker only after the complete restore succeeds.

`setup.ps1` and `start.ps1` refuse to run while `restore.in-progress` exists. A failed restore therefore leaves the network fenced rather than allowing a partially recovered state to become live.

`-AllowVersionMismatch` is a deliberate recovery escape hatch, not a normal convenience switch. Using a newer application/content version against an older backup is a separate migration/recovery decision and must be reviewed explicitly.

## Restore testing

Before public launch, perform an actual restore rehearsal. A backup that has never been restored is not a proven recovery system.

The repository now has executable local backup/restore tooling and CI PowerShell parse validation, but **real Windows + Docker restore correctness remains empirical until the rehearsal is run**.

After restore, verify representative:

- player/session ownership;
- wallet/bank accounting;
- item/commodity custody;
- markets;
- crafting/provenance;
- skills/caps;
- Map/Bounty persistent state;
- clan custody;
- votes/features/world era/history;
- `/integrity` returns no unexplained critical issues.

The exact rehearsal is tracked in `docs/testing/DEFERRED_EMPIRICAL_ACCEPTANCE.md` and does not block independent code work.

## Safe fallback

If a player's saved zone cannot be loaded/unlocked/routed, use a known safe destination (normally City) rather than stranding the session.

## Recovery audit

Manual recovery actions that alter persistent value, vote/feature state, Map/Bounty outcomes, world era, or historical authenticity require explicit audit records and dedicated capability. Avoid raw invisible database/item edits where possible.
