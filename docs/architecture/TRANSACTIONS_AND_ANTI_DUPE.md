# Transactions and Anti-Dupe Invariants

## Core invariant

Every economically valuable asset has **one authoritative owner/location at a time**.

Moving value means transferring that authority exactly once.

Examples of locations:

- player inventory/equipment
- player gathering/bounty pouch
- player pocket Coin balance
- protected bank custody
- Bazaar escrow
- Auction escrow
- secure-trade escrow
- commission escrow
- clan treasury/shared storage
- war custody
- explicit project contribution/consumption
- pending delivery
- Map-open/consumed state where the exact Map is no longer tradeable

The same authoritative value must never simultaneously remain spendable by the source and become available to the destination.

## Escrow/custody as common primitives

High-value multi-step workflows use the same pattern:

`lock/remove value from normal control -> perform workflow -> settle exactly once -> return/cancel exactly once if allowed`

This underlies Bazaar, AH, secure trade, commissions, clan storage movement, war loadouts, and future workflows.

Not every workflow is reversible escrow. Crafting inputs, bounty fees, PvE pocket loss, and explicit project contributions are consumption/sinks and must record that irreversible semantic explicitly.

## Idempotency

Every critical value/state-moving operation has a stable `operation_id`/idempotency key.

If an operation is retried after an ambiguous timeout/crash, the system returns/reconstructs the already-committed result rather than executing again.

Critical examples:

- wallet transfer/faucet/sink
- bank deposit/withdraw/interest period credit
- PvE pocket-money death loss
- Bazaar order create/fill/cancel
- AH listing/purchase/cancel
- secure trade settlement
- craft/upgrade/salvage settlement
- commission settlement
- compression/decompression where persisted
- Map open and Map completion/reward
- bounty contract fee/start, summon, boss completion/reward
- clan treasury/storage movement
- project contribution/completion action
- expansion ballot mutation/resolution/feature action
- war custody/settlement
- historical reward issuance
- recovery/admin value/state correction

## Database transaction rule

Never implement valuable operations as unsafe check-then-act sequences across separate commits.

Bad conceptual pattern:

`check balance -> later subtract -> later create item`

Correct pattern:

1. begin transaction;
2. lock/compare authoritative source/workflow state;
3. validate preconditions and request identity;
4. decrement/transfer/consume source value/state;
5. create/increment destination/result state;
6. append ledger/provenance/audit evidence as required;
7. persist idempotency result;
8. commit.

If any required step fails, no partial authoritative transfer remains.

## Economic ledger

Maintain append-oriented evidence for important value movements with fields such as:

- operation ID
- actor/player/system account
- asset type/definition/instance
- quantity/amount
- direction/source/destination
- reason
- related entity (order, listing, craft, Map run, bounty contract, war, project, etc.)
- timestamp

The ledger is audit/reconstruction evidence, not necessarily the primary balance table.

## Fixed-point currency

Coins use fixed-point integers, never floating point.

Balances cannot become negative. Escrowed/consumed currency is no longer available to spend elsewhere.

## Bank Manager

### Deposit / withdraw

Pocket <-> bank movement is one atomic transfer with capacity validation.

### Interest

Interest is an explicit Coin faucet with one stable period/eligibility key. The same eligible period cannot credit twice because of retry, reconnect, or multiple backends.

### PvE pocket loss

Death loss is an explicit sink applied only to the configured spendable balance. Simultaneous deposit/spend/death-loss operations serialize through authoritative balance/version rules so the same Coin cannot be both protected and destroyed/spent.

## Bazaar

### Sell order
Commodities move from player/container ownership into Bazaar escrow when the order is created. Fills consume only escrowed quantity.

### Buy order
Maximum required funds move into Bazaar escrow when the order is created. Fills consume reserved funds and cancellation returns only the unused remainder.

### Matching
Deterministic price-time priority:

- sells: lowest price, then oldest;
- buys: highest price, then oldest.

No hidden target-price logic.

### Fill/cancel race
Exactly one serialized database outcome decides which quantity was filled versus returned. Seller cannot receive refunded commodity that the buyer also receives.

## Auction House

An individualized item leaves player control when listed and enters Auction custody.

Purchase atomically transfers:

- buyer currency to seller settlement;
- item ownership to buyer/pending delivery;
- listing to sold state;
- fee/ledger/provenance/idempotency records.

Cancel/purchase races have one winner. One item instance cannot be returned to seller and sold to buyer.

## Secure trade

Offer changes invalidate previous confirmation. Once both sides lock/confirm, settlement moves both sides' value atomically.

Cancellation returns escrow only according to defined pre-settlement rules.

## Pending delivery

A completed transaction must not fail merely because physical Minecraft inventory has no slot or the player is offline.

Already-owned results can land in durable pending delivery and be claimed/rendered later.

Pending delivery is a correctness buffer, not a general mail/economic storage system.

## Crafting / rolled output

A craft is one atomic consumption/result operation.

For individualized rolled gear:

- inputs are consumed once;
- one unique output ID is created once;
- normalized roll quality is generated authoritatively once and persisted;
- a duplicate/retried request returns the same result rather than rerolling another item;
- provenance links the output to the committed craft operation.

Crafting must compete safely with Bazaar/transfer/storage operations for the same commodity quantities.

## Upgrade / salvage

Upgrade:

- consumes configured inputs/Coins once;
- modifies only allowed upgrade state/version;
- never rerolls intrinsic item quality unless a separately designed mechanic explicitly says so.

Salvage:

- consumes/destroys the exact unique item once;
- creates configured output once;
- cannot race with AH listing/trade/equip/storage and succeed twice.

## Map open / run settlement

### Open
One Map item may create at most one valid persistent run.

Opening atomically moves/consumes the Map out of tradeable ownership and creates the run/source record.

Open versus trade/AH/storage races must have one winner.

### Completion
A qualifying run completion settles at most once:

- persistent run terminal state;
- configured rewards/materials/Map outputs;
- clear/history record where applicable;
- ledger/provenance/idempotency evidence.

A failed run cannot silently restore the source Map unless an explicit refund policy is separately designed and transactionally enforced.

## Bounty contract / summon / reward

### Contract start
Contract fee is an explicit Coin sink tied to one contract-start operation.

### Kill progress
Eligible kill progress is deduplicated/serialized so the same authoritative gameplay event cannot increment twice.

### Summon
One configured summon authorization can produce only the allowed number of valid boss attempts. Authorization consumption and encounter creation are atomic/idempotent.

### Boss completion
Boss reward/material creation settles once against persistent contract/attempt state. Surviving/despawned runtime entities are not settlement authority.

## Clan treasury / shared storage

Clan-controlled value uses the same custody invariants as personal value.

- role/capability permission is validated inside the authoritative operation;
- concurrent withdrawals cannot overspend/duplicate;
- role change/kick/leave races cannot move the same asset twice;
- a unique item cannot be simultaneously clan-owned and personally owned/listed.

## Commissions

Requester materials/payment enter commission escrow before a worker can settle the job.

On valid completion:

- materials are consumed;
- result goes to requester/pending delivery;
- commission payment goes to worker;
- job becomes completed exactly once.

## Expansion voting / feature actions

Ballots are not economic value but use the same idempotency/uniqueness discipline.

- one configured effective ballot per uniqueness key;
- stale candidate-set version rejected;
- one vote resolves at most once;
- feature/world-era actions tied to resolution apply at most once;
- retry cannot create duplicate ballots, alternate winners, or repeated unlock actions.

Physical district blocks are not vote-result authority.

## Explicit Community Project contributions

Only projects intentionally configured to accept contributions use this lifecycle.

A contribution transaction removes value from the player and increments project contribution/history exactly once.

Ordinary voted districts do not automatically consume materials into hidden project progress.

## War custody

Real economic loadouts enter explicit custody/snapshot before the disposable match runtime uses them.

Final settlement applies consumed ammunition/potions, durability changes, returned equipment, and rewards exactly once.

A crashed match instance must not duplicate authoritative original value.

## Historical rewards

Exactly-once entitlements should be protected by database uniqueness such as the logical equivalent of:

`UNIQUE(event_id, player_id, reward_id)`

Retries, duplicate messages, or staff UI mistakes cannot create a second authentic entitlement.

## Unique-item duplicate detection

A unique `item_instance_id` has one authoritative location.

If two live Minecraft representations claim the same ID:

1. persistent authority determines legitimate ownership/location;
2. conflicting representation is rejected/quarantined;
3. an audit event is recorded;
4. never silently accept both.

## Commodity conservation checks

For important commodities, periodic accounting can compare:

`legitimate creation - legitimate consumption = held + escrowed + pending/other authoritative custody`

within exactly defined accounting boundaries.

The goal is early anomaly detection, not per-dirt-block forensic overhead.

## Legitimate creation/destruction sources

Economically meaningful creation reasons include:

- authorized gathering;
- mob/Map/Bounty reward;
- crafting/refining output;
- war/event reward;
- controlled NPC/bootstrap source;
- bank interest;
- audited recovery.

Consumption reasons include:

- crafting/refining/upgrade input;
- consumable use;
- salvage sink;
- bounty contract fee;
- PvE pocket death loss;
- market/service fee;
- explicit project contribution;
- durability destruction;
- Map opening where the Map item is consumed.

Every faucet/sink family should be observable enough to reconcile server-wide flow.

## Cross-backend dupe prevention

Single-writer leases plus state versions fence stale backends.

A source backend that has released ownership cannot later restore an old inventory snapshot over newer authoritative state.

Globally transactional systems (market/bank/Map/Bounty/vote/war) still use their own PostgreSQL operation boundaries and do not rely solely on the player's session lease.

## Backup/rollback warning

Naively restoring an old database while keeping newer persistent worlds or vice versa can manufacture duplicate value/history or revert legitimate votes/features.

Backup/restore must use coherent recovery boundaries; see `FAILURE_RECOVERY.md`.

## Hard rule

No economically valuable or world-authoritative operation may depend on timing, duplicate-message absence, server ordering, surviving entities, UI state, or player honesty for correctness.