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

`CoinFlowAnalyticsRepository` reads authoritative `processed_operations`, append-only Coin `economic_ledger` evidence, and salvage evidence. It writes no analytics state.

A raw Coin-ledger operation net is **not** automatically a faucet or sink. Several valid custody systems move Coin into durable escrow in one operation and return/settle that escrow in another. For example, creating a Bazaar buy order debits the player's wallet now, but the Coin still exists in buy-order escrow; treating that debit as destruction would be wrong.

The projection therefore classifies the stable V1 Coin-bearing operation types explicitly:

- confirmed faucets include controlled system credit, Bank interest, and configured salvage Coin return;
- confirmed sinks include controlled system debit, Bank tier upgrade cost, Bounty contract fee, and Bazaar execution fees;
- player transfer, Bank deposit/withdrawal, Bazaar buy escrow/cancel, Auction House purchase, secure-trade Coin escrow/settlement/cancel, crafting-commission payment escrow/cancel/completion, and clan-treasury deposit/withdrawal are classified as neutral custody/movement;
- Bazaar execution fee is derived from the append-only `BAZAAR_MATCH` processed result field `fees_destroyed_minor`, which the matcher commits after summing its fills; it is not inferred from the match operation's wallet-credit net;
- any current/future Coin-bearing operation that is unknown, missing processed-operation identity, or has malformed/mismatched faucet/sink evidence is **unclassified**, never guessed.

For a requested time window the projection returns:

- observed-through timestamp, clipped to the current time;
- **confirmed** Coin created;
- **confirmed** Coin destroyed;
- confirmed net supply change;
- total gross Coin-ledger movement;
- classified operation count;
- unclassified operation count and its gross movement;
- a bounded reason breakdown for classified operations;
- explicit reason-list truncation;
- `supplyClassificationComplete()`, which is true only when the unclassified operation count is zero.

Therefore confirmed faucet/sink totals may still be useful when coverage is incomplete, but they must not be presented as the complete world Coin-supply change unless `supplyClassificationComplete()` is true.

Totals and reason rows are read in one read-only repeatable-read snapshot, so concurrent economic activity cannot make one response internally disagree. PostgreSQL aggregate arithmetic uses `NUMERIC` and Java uses `BigInteger`, so long-lived aggregate metrics are not constrained by one wallet's `BIGINT` balance range.

Normal authority operations carry one stable reason. If unusual evidence puts multiple reasons on one Coin operation, the ledger-side observation uses the synthetic `<mixed>` reason rather than splitting one economic operation into several fake supply events. A fee-only Bazaar match with no Coin credit line still has the stable fallback reason `bazaar.match.fee`.

The PostgreSQL integration proof uses real authority operations and covers both sides of the escrow distinction:

- controlled system credit -> confirmed supply creation;
- balanced player transfer -> zero supply change with non-zero gross movement;
- controlled system debit -> confirmed supply destruction;
- real Bazaar buy-order escrow debit -> zero supply destruction with non-zero gross movement;
- a real crossed Bazaar match at the test 1% fee -> `198` minor units of seller wallet credit/gross movement while exactly `2` minor units are classified as destroyed supply;
- future, not-yet-observed windows -> zero flow.

## Implemented Bazaar market baseline

`BazaarMarketAnalyticsRepository` is a read-only projection over authoritative current `bazaar_orders` plus append-only `BAZAAR_MATCH` processed-operation results. It writes no analytics event, price cache, order copy, fill copy, or secondary order-book state.

For one commodity it returns a **current book snapshot** containing:

- best bid and best ask price when that side exists;
- remaining quantity at the best bid and best ask price;
- open buy-order and sell-order counts;
- total remaining buy and sell quantity;
- nullable quoted spread (`best ask - best bid`), which may be negative if the current book is crossed.

The projection does not attach a policy interpretation to spread or depth. It does not define a healthy spread, target volume, liquidity score, or tuning threshold.

For a requested execution-history window it separately returns:

- observation-fenced match-pass count;
- fill count;
- filled commodity quantity;
- gross trade value;
- Bazaar fees destroyed.

Historical execution is derived from the frozen aggregate fields persisted by each append-only `BAZAAR_MATCH` processed result: commodity ID, fill count, filled quantity, gross trade value, fee destruction, and operation completion time. It deliberately does not try to reconstruct matcher batches from per-fill delivery identities.

The current order-book snapshot and the historical execution window are intentionally different time concepts. A future or not-yet-observed history window therefore reports zero observed execution while the current book can still be visible; current depth must never be presented as if it were historical depth.

The PostgreSQL integration proof uses real Bazaar authority paths and demonstrates:

- exact two-sided book state with multiple price levels, including best-level and total remaining depth;
- a real crossed trade at the test 1% fee with one fill, quantity `2`, gross trade value `200`, and fee destruction `2`;
- fully filled orders disappearing from current open-book depth while their append-only matcher result remains available to history analytics;
- a future execution-history window returning zero observed execution without hiding current open orders;
- temporary sell-order sessions being explicitly disconnected, while valid append-only market/economic evidence remains immutable rather than being deleted for test cleanup.

This is the V1 market-microstructure baseline. More elaborate price-series, slippage curves, volatility measures, or dashboards should be added only when a concrete product question requires them.

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

Confirmed Coin faucet/sink analytics already derives from existing operation/ledger evidence. Do not add duplicate `COIN_FAUCET`/`COIN_SINK` rows merely to reproduce facts that are already durable. Likewise, current Bazaar depth and aggregate fill quantity/value/fees already derive from `bazaar_orders` plus append-only `BAZAAR_MATCH` results; do not persist duplicate `BAZAAR_FILL` analytics rows merely to recreate those facts. A separate analytics event is justified only when it adds observational context the authoritative operation does not preserve.

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

The implemented Coin-flow projection answers confirmed creation/destruction/net supply change plus gross movement by stable reason and reports whether classification coverage is complete. It deliberately does not infer supply from one-sided escrow ledger entries.

The implemented Bazaar market projection now answers current best bid/ask/spread, best-level and total open depth, and observation-fenced matcher/fill quantity/value/fee activity per commodity. Holdings distribution, commodity creation/consumption, Auction-House price distributions, wealth concentration, and richer market behavior such as slippage/volatility remain separate questions.

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
- Coin-flow analytics combines stable operation type with Coin ledger/custody-specific evidence; raw ledger net alone is not global supply authority;
- Bazaar order rows are current market authority and append-only `BAZAAR_MATCH` processed results are frozen execution summaries; Bazaar market analytics is a read-only projection of those sources;
- Map clear records are authoritative leaderboard/history source;
- analytics about Map clears is observational;
- ballots/vote resolution are authoritative world-state source;
- analytics about participation is observational.

## Privacy/data minimization

Store only data needed to operate, secure, debug, and improve the game. Avoid unnecessary personal data in analytics records.

The implemented session, Coin-flow, and Bazaar-market summaries return aggregate counts/time/value/depth only; they do not expose player names, Minecraft UUIDs, or serialized player state.

## Expansion rule

Add instrumentation when it answers a concrete question. Prefer deriving from existing durable authority when that is lossless. Do not build an analytics platform for hypothetical future dashboards.
