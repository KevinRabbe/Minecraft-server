# V1 Acceptance Criteria

V1 is accepted only when the complete persistent-world loop survives normal use and deliberate failure without duplication, double rewards, ambiguous ownership, invalid votes, or authority corruption.

Exact tuning values are not release gates unless they make the game unusable. Structural correctness is. Canonical launch mechanics/identities are defined by the V1 planning documents, including `../planning/V1_CONTENT_DETAILS.md`.

## A. Join, identity, and persistent state

A fresh player can:

1. connect through Velocity;
2. receive stable network identity/session;
3. enter the starter region;
4. disconnect/reconnect without duplicate player records;
5. preserve committed inventory, equipment, Coins, bank state, skills, bounty progression, clan membership, and logical location;
6. recover safely if the previous backend/session lease is stale.

## B. Zone routing and cross-backend ownership

For starter gameplay and at least one disposable PvE activity:

1. players request logical gameplay zones, never backend IDs;
2. router selects a suitable live instance/backend;
3. additional instances are created only when that zone's concurrent demand requires them;
4. one backend is the only active writer for a player's live persistent state;
5. transfer freezes/commits/releases/claims with version fencing;
6. stale source writes and ticket replays are rejected;
7. instance/backend failure cannot duplicate inventory, Coins, XP, Map/Bounty rewards, or unique items;
8. unavailable destinations fall back safely;
9. every live `ACTIVE`, `TRANSFERRING`, or `RECOVERING` session reconciles to the exact current `player_state.state_version`;
10. live session ownership/lease shape is valid, a claimed `owner_instance_id` belongs to the same owner backend, and historical `DISCONNECTED` sessions retain no live backend/instance/lease custody;
11. every `TRANSFERRING` session and its one open ticket agree on player, source backend, and exact expected state version, while an open ticket cannot remain attached to a non-`TRANSFERRING` session;
12. a routed transfer's stable instance identity agrees with the ticket's target backend and logical zone; ticket expiry or a later `STOPPED` instance alone remains a recoverable condition rather than an integrity failure.

## C. Asset identity and economic evidence

1. fungible commodities use quantity accounting rather than per-unit identities;
2. individualized items receive stable `item_instance_id` custody/provenance;
3. every valuable asset has exactly one authoritative location at a time;
4. malformed/forged/stale live representations are rejected/quarantined/rebuilt from authority;
5. full inventory can receive transaction output through safe pending delivery;
6. economically meaningful operations have stable idempotency/operation identity;
7. committed economic evidence/ledger/provenance is append-only where required;
8. retries with the same operation identity cannot create another result.

## D. Coin pocket and Bank Manager

### Pocket/spendable balance

- integer/fixed-point arithmetic only;
- no negative spendable balance;
- concurrent spending cannot exceed owned balance;
- ordinary persistent combat death in Rootborn/Ashbound/Veilborn regions destroys exactly 5% of the locked current pocket balance, rounded down, once;
- the same ordinary death-loss rule does not fire in the Hub/starter Combat area, gathering-only areas, capital-M Maps, Ranked Arena, or Clan War;
- the lost Coin is destroyed through authoritative economic evidence and never materialized as world loot.

### Protected bank

- deposited money is protected from ordinary PvE death loss;
- deposits/withdrawals are atomic and idempotent;
- configured bank capacity is enforced;
- upgrades change configured capacity/interest eligibility without corrupting balance;
- daily interest, when enabled, credits at most once per eligible period and is recorded as an explicit faucet;
- changing interest/capacity configuration does not corrupt existing bank balances.

## E. Bazaar

For at least one commodity before broad content expansion:

1. buy orders reserve/escrow sufficient Coin;
2. sell orders reserve/escrow owned quantity;
3. best-price/oldest-time matching is deterministic;
4. partial fills preserve exact remaining quantity/value;
5. cancel/fill races have one valid winner and never return sold escrow twice;
6. offline settlement is correct;
7. duplicate/retried fills do not execute twice;
8. fees are explicit sinks;
9. commodity + Coin accounting reconciles after high concurrency/crash injection;
10. bounty materials and other configured fungible resources use the same market mechanism rather than bespoke pricing.

## F. Auction House and individualized gear

1. listing an individualized item removes it from player control into authoritative escrow;
2. one listing references the exact unique item instance;
3. simultaneous buyers result in at most one successful purchase;
4. cancel/purchase races cannot give the item to both seller and buyer;
5. sale/cancel settlement works while the other party is offline;
6. exact item roll quality/upgrades and any configured static use/equip requirements are visible/inspectable before purchase where relevant;
7. purchase/ownership authority does not depend on satisfying a configured use/equip requirement;
8. finished gear remains tradable unless an explicit documented exception exists;
9. retry/restart cannot duplicate the listed item or Coin proceeds.

## G. Crafting, rolls, upgrade, and salvage

1. a successful craft consumes all required inputs and creates outputs in one authoritative operation;
2. duplicate craft requests return/refer to the original result rather than rerolling another item;
3. individualized rolled gear stores persistent normalized roll quality;
4. changing balance definitions changes derived current stats without changing historical roll quality;
5. V1 rolled items use exactly one intrinsic property from `damage`, `defense`, or `gathering_speed` according to the locked item assignment;
6. initial V1 ranges resolve as weapon damage `10000..12000`, wearable defense `10000..11500`, and gathering-tool speed `10000..12000`, with normalized creation quality in `0..10000`;
7. most ordinary rolls remain usable and perfect rolls are not required for progression;
8. upgrade state is separate from intrinsic roll quality and upgrading never rerolls intrinsic quality;
9. V1 rolled items permit only deterministic `+0..+5`; each committed level adds 2% at the rolled-stat upgrade stage and cannot randomly fail, downgrade, or destroy the item;
10. one committed upgrade step advances the exact item's `upgrade_level` and item `state_version` exactly once without changing custody;
11. carried-item upgrades commit the serialized player-state authority-version change and the item authority head atomically under the owning live session;
12. stale/concurrent upgrade attempts from the same item/session head result in at most one commit;
13. replaying one upgrade operation returns the original result and cannot bind that operation to another item, payload, version, level, or session context;
14. an uncommitted/failed upgrade attempt leaves the item intact and does not silently degrade, destroy, reroll, or ambiguously advance it;
15. upgrade evidence/provenance is append-only enough for the global integrity verifier to detect live-item chain/provenance corruption;
16. upgrade costs never consume Heartwood Core, Kilnheart, or Gate Fragment as repeat V1 upgrade taxes;
17. salvage destroys exactly the intended unique item and creates configured output once;
18. salvage refunds no Coin, boss component, consumed upgrade investment, or roll/upgrade-dependent bonus yield;
19. craft/Bazaar/transfer races cannot spend the same commodity twice;
20. live rolled-equipment stats are rebuilt from trusted item definition/roll authority rather than trusting arbitrary carried ItemStack attributes, and unsupported runtime attribute shapes fail closed;
21. intrinsic-roll materialization affects the definition-owned base item contribution without accidentally scaling later player-skill, upgrade, enchantment, set/context, or temporary-effect stages.

Exact recipe quantities, upgrade Coin/material costs, and salvage return quantities remain tuning/content values; the V1 progression shape above is not open for implementation-time reinvention.

## H. Skills and staged caps

For Mining and Crafting first, then all launch skills:

1. valid authoritative actions award configured XP once;
2. replayed/player-placed/invalid sources cannot mint duplicate XP/value;
3. concurrent valid XP events sum exactly without lost updates;
4. skill benefits and XP eligibility are separately enforceable;
5. unlocks/requirements derive consistently from committed progression;
6. active launch cap is 50;
7. XP does not accumulate invisibly beyond the active cap;
8. test transition 50 -> 75 reopens progression without duplicating earlier rewards;
9. test transition 75 -> 100 behaves the same way;
10. a configured use/equip requirement is enforced at the relevant use/equip boundary without blocking ownership, transfer, listing, or purchase;
11. eligibility is derived from authoritative committed skill progression rather than item possession or client presentation;
12. any local eligibility projection is bounded, fails closed when no trustworthy snapshot exists, and routine use/equip checks do not require a PostgreSQL query per action;
13. disconnect/reconnect or attachment replacement fences stale eligibility refresh/retry work so an earlier session cannot become the current permission snapshot;
14. a committed XP award that crosses a configured use threshold advances the attached eligibility projection without requiring reconnect; if that commit races an attachment refresh, the newer skill `state_version` wins, while detach during the refresh prevents the old read from republishing permission state.

Enchanting/brewing retain their separately documented Minecraft integration and may not bypass staged-cap or source-gating invariants.

## I. Authorized gathering/starter economy bridge

For starter Woodcutting/Foraging, Mining, Farming, and ordinary PvE sources:

1. valid source creates configured commodity/XP once;
2. invalid/replayed/player-manufactured source cannot mint value where not intended;
3. equivalent zone instances use identical persistent progression/economy rules;
4. resource generation, transfer, Bazaar sale, crafting use, reconnect, and restart preserve exact accounting;
5. a qualifying Ruinbound Champion kill is represented by the managed-source authority before any first-Map issuance occurs;
6. one authoritative Champion kill can issue at most one bootstrap Map even across lost acknowledgements/restarts.

## J. Portal/Map PvE

### Instance lifecycle

1. one authorized run creates one isolated PvE instance/run identity;
2. participants/objective state are server-authoritative;
3. completion/failure transition occurs once;
4. completion reward occurs once;
5. disposable runtime can be cleaned up without losing persistent result/evidence;
6. disconnect/death/restart follows a documented deterministic policy;
7. Map death/failure does not also charge the ordinary 5% persistent-world pocket-Coin death loss.

### First Map bootstrap

1. the renewable Ruinbound Champion exists in the walkable starter Combat area before but does not block the first Rootborn portal;
2. a qualifying authoritative player kill issues exactly one tradable difficulty-1 Map with `Forgotten Bastion + Relic Guard + Extermination + no modifier`;
3. non-player/environmental Champion resolution creates no Map;
4. issuance is recoverable from durable managed-kill evidence;
5. the source remains renewable without a per-player daily/weekly lockout so a failed/consumed first Map cannot permanently lock the player out of Maps.

### Map object lifecycle

1. an individualized Map may be owned/traded/listed before opening;
2. opening consumes/moves the exact Map exactly once;
3. open/trade/AH races cannot both succeed;
4. one Map cannot create two valid runs;
5. run configuration records difficulty/environment/enemy package/objective/modifiers/generation/balance context;
6. failed run does not silently return the consumed Map;
7. successful run can create configured Map materials/new nearby Maps once;
8. successful eligible participants receive the configured Coin payout at most once through deterministic run/player wallet operations.

### Canonical content/runtime semantics

1. V1 environments are Forgotten Bastion, Flooded Depths, and Windscar Ruins and use authored compact templates with deterministic encounter anchors;
2. V1 Map packages are Relic Guard, Deep Brood, and Ruin Raiders and remain separate from Bounty family progress;
3. Extermination uses sequential objective-owned packs; Elite Hunt uses bounded guard gates followed by one authoritative marked elite target;
4. Fortified, Relentless, and Swarming preserve readable tells and bounded entity/cadence behavior as defined in `V1_CONTENT_DETAILS.md`;
5. Bulwark, Hunter, and Volatile preserve explicit counterplay and do not introduce hidden permanent guard/speed or zero-delay burst behavior;
6. unsupported content combinations fail closed rather than silently downgrading to a fixture/default encounter.

### Difficulty

1. Map difficulty is independent of player skill/character level permission;
2. a player may attempt content above their practical power;
3. scaling is data/config-driven and technically bounded;
4. changing the curve does not mutate historical run evidence;
5. major gear expansions raise practical clear ceilings rather than unlocking permission to enter difficulty numbers.

## K. PvE leaderboards/history

1. solo/group clears are derived from authoritative completed-run records;
2. one run cannot create multiple competing completion records;
3. clear records retain time, participants, Map configuration, relevant loadout context, balance version, timestamp, and world era;
4. highest/fastest views are derived read models rather than mutable client scores;
5. pre-Nether/pre-power-jump records remain historically queryable after later gear expansions;
6. leaderboard prestige does not grant required combat power.

## L. Bounties

For one family/tier first, then Rootborn, Ashbound, and Veilborn:

1. contract fee is paid exactly once as an explicit Coin sink;
2. only eligible authored normal-world family creature kills advance the bounty;
3. duplicate/replayed kills do not advance twice;
4. boss authorization becomes available only after the configured requirement;
5. one authorization cannot create multiple valid boss attempts unless explicitly designed that way;
6. boss completion/reward applies exactly once;
7. failure/restart/disconnect follows deterministic contract/boss-authorization semantics;
8. higher tiers introduce the locked stronger roles/combinations/mechanics rather than only numerical HP scaling;
9. the configured Rootborn/Ashbound/Veilborn technical bases and authored ability capabilities preserve their player-facing family identities instead of reverting to vanilla mob behavior;
10. all configured bounty-family materials are Bazaar-tradable;
11. personal completion is not required merely to buy/own/craft with a tradable family material;
12. ordinary persistent death in these three deeper regions applies the 5% pocket-Coin policy once without dropping managed items.

### Bounty pouches

- Rootborn/Ashbound/Veilborn pouches store only their configured family commodities;
- capacity upgrades are authoritative;
- moving/selling from a pouch cannot duplicate quantity;
- pouch custody does not make the commodity non-tradable.

## M. Clans

1. create/invite/leave/kick/role lifecycle persists across reconnect/backends;
2. treasury operations are atomic/audited;
3. shared-storage permissions cannot be bypassed by lower roles;
4. simultaneous withdrawals cannot duplicate commodities/unique items;
5. leaving/kicking cannot create ambiguous ownership of personal versus clan assets;
6. clan size is configurable without changing authority semantics.

## N. Ranked PvP and clan war

### Ranked 1v1

1. explicit opt-in creates one isolated execution and routes only its two frozen participants to the assigned 1.8.9 backend;
2. entering/leaving the competitive category requires the supported client reconnect boundary; the proxy does not silently protocol-translate the player between 1.8.9 and the persistent MMO;
3. standardized disposable configuration supplies the temporary arena/loadout and normal persistent inventory is never match authority;
4. combat stays closed until the exact execution is fully materialized; players waiting for materialization cannot interfere with another arena;
5. a materialized execution renews its lease only while it is locally runnable, and Ranked combat/result handling pauses when either participant is offline;
6. no-show/disconnect and runtime failure converge on bounded trusted recovery without inventing a winner;
7. the configured match timeout aborts without assigning a winner or rating result;
8. a valid death resolves the frozen opponent side through the narrow runtime report boundary and result/rating settlement applies exactly once;
9. disposable arena/runtime restart or cleanup cannot create persistent inventory value, duplicate rating settlement, or require the arena world for authority recovery;
10. real 1.8.9 client acceptance must still empirically verify the intended hit, knockback, movement, and combat feel before release.

### Clan war

1. challenge/accept/roster lifecycle works;
2. real economic loadout enters explicit custody/snapshot;
3. disposable match runtime cannot duplicate original gear;
4. configured consumable/durability/economic effects settle once;
5. result/rating/history updates once;
6. deliberate war-instance failure follows documented recovery/abort semantics without duplication.

## O. World expansion voting, districts, and Chronicle

1. candidate set/version is authoritative;
2. vote eligibility/uniqueness is enforced server-side;
3. retries cannot count a vote twice;
4. resolution is deterministic and recorded immutably enough for audit/history;
5. developers/admin tools cannot silently replace a legitimate result without an explicit audited recovery action;
6. selected feature/capability transition happens exactly once;
7. ordinary district physical form has no developer-authored blueprint, required appearance, or minimum block count;
8. player construction cannot be rejected merely for being smaller/different than an imagined canonical district;
9. Chronicle/history can record Day 0, votes/results, feature unlocks, significant clears, and other authoritative events;
10. historical recognition remains prestige/record, not required mechanical power.

Generic community-project/contribution/archive infrastructure may be accepted separately for explicitly defined projects, but it cannot become an undocumented mandatory progress bar for ordinary voted districts.

## P. Nether/End progression boundary

1. Nether is inaccessible on Day 0;
2. End is a later progression milestone;
3. player-directed world progression controls when the population reaches these milestones according to the documented feature flow;
4. no Map difficulty number becomes permission-gated solely by Nether/End state;
5. stronger Nether/End gear can raise the practical Map ceiling;
6. any physical player build associated with the milestone is not required to match a developer-authored blueprint.

## Q. Failure/adversarial tests

Deliberately test at minimum:

- Paper crash during ordinary play;
- Paper crash during/around transfer;
- repeated transfer ticket replay;
- deliberate corruption of live session/player-state version pairing, session ownership/lease shape, transfer-ticket/session pairing, and routed instance identity must be detected by bounded integrity verification;
- database response lost after a successful transaction commit;
- duplicate economic/crafting/XP/Map/Bounty/vote/upgrade/first-Map operation requests;
- Bazaar cancel/fill and AH cancel/buy races;
- simultaneous bank/spend/death-loss operations;
- concurrent carried-item upgrades from one stale item/session head;
- stale item-use eligibility refresh/retry work racing disconnect/reconnect or a newly committed XP result;
- player disconnect during sensitive operations;
- Velocity restart;
- PostgreSQL temporary unavailability;
- Map instance failure;
- Ruinbound Champion kill committed immediately before first-Map delivery failure/restart;
- bounty completion/boss-authorization failure timing;
- clan storage/treasury races;
- ranked/war instance failure;
- restart with empty/active disposable instances;
- intentional breaker/red-team exploit attempts.

No test may produce duplicate persistent value, two valid player writers, double rewards, impossible vote counts, or two authoritative owners for one unique item.

## R. Backup/restore

Before public launch:

1. create documented backup of PostgreSQL + persistent world data + relevant config/catalog versions;
2. restore into a disposable environment;
3. verify identity/inventory/wallet/bank/skills/markets/items/clans/votes/features/history/leaderboards;
4. verify persistent world state corresponds to the selected database recovery point;
5. verify no disposable instance is required to recover valuable persistent state;
6. run representative economy/transfer/Map/Bounty acceptance tests after restore.

## S. Private alpha / closed beta boundary

1. private alpha/closed-beta worlds are explicitly disposable/non-canonical;
2. beta assets/history cannot leak into Day-0 authority;
3. adversarial testers can be given accelerated/test-only access without producing production-authentic value;
4. creator embargo/release logistics do not affect persistent authority.

## T. Day-0 release gate

The public world may open when:

- no known critical persistent-state/economic duplication path remains;
- recovery/restore has been proven;
- canonical Map + Bounty PvE loops work end-to-end;
- the Ruinbound Champion can bootstrap the first Map recoverably from a fresh player path;
- markets/crafting/skills/clans/voting survive required acceptance tests;
- configured item use/equip requirements are inspectable before purchase and enforceable independently of ownership/trade authority;
- structural feature set is frozen for release;
- remaining numerical uncertainty is tuning-scale rather than architecture-scale.

A plausible 10–20% balance error is acceptable. A known structural defect capable of forcing a reset is not.

## U. Local-PC-first operational test

The Windows development deployment must remain startable/stoppable/recoverable without rented hosting during development. Moving to rented capacity later must not require changing gameplay identities, economic semantics, Map/Bounty authority, or persistent data contracts.
