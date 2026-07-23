# Analytics and Domain Events

## Product metric

Primary optimization target:

**repeatable/retained player-hours generated per developer-hour and recurring maintenance cost**.

Analytics exists to remove uncertainty about what actually creates/loses player time and where system complexity is justified.

## Initial implementation

Use PostgreSQL plus a clean structured application-event model. No Kafka/event-streaming platform is required for V1.

## Event examples

- `PLAYER_SESSION_STARTED`
- `PLAYER_SESSION_ENDED`
- `ZONE_ENTERED`
- `ZONE_LEFT`
- `INSTANCE_CREATED`
- `INSTANCE_RETIRED`
- `RESOURCE_GATHERED`
- `SKILL_XP_GAINED`
- `ITEM_CRAFTED`
- `ITEM_REFINED`
- `ITEM_CONSUMED`
- `BAZAAR_ORDER_CREATED`
- `BAZAAR_FILL`
- `AH_SALE`
- `TRADE_COMPLETED`
- `NPC_SALVAGE`
- `BOOTSTRAP_PURCHASE`
- `PVP_MATCH_COMPLETED`
- `WAR_COMPLETED`
- `PROJECT_CONTRIBUTION`
- `PROJECT_COMPLETED`
- `FEATURE_UNLOCKED`
- `HISTORICAL_REWARD_ISSUED`
- `ITEM_CONFLICT_QUARANTINED`

## Questions the data should answer

### Retention/player-hours
- which activities produce repeat sessions?
- where do new players stop?
- how long do sessions/activity segments last?
- which systems are used together?

### Zone/instance scaling
- concurrent players by zone
- resource/mob contention indicators
- instance count by zone over time
- soft-cap violations and idle-instance waste
- when a zone genuinely needs different capacity/template design

### Progression
- skill participation and progression distribution
- common goal/order paths without assuming there is a correct one
- resource throughput by progression band
- whether a skill becomes accidentally mandatory or irrelevant

### Economy
- trade volume/liquidity/spread
- supply creation/consumption by source
- NPC salvage/bootstrap usage
- resource sinks/faucets
- suspicious sudden supply or currency creation

### Social/competition
- clan participation
- ranked PvP participation/repeat rate
- war participation/resource consumption
- project contribution breadth/depth

### Development leverage
For a feature/content addition, compare implementation/maintenance cost with resulting repeatable player-hours and interaction across existing systems.

## Event design

Events use stable IDs/references rather than display names.

Include only data needed for product/operational analysis and debugging. Do not log giant serialized player snapshots or every low-value hot-path event if aggregates are sufficient.

## Economic ledger versus analytics

The economic ledger is correctness/audit evidence for important value movement. Analytics events are product/behavior observations.

They may reference the same operation IDs but should not be conflated into one purpose.

## Privacy/data minimization

Store only data needed to operate, secure, debug, and improve the game. Avoid unnecessary personal data in analytics records.

## Expansion rule

Add instrumentation when it answers a concrete question. Do not build an analytics platform for hypothetical future dashboards.
