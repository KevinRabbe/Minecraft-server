# Clans, Ranked PvP, and Clan War

## Clans

Launch clan functions:

- create clan
- unique name/tag
- invite
- leave/kick
- Leader/Officer/Member roles
- clan chat
- roster
- configurable member cap
- War Rating/history
- global clan leaderboard

No open-world land destruction is required.

## Clan leaderboard

Primary score: clan War Rating.

Top-10 prestige display may appear in City. A functional inventory/UI view provides Top 10 plus the player's own clan rank where applicable.

Ranking is global across instances.

Being rank 1 provides prestige/visibility/history, not direct mechanical power.

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

## Instance model

`PVP` and `WAR` are logical gameplay types, not necessarily always-running dedicated Paper processes.

Match instances can be allocated on demand. Initially one backend may host them; later additional backends can host multiple concurrent matches.

## State isolation

### Ranked PvP
Temporary standardized state only; no normal inventory mutation.

### Clan war
Real value enters through explicit custody and exits through explicit settlement.

### Normal PvE/gathering
Normal persistent player state, protected from open-world player destruction.

Do not reuse one inventory/death model across all three contexts.

## Rating transaction

Match result and rating update are durable/idempotent. A retried match-result message cannot apply rating twice.

## Open values

Configuration/playtesting decides:

- clan cap
- rating constants
- war team size
- war costs/rewards
- durability/consumable settlement parameters
- exact match capacity/time limits
