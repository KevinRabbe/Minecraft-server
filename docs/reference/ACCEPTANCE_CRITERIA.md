# V1 Acceptance Criteria

V1 is accepted only when the complete player/economy loop survives normal use and deliberate failure without duplication or authority corruption.

## A. Join and persistent identity

A fresh player can:

1. connect through Velocity;
2. receive stable network identity/session;
3. enter the starter region;
4. disconnect/reconnect without duplicate player records or state loss beyond the documented checkpoint window.

## B. Compact zone routing/instancing

For at least Woodcutting, Mining, Farming, and one PvE zone:

1. player requests/enters the logical zone;
2. router selects an existing suitable instance;
3. additional instances are created/opened only when that zone's concurrent demand requires them;
4. players elsewhere on the network do not affect the zone's instance count;
5. empty resettable instances can retire/recreate safely;
6. progression/economy is identical across equivalent copies;
7. player does not need to know backend/instance infrastructure IDs.

## C. Authorized gathering and progression

For each launch gathering skill:

1. valid source generates configured resources/XP once;
2. player-placed/replayed/invalid sources do not mint XP/value;
3. skill benefit and XP eligibility are separately enforceable;
4. use requirements block use without blocking ownership/trading;
5. checkpoint/reconnect preserves committed progression.

## D. Cross-backend state transfer

With at least two Paper backends available:

1. player state has exactly one active writer;
2. source freezes/commits and increments state version;
3. target claims ownership through valid transfer ticket;
4. stale source write is rejected;
5. interruption/retry cannot duplicate inventory/Coins/XP;
6. unavailable target routes player to valid fallback/retry path.

## E. Item identity and inventory

1. stackable commodities use quantity accounting rather than per-unit identity;
2. selected unique/non-fungible item receives stable `item_instance_id`;
3. same instance ID cannot become authoritative in two locations;
4. malformed/conflicting representation is rejected/quarantined rather than silently accepted;
5. full inventory can receive transaction result through pending delivery without rollback/duplication.

## F. Economy

### Wallet
- fixed-point integer arithmetic only
- no negative spendable balance
- escrowed funds cannot be double-spent

### Bazaar
- buy/sell order escrow
- deterministic price-time matching
- partial fills
- cancellation returns only remaining escrow
- offline settlement
- retrying a committed fill does not fill twice

### Auction House
- listing removes item from player control
- purchase atomically moves Coin/item ownership
- cancel/sale is exactly once

### Secure trade
- offer changes clear confirmation
- both sides settle atomically
- cancellation/retry cannot duplicate value

### Salvage/bootstrap NPC
- configured source/sink works
- no trivial NPC/recipe/compression arbitrage loop

## G. Enchanting and brewing

1. normal Minecraft XP is the operational enchanting resource;
2. Enchanting skill amplifier applies to configured other-skill XP only;
3. Enchanting cannot amplify itself;
4. bootstrap brewing inputs keep brewing functional before Nether unlock;
5. bootstrap supplier does not require Nether backend/access to exist at launch.

## H. Clans and leaderboards

1. clan create/invite/leave/kick/roles persist across reconnect/backends;
2. War Rating and skill XP produce deterministic global ranking;
3. Top-N cached view and personal rank can be read without rebuilding the entire ranking on every request;
4. town prestige display can use an older valid snapshot during transient read failure.

## I. Ranked PvP

1. opt-in player enters isolated match instance;
2. standardized temporary loadout replaces economic advantage;
3. normal persistent inventory is not mutated by match inventory;
4. result/rating applies exactly once;
5. match instance can be discarded safely.

## J. Clan war

1. challenge/accept/roster lifecycle works;
2. real economic loadout enters explicit custody/snapshot;
3. disposable match runtime cannot duplicate original gear;
4. configured consumable/durability effects settle once;
5. War Rating/history updates once;
6. deliberate match-backend failure follows defined recovery/abort policy without duplication.

## K. Community project/history

1. material contribution removes player value and increases global project history once;
2. project progress is the same regardless of City/zone instance;
3. controlled build region permissions work;
4. completion creates versioned archive metadata/checksum;
5. historical entitlement/reward can be issued only once per qualifying player;
6. completion can execute generic `ENABLE_FEATURE(feature_id)` action;
7. Nether remains inaccessible before its feature unlock.

## L. Failure tests

Deliberately test:

- Paper crash during ordinary play
- Paper crash during/around transfer
- repeated transfer request/ticket replay
- database response lost after successful transaction commit
- duplicate transaction request
- player disconnect during transaction-safe and unsafe states
- Velocity restart
- PostgreSQL temporary unavailability
- war-instance failure
- restart with empty/active resettable instances

No test may produce duplicate persistent value or two valid player writers.

## M. Backup/restore

Before public alpha:

1. create documented backup of PostgreSQL + persistent City/project archives/config versions;
2. restore into a disposable environment;
3. verify player identity/inventory/wallet/skills/markets/clans/projects/history;
4. verify world/project state corresponds to the chosen database recovery point;
5. run at least a subset of economy/transfer acceptance tests after restore.

## N. Local-PC-first operational test

The Windows development deployment must be startable/stoppable/recoverable without rented hosting. Moving to rented capacity later must not require changing gameplay identities or persistent data semantics.
