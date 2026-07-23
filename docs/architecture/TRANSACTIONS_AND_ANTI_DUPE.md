# Transactions and Anti-Dupe Invariants

## Core invariant

Every economically valuable asset has **one authoritative owner/location at a time**.

Moving value means transferring that authority exactly once.

Examples of locations:

- player inventory/equipment
- player pouch
- Bazaar escrow
- Auction escrow
- secure-trade escrow
- commission escrow
- war custody
- project contribution/consumption
- pending delivery

The same authoritative value must never simultaneously remain spendable by the source and become available to the destination.

## Escrow as the common primitive

High-value multi-step workflows use the same pattern:

`lock/remove value from normal player control -> perform workflow -> settle exactly once -> return/cancel exactly once if allowed`

This underlies Bazaar, AH, secure trade, commissions, war loadouts, and other future workflows.

## Idempotency

Every critical value-moving operation has a stable `operation_id`/idempotency key.

If an operation is retried after an ambiguous timeout/crash, the system returns the already-committed result rather than executing a second transfer.

Critical examples:

- Bazaar fill/cancel
- AH purchase/cancel
- secure trade settlement
- commission settlement
- compression/decompression where persisted
- project contribution
- war custody/settlement
- historical reward issuance
- recovery/admin value correction

## Database transaction rule

Never implement valuable operations as unsafe check-then-act sequences across separate commits.

Bad conceptual pattern:

`check balance -> later subtract -> later create item`

Correct pattern:

1. begin transaction;
2. lock/compare authoritative source state;
3. validate preconditions;
4. decrement/transfer source value;
5. create/increment destination value;
6. append ledger/audit state;
7. mark idempotency result;
8. commit.

If any required step fails, no partial authoritative transfer remains.

## Economic ledger

Maintain an append-oriented ledger for important value movements with fields such as:

- operation ID
- actor/player/system account
- asset type/definition/instance
- quantity/amount
- direction/source/destination
- reason
- related entity (order, trade, war, project, etc.)
- timestamp

The ledger is audit/reconstruction evidence, not necessarily the primary balance table.

## Fixed-point currency

Coins use fixed-point integers, never floating point.

Example convention: `1 Coin = 100 internal units`.

Balances cannot become negative. Escrowed currency is no longer available to spend elsewhere.

## Bazaar

### Sell order
Items move from player ownership into Bazaar escrow when the order is created. Fills consume only escrowed quantity.

### Buy order
Maximum required funds move into Bazaar escrow when the order is created. Fills consume reserved funds and cancellation returns the unused remainder.

### Matching
Deterministic price-time priority:

- sells: lowest price, then oldest
- buys: highest price, then oldest

No hidden target-price logic.

## Auction House

A non-stackable/unique item leaves player control when listed and enters Auction escrow.

Purchase atomically transfers:

- buyer currency to seller settlement
- item ownership to buyer/pending delivery
- listing to sold state
- ledger/idempotency records

## Secure trade

Offer changes invalidate previous confirmation. Once both sides lock/confirm, settlement moves both sides' value atomically.

Cancellation returns escrow only according to defined pre-settlement rules.

## Pending delivery

A completed transaction must not fail merely because physical Minecraft inventory has no slot or the player is offline.

Already-owned results can land in durable pending delivery and be claimed/rendered later.

Pending delivery is a correctness buffer, not a general mail/economic storage system.

## Commissions

Requester materials/payment enter commission escrow before a worker can settle the job.

On valid completion:

- materials are consumed
- result goes to requester/pending delivery
- commission payment goes to worker
- job becomes completed exactly once

## Community contributions

Contributions are usually irreversible consumption, not retrievable chest contents.

A contribution transaction removes value from the player and increments immutable project contribution/progress records atomically.

The physical project build is representation; contribution history is authoritative.

## War custody

Real economic loadouts enter explicit custody/snapshot before the disposable match runtime uses them.

Final settlement applies consumed ammunition/potions, durability changes, returned equipment, and rewards exactly once.

A crashed match instance must not be able to duplicate the authoritative original value.

## Historical rewards

Exactly-once entitlements should be protected by database uniqueness such as the logical equivalent of:

`UNIQUE(project_or_event_id, player_id, reward_id)`

Retries, duplicate messages, or staff UI mistakes cannot create a second authentic entitlement.

## Unique-item duplicate detection

A unique `item_instance_id` has one authoritative location.

If two live Minecraft representations claim the same ID:

1. persistent authority determines the legitimate current ownership/location;
2. conflicting representation is rejected/quarantined;
3. an audit event is recorded;
4. never silently accept both.

## Commodity conservation checks

For important commodities, periodic accounting can compare:

`legitimate creation - legitimate consumption ≈ held + escrowed + pending quantities`

The goal is early anomaly detection, not per-dirt-block forensic overhead.

## Legitimate creation/destruction sources

Economically meaningful creation should have a reason such as:

- authorized gathering
- mob drop
- crafting/refining output
- project/war/event reward
- controlled NPC/bootstrap purchase
- audited recovery

Consumption should likewise have a reason such as:

- crafting/refining input
- consumable use
- salvage sink
- project contribution
- durability destruction

## Cross-backend dupe prevention

Single-writer leases plus state versions fence stale backends.

A source backend that has released ownership cannot later restore an old inventory snapshot over newer authoritative state.

## Backup/rollback warning

Naively restoring an old database while keeping newer persistent worlds (or vice versa) can manufacture duplicate value. Backup/restore must use coherent recovery boundaries; see `FAILURE_RECOVERY.md`.

## Hard rule

No economically valuable operation may depend on timing, duplicate-message absence, server ordering, or player honesty for correctness.
