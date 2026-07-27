# Analytics and Domain Events

## Product metric

Primary optimization target:

**repeatable/retained player-hours generated per developer-hour and recurring maintenance cost**.

Analytics exists to remove uncertainty about what actually creates/loses player time and where system complexity is justified.

Analytics does **not** steer legitimate player outcomes. It informs tuning, capacity, bug investigation, and future content investment.

## Initial implementation

Use PostgreSQL plus small read-only projections and, only where needed, a clean structured application-event model. No Kafka/event-streaming platform is required for V1.

Do not duplicate a fact into an analytics event merely because it is useful analytically. If an authoritative durable table already represents the fact losslessly, analytics should derive from that table. Add observational events when the question cannot be reconstructed cleanly from existing authority or when an aggregate/sampled observation is intentionally cheaper than retaining hot-path detail.

Correctness-critical evidence always remains in the owning subsystem's authority/ledger rather than relying on analytics.

## Implemented session baseline

`SessionAnalyticsRepository` reads authoritative `player_sessions` only and writes no analytics state.

For any requested activity window it returns:

- observed-through timestamp, clipped to the current time for an in-progress window;
- unique active players;
- new players whose first known network session begins in the observed part of the window;
- returning players whose first known network session predates the window;
- network sessions started;
- network sessions ended;
- total observed player-seconds, using exact overlap with the window rather than assigning a whole cross-boundary session to one day.

It also exposes cohort retention for any completed first-session cohort and later non-overlapping return window:

- cohort membership is based only on each player's first-ever network session;
- the cohort window must already be fully observed;
- the return window may still be in progress and is clipped at the current observation time;
- a player counts as returned only when a separate network session **starts** in the return window;
- one unusually long initial session crossing into the return window therefore cannot fake retention;
- zero-size cohorts return no synthetic percentage rather than pretending retention is `0%`.

This gives the first direct denominator and retention signal for the product metric without creating a second session lifecycle. A currently active/otherwise not-yet-ended session can contribute only already-observed time, never future player-hours.

The PostgreSQL integration proof covers new versus returning players, a session crossing into the window, a session extending beyond the observation cutoff, completed historical windows, future windows with zero observed time, partial cohort-return observation, and the long-first-session retention edge case.

## Implemented Coin-flow baseline

`CoinFlowAnalyticsRepository` derives currency supply/movement directly from append-only `economic_ledger` Coin lines and writes no analytics state.

The important unit is the **economic operation**, not one ledger line. Coin lines belonging to one operation are netted first:

- positive operation net = Coin created by that operation;
- negative operation net = Coin destroyed by that operation;
- zero operation net = internal movement with no supply change;
- gross movement remains the sum of every Coin line and is reported separately from supply impact.

This means a normal player transfer, Bank deposit, or Bank withdrawal can move a large amount of Coin while contributing exactly zero faucet/sink volume. Bank interest, controlled system rewards, upgrade fees, contract fees, death loss, and similar one-sided economic effects remain visible as actual supply change when their authoritative operation produces a non-zero net.

For a requested time window the projection returns:

- observed-through timestamp, clipped to the current time;
- total Coin created;
- total Coin destroyed;
- net Coin supply change;
- gross Coin movement;
- number of Coin-bearing economic operations;
- a bounded reason breakdown with the same created/destroyed/net/gross distinction and operation counts;
- an explicit `reasonsTruncated` signal if the requested reason limit does not contain the full breakdown.

Totals are never truncated when the reason list is bounded. The totals and reason rows are read in one read-only repeatable-read snapshot, so concurrent economic activity cannot make one response internally disagree. PostgreSQL aggregate arithmetic uses `NUMERIC` and the Java projection uses `BigInteger`, so long-lived aggregate metrics are not constrained by one wallet's `BIGINT` balance range.

Normal authority operations carry one stable reason. If corrupted/unusual evidence puts multiple reasons on one Coin operation, analytics keeps the operation net intact and groups that operation under the synthetic `<mixed>` reason rather than misclassifying individual lines as independent faucets/sinks.

The PostgreSQL integration proof uses real `CoinWalletRepository` operations and demonstrates:

- controlled system credit -> positive supply creation;
- balanced player transfer -> zero created/destroyed supply with non-zero gross movement;
- controlled system debit -> positive supply destruction;
- future, not-yet-observed windows -> zero flow.

## Event examples

These are the stable event vocabulary that may be useful where an event is actually needed. Inclusion here does not mean every example must be persisted if the same question can be derived from existing authority.

### Session / topology
- `PLAYER_SESSION_STARTED`
- `PLAYER_SESSION_ENDED`
- `ZONE_ENTERED`
- `ZONE_LEFT`
- `INSTANCE_CREATED`
- `INSTANCE_RETIRED`

The implemented session baseline deliberately derives start/end/player-time/retention metrics from `player_sessions`; it does not persist duplicate `PLAYER_SESSION_STARTED`/`PLAYER_SESSION_ENDED` analytics rows.

### Progression / production
- `RESOURCE_GATHERED`
- `SKILL_XP_GAINED`
- `SKILL_CAP_REACHED`
- `ITEM_CRAFTED`
- `ITEM_ROLL_GENERATED`
- `ITEM_UPGRADED`
- `ITEM_REFINED`
- `ITEM_SALVAGED`
- `ITEM_CONSUMED`

### Currency / markets
- `COIN_FAUCET`
- `COIN_SINK`
- `BANK_DEPOSITED`
- `BANK_WITHDRAWN`
- `BANK_INTEREST_CREDITED`
- `PVE_POCKET_LOSS`
- `BAZAAR_ORDER_CREATED`
- `BAZAAR_FILL`
- `BAZAAR_ORDER_CANCELLED`
- `AH_LISTING_CREATED`
- `AH_SALE`
- `AH_LISTING_CANCELLED`
- `TRADE_COMPLETED`
- `NPC_SALVAGE`
- `BOOTSTRAP_PURCHASE`

Coin faucet/sink totals already derive losslessly from the economic ledger. Do not add duplicate `COIN_FAUCET`/`COIN_SINK` analytics rows merely to reproduce those totals. A separate event is justified only if it adds observational context the authoritative operation/ledger does not preserve.

### Maps / PvE
- `MAP_OPENED`
- `MAP_RUN_STARTED`
- `MAP_RUN_COMPLETED`
- `MAP_RUN_FAILED`
- `MAP_CLEAR_RECORDED`
- `MAP_ITEM_GENERATED`
- `MAP_MATERIAL_CREATED`

### Bounties
- `BOUNTY_CONTRACT_STARTED`
- `BOUNTY_KILL_PROGRESS`
- `BOUNTY_SUMMON_READY`
- `BOUNTY_BOSS_SUMMONED`
- `BOUNTY_COMPLETED`
- `BOUNTY_FAILED`
- `BOUNTY_MATERIAL_CREATED`

### Social / competition / world
- `CLAN_CREATED`
- `CLAN_TREASURY_MUTATED`
- `CLAN_STORAGE_MUTATED`
- `PVP_MATCH_COMPLETED`
- `WAR_COMPLETED`
- `EXPANSION_VOTE_OPENED`
- `EXPANSION_BALLOT_CAST`
- `EXPANSION_VOTE_RESOLVED`
- `FEATURE_UNLOCKED`
- `WORLD_ERA_STARTED`
- `PROJECT_CONTRIBUTION`
- `PROJECT_COMPLETED`
- `HISTORICAL_REWARD_ISSUED`
- `CHRONICLE_EVENT_RECORDED`

### Integrity / operations
- `ITEM_CONFLICT_QUARANTINED`
- `DUPLICATE_OPERATION_REJECTED`
- `STALE_WRITE_REJECTED`
- `RECOVERY_ACTION_EXECUTED`

Not every low-value hot-path event must be persisted individually forever. Use aggregated counters/sampling where appropriate, but correctness-critical evidence remains in the subsystem's authoritative ledger/state rather than relying on analytics.

## Questions the data should answer

### Retention/player-hours
- which activities produce repeat sessions?
- where do new players stop?
- how long do sessions/activity segments last?
- which systems are used together?
- which content creates repeatable player-hours relative to implementation/maintenance cost?

The implemented session projection directly answers total player-time, new/returning participation, and arbitrary D1/D7-style first-session cohort retention without additional event storage. Activity attribution and cross-system path analysis should be added only when their required underlying observations are explicitly defined.

### Zone/instance scaling
- concurrent players by zone
- resource/mob contention indicators
- instance count by zone over time
- soft-cap violations and idle-instance waste
- Map/encounter concurrency by backend
- when a zone genuinely needs different capacity/template/process isolation

### Progression
- skill participation and progression distribution
- time to active cap 50 by skill
- later 50->75 and 75->100 progression pace
- common goal/order paths without assuming there is a correct one
- specialization breadth/depth by account age
- resource throughput by progression band
- whether a skill becomes accidentally mandatory or irrelevant

### Rolled gear / crafting
- craft volume by item definition
- roll-quality distribution versus configured profile
- median/high/perfect-roll market prices
- material consumption per crafted sellable item
- salvage volume of unwanted rolls
- whether perfect-roll chasing creates healthy sinks or pathological resource pressure

### Economy
- total Coin created/destroyed per reason
- pocket versus bank versus escrow distribution
- bank interest issued
- PvE pocket loss destroyed
- market volume/liquidity/spread
- bounty contract fee sink
- supply creation/consumption by source
- NPC salvage/bootstrap usage
- suspicious sudden supply/currency creation
- wealth concentration and transaction velocity

The implemented Coin-flow projection directly answers total created/destroyed/net supply change and gross movement by stable reason without a second currency ledger. Holdings distribution, market microstructure, commodity supply, and wealth-distribution questions remain separate projections.

### Maps
- run starts/completions/failures by difficulty/configuration
- clear-time distribution
- solo/group practical ceiling
- farm difficulty versus push difficulty behavior
- environment/enemy/objective/modifier participation
- Map-item generation/supply and AH liquidity
- Map material creation/consumption
- pre/post major-power-era clear distributions

### Bounties
- contracts started/completed/failed by family/tier
- boss success/failure rates
- Coin sink by family/tier
- material creation/consumption
- Bazaar liquidity/prices by material grade
- family-specialized gear usage
- whether category specialization actually creates distinct player roles

### Social/competition
- clan participation and size distribution
- treasury/storage activity
- ranked PvP participation/repeat rate
- war participation/resource consumption
- expansion-vote participation
- candidate/result distributions without treating any valid winner as preferred
- explicit project contribution breadth/depth where projects are used

### World/history
- vote/feature/world-era chronology
- first significant Map clears by era
- Chronicle event frequency/types
- whether new districts/expansions create sustained activity in older systems

### Development leverage
For a feature/content addition, compare implementation/maintenance cost with resulting repeatable player-hours and interaction across existing systems.

## Event design

Events use stable IDs/references rather than display names.

Include only data needed for product/operational analysis and debugging. Do not log giant serialized player snapshots or every low-value hot-path event if aggregates are sufficient.

Map/Bounty/world events should reference authoritative run/contract/vote/feature IDs rather than duplicating freeform state.

## Economic ledger versus analytics

The economic ledger is correctness/audit evidence for important value movement. Analytics is a read-only interpretation/projection of that evidence where possible, and separate observational events only where necessary.

Similarly:

- network session rows are session ownership/lifecycle authority; session analytics is a read-only product projection;
- the economic ledger is Coin/value movement authority/evidence; Coin-flow analytics nets its Coin lines by operation without becoming another currency ledger;
- Map clear records are authoritative leaderboard/history source;
- analytics about Map clears is observational;
- ballots/vote resolution are authoritative world-state source;
- analytics about participation is observational.

## Privacy/data minimization

Store only data needed to operate, secure, debug, and improve the game. Avoid unnecessary personal data in analytics records.

The implemented session and Coin-flow summaries return aggregate counts/time/value only; they do not expose player names, Minecraft UUIDs, or serialized player state.

## Expansion rule

Add instrumentation when it answers a concrete question. Prefer deriving from existing durable authority when that is lossless. Do not build an analytics platform for hypothetical future dashboards.
