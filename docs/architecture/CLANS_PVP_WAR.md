# Clans, Ranked PvP, and Clan War

## Clans

Launch clan functions include:

- create clan
- unique name/tag
- invite
- leave/kick
- Leader/Officer/Member or similarly small configurable role set
- clan chat
- roster
- configurable member cap
- treasury
- shared storage with explicit permissions
- War Rating/history
- global clan leaderboard

No open-world land destruction is required.

## Emergent organization

The game does not assign hard professions/classes to clan members.

Large clans may choose to organize members as:

- miners/foragers/farmers
- Map pushers/farmers
- Spider/Zombie/Golem or other bounty specialists
- crafters
- traders
- builders/logistics roles
- competitive players

Their advantage comes from division of labor, specialization, capital allocation, market knowledge, and coordination rather than hidden clan-only production multipliers.

Solo/small-group specialists remain economically relevant because resources/materials/gear remain tradable through the wider market.

## Clan treasury

The treasury is explicit clan-controlled Coin custody.

Rules:

- deposits/withdrawals are authoritative transactions;
- role/capability permissions control withdrawal/spending;
- concurrent withdrawal cannot overspend;
- treasury funds are separate from personal pocket/bank balances;
- no role change/kick/leave race may duplicate or ambiguously reassign treasury Coins;
- important mutations are auditable.

The treasury may fund Bazaar/AH purchases, bounty costs, infrastructure, crafting inputs, war costs, or other future systems only through their normal transaction interfaces.

## Shared storage

Clan storage may hold configured commodities and individualized items.

Rules:

- storage ownership/custody is authoritative and persistent;
- roles/capabilities control deposit/withdraw/manage actions;
- one unique item cannot be both clan-owned and personally owned/listed;
- simultaneous withdrawals/transfers cannot duplicate quantity/items;
- leave/kick does not automatically convert clan-owned assets into personal assets;
- high-value operations produce audit evidence.

Avoid one infinite magical container if physical/logistical storage constraints later add gameplay value, but storage presentation must not weaken authority semantics.

## Clan leaderboard

Primary competitive score can remain clan War Rating unless later data justifies another explicit leaderboard.

Top prestige display may appear in City/UI. Ranking is global across instances.

Being rank 1 provides prestige/visibility/history, not required mechanical power.

## Ranked PvP

Ranked PvP is explicit opt-in and isolated from normal economic advantage.

V1 target:

- 1v1
- simple symmetric map
- standardized temporary loadout
- permanent skill/equipment advantages disabled
- temporary inventory discarded after match
- Elo/rating-style progression and leaderboard

A ranked-PvP match uses match-temporary zone state.

## Clan war

Clan war is a different product from ranked PvP.

It intentionally uses real economic gear/consumables so war acts as a resource sink/demand generator for peaceful producers/traders.

Planned V1 lifecycle:

1. challenge
2. accept
3. roster lock
4. loadout/value custody
5. isolated war instance
6. one simple objective/control-point mode
7. deterministic result
8. exactly-once economic settlement
9. War Rating/history update

## War loss model

No uncontrolled full-loot PvP.

Defined economic effects may include:

- durability changes
- consumed potions/ammunition
- configured entry/supply costs
- configured rewards

Equipment ownership/return is settled explicitly.

## War custody

Before entering the disposable match context, real persistent equipment/resources are snapshotted/escrowed/custodied.

The match operates on an isolated runtime representation. Final settlement applies the allowed changes exactly once.

A war backend crash cannot legitimately create a second authoritative copy of the loadout.

Clan storage/treasury may supply war resources only by explicit authorized custody/transaction operations; match code cannot directly mutate clan balances.

## Instance model

`PVP` and `WAR` are logical gameplay types, not necessarily always-running dedicated Paper processes.

Match instances can be allocated on demand. Initially one backend may host them; later additional backends can host multiple concurrent matches.

## State isolation

### Ranked PvP
Temporary standardized state only; no normal inventory mutation.

### Clan war
Real value enters through explicit custody and exits through explicit settlement.

### Normal PvE/gathering
Normal persistent player state, protected from uncontrolled open-world player destruction.

Do not reuse one inventory/death model across all three contexts.

## Rating transaction

Match result and rating update are durable/idempotent. A retried match-result message cannot apply rating twice.

## World-politics boundary

Clan social/political influence may affect player campaigning and voting behavior, but clans receive no hidden weighted ballots or admin privileges by virtue of size/status.

Expansion voting remains governed by the same authoritative ballot rules for all eligible players.

## Open values

Configuration/playtesting decides:

- clan member cap
- role permission defaults
- rating constants
- war team size
- war costs/rewards
- durability/consumable settlement parameters
- exact match capacity/time limits