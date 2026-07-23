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
- `owner_instance_id` (runtime locator only)
- `state_version`
- `status`
- `lease_expires_at`
- `last_heartbeat_at`

## Player state

### `player_state`
Persistent non-skill/network state that does not deserve a separate transactional table.

### `player_skills`
Per-player skill XP/derived level source state.

### Inventory/equipment state
Implementation may use snapshots plus structured authoritative records where required. Valuable/unique item ownership must remain independently verifiable.

### `pending_deliveries`
Durable value already owned by a player but not yet safely placed in physical Minecraft inventory.

## Items and provenance

### `item_instances`
Only for non-fungible items whose individuality matters.

Conceptual fields:

- `item_instance_id`
- `definition_id`
- authoritative owner/location
- creator/original owner when relevant
- created timestamp/source
- mutable instance state such as enchantments/durability when required

### `item_provenance`
History/origin records where provenance has persistent value.

### `historical_entitlements`
Exactly-once rights to historical/project/event rewards.

Do not create one row per unit of normal stackable commodities.

## Currency/economy

### `wallets`
Fixed-point integer balances; no floating point.

### `economic_ledger`
Append-oriented audit of important value movements.

### `bazaar_orders`
Buy/sell orders and remaining escrowed quantity/funds.

### `bazaar_fills`
Immutable fill records.

### `auction_listings`
Non-stackable/unique item custody and listing lifecycle.

### `secure_trades`
Trade workflow state and settlement identity.

### `commissions`
Crafting/refining work requests, escrow, worker, result, settlement status.

### Escrow/custody records
May be represented in system-specific tables or a shared custody model, but one asset must have one authoritative location at a time.

## Clans

### `clans`
Stable clan identity, name/tag, War Rating, lifecycle metadata.

### `clan_members`
Membership and role.

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

## Community projects

### `community_projects`
Project definition/instance/lifecycle and global progress.

### `project_contributions`
Immutable contribution transactions.

### `project_contributors`
Derived/aggregated contributor state if useful for rewards/UI.

### `project_archives`
Versioned build archive/schematic metadata, checksums, completion timestamps, contributor references.

## Feature registry

Persistent feature state separates implementation/accessibility from runtime process activation.

Conceptual examples:

- `feature_id`
- accessibility state (locked/available)
- unlocked/completed metadata

Do not use backend existence as feature-state authority.

## Leaderboards/read models

Leaderboards are derived from authoritative skills/ratings rather than their own source of truth.

Useful derived/cache records may store:

- Top-N snapshots
- generated timestamp
- source version/watermark if useful

Personal rank should be computed/indexed from authoritative score/XP rather than requiring a complete global sort on every menu open.

## Domain events/analytics

A structured appendable event stream/table can capture product/economy events without Kafka. See `ANALYTICS.md`.

## Stable identity rule

Long-lived objects use stable internal IDs. Names, URLs, backend IDs, display labels, and world paths are attributes/locators, not identity.
