# Clans, Ranked PvP, and Clan War

Status: **Canonical architecture policy.** The persistent MMO and competitive PvP are intentionally separate game categories. Ranked Arena and Clan Wars use the isolated **1.8.9 competitive category**; the persistent MMO uses the selected late-1.21.x platform.

## Clans

Launch clan functions include:

- create clan;
- unique name/tag;
- invite;
- leave/kick;
- Leader/Officer/Member role model;
- clan chat;
- roster;
- configurable member cap;
- treasury;
- shared storage with explicit permissions;
- Clan-War rating/history;
- global Clan-War leaderboard.

No open-world land destruction is required.

## Emergent organization

The game does not assign hard professions/classes to clan members.

Large clans may choose to organize members as miners/foragers/farmers, Map pushers, bounty specialists, crafters, traders, builders/logistics roles, or competitive players.

Their advantage comes from division of labor, specialization, capital allocation, market knowledge, and coordination rather than hidden clan-only production multipliers. Solo/small-group specialists remain economically relevant because resources/materials/gear remain tradable through the wider market.

## Clan treasury

The treasury is explicit clan-controlled Coin custody.

Rules:

- deposits/withdrawals are authoritative transactions;
- role/capability permissions control withdrawal/spending;
- concurrent withdrawal cannot overspend;
- treasury funds are separate from personal pocket/bank balances;
- no role change/kick/leave race may duplicate or ambiguously reassign treasury Coins;
- important mutations are auditable.

The treasury may fund Bazaar/AH purchases, bounty costs, infrastructure, crafting inputs, war costs, or future systems only through their normal transaction interfaces.

## Shared storage

Clan storage may hold configured commodities and individualized items.

Authority rules:

- any member may deposit unless a later explicit permission policy says otherwise;
- Leader/Officer may withdraw under the current default role policy;
- commodity deposits prove the exact serialized player-state removal before clan quantity is credited;
- individualized deposits prove exact item identity/version and move custody `PLAYER_INVENTORY -> CLAN_STORAGE`;
- withdrawals never write value directly into a live Minecraft inventory;
- commodity withdrawals create normal durable pending commodity delivery;
- individualized withdrawals move `CLAN_STORAGE -> PENDING_DELIVERY` and create the normal unique-item delivery record;
- simultaneous withdrawals/transfers cannot duplicate quantity/items;
- leave/kick does not automatically convert clan-owned assets into personal assets;
- economic ledger/provenance evidence accompanies value movement;
- the global integrity verifier reconciles clan commodity quantities and unique-item custody against economic evidence.

Presentation may later impose physical/logistical storage constraints, but presentation must not weaken the authority model.

## Competitive-category boundary

The 1.8.9 competitive category is intentionally isolated from the modern persistent MMO category.

Shared durable identities may include `PlayerId`, `ClanId`, Chronicle references, ratings/history, and explicitly custodied economic assets. The competitive runtime does **not** become a second authority for MMO inventory, economy, or item identity.

This separation means the network does not need to make 1.8.9 faithfully represent the complete late-1.21.x MMO world or inventory model.

## Ranked Arena — 1.8.9

Ranked Arena is explicit opt-in competitive PvP using standardized temporary state.

V1 contract:

- 1v1;
- simple symmetric map;
- 1.8.9 competitive runtime;
- standardized temporary loadout;
- permanent MMO skill/equipment advantages disabled;
- no persistent MMO inventory enters match authority;
- temporary runtime inventory is disposable;
- one live ranked match per player;
- durable Elo-style rating progression and separate competitive leaderboard;
- ruleset and rating-policy context are frozen on match creation;
- match completion and rating settlement are one exactly-once durable transaction;
- immutable result evidence preserves participant, rating-before/after, ruleset, rating-policy and timestamp context.

The exact rating constants are configuration/tuning. A deployment changing those constants cannot retroactively alter an already-created match because the policy version and required settlement parameters are persisted with that match.

### Ranked lifecycle

```text
CREATED -> ACTIVE -> COMPLETED
   |          |
   +------> CANCELLED
```

A terminal match releases both players for future matches. Terminal match identity/result context is immutable.

### Ranked trust boundary

The disposable 1.8.9 runtime reports the outcome through trusted internal match infrastructure. Clients never write ratings directly. If/when match execution crosses an authenticated service boundary, result submission must bind to the authorized match/runtime identity; do not weaken PostgreSQL authority to accommodate runtime routing.

## Clan-War leaderboard

Clan-War competitive ranking is separate from Ranked Arena and from persistent-MMO/PvE prestige.

Primary competitive score may remain Clan-War Rating unless later data justifies another explicit board. Rank provides prestige/visibility/history, not required mechanical power.

See `LEADERBOARD_CATEGORIES.md` for the cross-category separation rule.

## Clan War — 1.8.9

Clan War is a different product from standardized Ranked Arena.

It intentionally allows explicitly selected real economic gear/consumables to influence the match so war can create demand for peaceful producers/traders. The 1.8.9 backend still never owns the persistent items themselves.

Planned V1 lifecycle:

1. challenge;
2. accept;
3. roster lock;
4. loadout/value custody;
5. derive temporary combat snapshots;
6. isolated 1.8.9 war instance;
7. one simple objective/control-point mode;
8. deterministic result;
9. exactly-once economic settlement/return;
10. Clan-War Rating/history update.

## War loss model

No uncontrolled full-loot PvP.

Defined economic effects may include durability changes, consumed potions/ammunition, configured entry/supply costs, and configured rewards. Equipment ownership/return is settled explicitly.

## War custody

Real persistent value must cross the MMO/competitive boundary only through explicit custody.

For individualized gear:

```text
PLAYER_INVENTORY / CLAN_STORAGE
        -> WAR_CUSTODY
        -> temporary 1.8.9 combat snapshot
        -> exactly-once settlement
        -> PENDING_DELIVERY / authorized clan custody
```

The disposable match representation is not the economic item and may be destroyed/recreated freely. Persistent `item_instance_id`, roll state, upgrade state and ownership remain PostgreSQL concepts.

Rules:

- exact item identity/version is checked at entry;
- live player-state removal is fenced where the source is player inventory;
- an item in active `WAR_CUSTODY` cannot simultaneously be in AH, trade, clan storage, or personal inventory custody;
- the match backend receives only the combat representation required by the war rules;
- match code cannot directly mutate player/clan balances or item custody;
- settlement applies allowed economic effects once, then returns/redirects persistent value through normal delivery/custody authority;
- backend crash cannot create a second authoritative copy;
- unreleased custody is recoverable from durable records.

Clan storage/treasury may supply war resources only through explicit authorized custody/transaction operations.

## Instance model

Ranked Arena and Clan War use match-temporary instances. They may initially share infrastructure, but logical match identity and persistent records are independent of a particular Paper process.

Destroying or restarting a live 1.8.9 match backend must never erase a committed match/war record or make persistent custody ambiguous.

## State isolation

### Ranked Arena
Temporary standardized state only; no normal MMO inventory mutation.

### Clan War
Real value enters through explicit custody; only a temporary combat snapshot enters the 1.8.9 runtime; real value exits through explicit settlement/recovery.

### Normal PvE/gathering
Modern persistent player state, protected from uncontrolled open-world player destruction.

Do not reuse one inventory/death model across these contexts.

## World-politics boundary

Clan social/political influence may affect player campaigning and voting behavior, but clans receive no hidden weighted ballots or admin privileges by virtue of size/status. Expansion voting remains governed by the same authoritative ballot rules for all eligible players.

## Open tuning values

Configuration/playtesting decides:

- clan member cap;
- role permission defaults;
- Arena rating constants;
- Clan-War rating constants;
- war team size;
- war costs/rewards;
- durability/consumable settlement parameters;
- exact match capacity/time limits.
