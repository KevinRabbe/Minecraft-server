# Persistent Data Model

This document defines table/data families and ownership relationships, not final column-level schema. Exact schema is implemented through migrations after these contracts are stable.

## Identity and sessions

### `players`
Stable internal player identity plus preserved Minecraft UUID and current identity metadata.

### `player_names`
Historical/current name mapping if needed for display/search without using names as identity.

### `player_sessions`
Conceptual fields:

- `player_id`
- `network_session_id`
- `owner_backend_id`
- runtime instance locator where useful
- `state_version`
- status
- lease expiry/heartbeat metadata

## Player state

### `player_state`
Persistent non-skill/network state that does not deserve a separate transactional table.

### `player_skills`
Per-player skill XP/progression source state.

The active cap is global/configured world state rather than hidden overflow per player.

### `player_bounty_progress`
Per-player progression within each bounty family where separate from generic skill rows.

Conceptual key:

`player_id + bounty_family_id`

May store XP/progression source state required to derive accessible bounty tiers/pouch upgrades.

### Inventory/equipment state
Implementation may use snapshots plus structured authoritative records where required. Valuable/unique item ownership must remain independently verifiable.

### `pending_deliveries`
Durable value already owned by a player but not yet safely placed in physical Minecraft inventory.

## Items and provenance

### `item_instances`
Only for individualized items whose identity matters.

Conceptual fields:

- `item_instance_id`
- `definition_id`
- authoritative owner/location
- creator/original owner when relevant
- created timestamp/source
- persistent normalized roll state where applicable
- upgrade state where applicable
- enchantments/durability/other mutable state where required
- individualized Map challenge state where the item is a Map
- state/version metadata

### `item_provenance`
Append-oriented history/origin/custody records where provenance has persistent value.

### `historical_entitlements`
Exactly-once rights to historical/project/event rewards.

Do not create one row per unit of normal stackable commodities.

## Commodities

Commodity quantities may live in player-state inventory representation and/or structured balances/custody tables according to the authoritative inventory design.

Where structured balance rows are used, key by stable owner/location + `definition_id`; never one row per unit.

Pouch contents are still ordinary commodity ownership under a pouch location/family constraint, not a new asset identity class.

## Currency / Bank Manager

### `wallets`
Fixed-point integer spendable/pocket Coin balance and state version as already implemented/appropriate.

### `bank_accounts` or equivalent bank-custody state
Protected Coin balance plus bank progression/capacity state where that progression is not derivable elsewhere.

Bank interest eligibility requires enough persistent period/checkpoint state to prove one credit per configured period, for example:

- last credited period/date;
- account/bank tier reference if not derived;
- state version.

Do not use floating point.

### `economic_ledger`
Append-oriented audit evidence for important value movements/faucets/sinks.

Reason/source categories include pocket death loss, bank interest, bounty contract fees, market fees, crafting/upgrade/salvage/service operations, configured gameplay rewards, and transfers.

## Bazaar

### `bazaar_orders`
Buy/sell order identity, commodity, side, limit price, original/remaining quantity, owner, status, created time, and escrow references/state.

### `bazaar_fills`
Immutable/append-oriented execution records connecting buy/sell orders, quantity, execution price, fee, operation identity, timestamp.

Order-book read models/history are derived from authoritative order/fill records.

## Auction House

### `auction_listings`
Individualized-item custody and listing lifecycle.

One active listing owns custody of one exact `item_instance_id`.

Fixed-price Buy-It-Now is the initial V1 lifecycle.

## Secure trades / commissions

### `secure_trades`
Trade workflow state and exactly-once settlement identity.

### `commissions`
Crafting/refining work requests, escrow, worker, result, settlement status where/when shipped.

### Escrow/custody records
May be represented in system-specific tables or a shared custody model, but one asset must have one authoritative location at a time.

## Crafting

### `craft_records`
Authoritative evidence/provenance for successful crafts where useful.

Conceptual fields:

- craft/operation ID
- player/crafter ID
- recipe ID/version
- timestamp
- input summary/reference
- output item IDs and/or commodity outputs
- roll-generation/version context where relevant

The idempotent craft operation, not the UI click, is the source of truth for one craft result.

## Portal / Maps

### Map item state
Individualized Map challenge properties live on/in relation to the unique item instance according to the item serialization/schema choice.

### `map_runs`
Persistent lifecycle record for one execution of one source Map.

Conceptual fields:

- `run_id`
- `source_map_item_id`
- run state
- party/participant reference
- difficulty
- environment/enemy-family/objective/modifier context or immutable snapshot reference
- generation seed/profile
- Map-generation version
- balance/combat version
- world era
- start/end timestamps
- result/failure reason
- reward settlement operation ID

One source Map may create at most one valid run.

### `map_run_participants`
Participants and relevant start/end metadata when normalized storage is useful.

### `map_clears`
Append-oriented qualifying completion records used as source truth for PvE leaderboards/history.

Conceptual fields:

- `clear_id`
- `run_id`
- solo/group classification
- difficulty
- elapsed time
- world era
- balance/version context
- timestamp
- loadout snapshot/reference as required for durable interpretation.

## Bounties

### `bounty_contracts`
One player's active/historical contract lifecycle.

Conceptual fields:

- contract ID
- player ID
- bounty family ID
- tier ID/version
- state
- eligible kill progress/target snapshot if needed
- fee operation ID
- summon authorization state/count
- boss run/attempt reference
- completion/failure metadata
- state version/timestamps

### `bounty_kill_events` or dedupe evidence
Persist only if required to guarantee/reconstruct exactly-once category-kill progress. A compact processed-operation/event key may be sufficient rather than retaining every raw kill forever.

### `bounty_attempts`
Optional normalized boss-attempt lifecycle if separating encounter attempts from contracts materially improves correctness/history.

### Bounty materials
Remain ordinary commodity definitions/balances; no special per-unit identity.

### Bounty pouches
May use generic pouch/container state keyed by player + family or a unique pouch item model. Either representation must preserve central commodity authority and family allowlist/capacity constraints.

## Clans

### `clans`
Stable clan identity, name/tag, War Rating, lifecycle metadata.

### `clan_members`
Membership and role.

### `clan_treasuries`
Clan-controlled Coin custody/account state if not represented through a generic asset-owner model.

### `clan_storage`
Shared commodity/unique-item custody metadata and permissions if not represented through a generic container/owner abstraction.

### `clan_history`
Auditable important lifecycle/rating/event records as needed.

## Ranked PvP

### `ranked_matches`
Match identity/result.

### `ratings`
Current authoritative rating.

### `rating_history`
Historical changes for audit/debug/leaderboard history.

## Clan war

### `clan_wars`
Challenge/accept/match/settlement lifecycle.

### `war_rosters`
Locked participants.

### `war_loadouts`
Custody/snapshot records for real economic equipment entering the isolated match context.

### `war_settlements`
Exactly-once result of consumed/changed/returned value.

## World expansion voting

### `expansion_votes`
Persistent vote identity/lifecycle and candidate-set version.

Conceptual fields:

- `vote_id`
- candidate-set/config version
- open/close timestamps/rules
- state
- resolved winner
- resolution operation/version/timestamp

### `expansion_ballots`
Authoritative ballots keyed to stable player identity and the vote's uniqueness policy.

Conceptual default uniqueness:

`vote_id + player_id`

### `feature_states`
Persistent feature accessibility separate from runtime infrastructure activation.

Conceptual examples:

- `feature_id`
- accessibility state
- source vote/project/action
- unlocked timestamp/version

### `world_eras`
Major historical power/content-era transitions where useful for leaderboards/history.

Conceptual fields:

- era ID
- started timestamp
- source feature/vote/project/action
- predecessor/sequence metadata

Do not create a new era for every trivial horizontal district unless history/leaderboard semantics require it.

## Community projects

### `community_projects`
Only for explicitly defined project lifecycles; not automatic for ordinary voted districts.

### `project_contributions`
Immutable contribution transactions for projects that actually accept economic contributions.

### `project_contributors`
Derived/aggregated contributor state if useful for rewards/UI.

### `project_archives`
Versioned build archive/schematic metadata, checksums, timestamps, contributor/event references.

Ordinary district physical identity is not represented as a canonical blueprint/progress row merely to decide whether players built "enough".

## Chronicle / historical events

### `historical_events` / Chronicle source records
May store normalized event metadata/reference when useful, but should point to an authoritative source such as:

- Day-0 opening record
- resolved expansion vote
- feature/world-era transition
- Map clear
- ranked/war result
- explicit project completion
- historical entitlement event

Do not make freeform display text the only source of truth.

## Leaderboards/read models

Leaderboards are derived from authoritative source records such as:

- skills/XP
- PvP ratings
- clan War Rating
- `map_clears`

Useful cache/read-model records may store Top-N snapshots, generated timestamps, source version/watermark, and indexed personal rank.

Historical Map leaderboards must remain queryable by world era/balance context.

## Domain events/analytics

A structured appendable event stream/table can capture product/economy events without Kafka. See `ANALYTICS.md`.

## Stable identity rule

Long-lived objects use stable internal IDs. Names, URLs, backend IDs, display labels, and world paths are attributes/locators, not identity.