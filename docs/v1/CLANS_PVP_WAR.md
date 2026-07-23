# Clans, Ranked PvP, and Clan Wars

## Clans

Clans exist from day one because relationships need time to accumulate.

Minimum V1 capabilities:

- create clan
- unique name/tag
- invite
- leave
- kick
- roles: Leader, Officer, Member
- clan chat
- roster/member list
- configurable member cap
- clan rating
- war history

Do not build large clan tech trees or territory-destruction systems in V1.

## Safety boundary

Normal gathering, refining, crafting, trading, building, exploration, and clan management are safe. PvP/loss is opt-in through explicit competitive contexts.

No open-world clan land destruction.

## Ranked PvP

Runs on the `PVP` role.

V1:

- 1v1
- standardized temporary loadouts
- simple rating/Elo-style system
- leaderboard
- one simple symmetric map is sufficient

Permanent inventory/equipment state is frozen or hidden for the match. Temporary combat state is discarded afterward. Permanent Combat skill power does not decide ranked matches.

## Clan wars

Runs on the `WAR` role after explicit challenge/acceptance and roster formation.

V1 should use one simple objective format and configurable team size/match duration. Exact values are balance configuration.

Wars intentionally use real economic equipment and consumables to create demand and sinks, but the WAR server does not receive unrestricted ownership authority over the player's only real item instances.

## War loadout escrow/snapshot

1. Player selects eligible equipment/consumables.
2. Persistent source items become locked.
3. WAR receives validated battle-state copies/snapshots.
4. Runtime records durability, consumable use, repairs, and other settleable changes.
5. Match completes or recovery procedure runs.
6. Settlement atomically updates persistent source items/results.
7. Locks release exactly once.

A WAR crash must not duplicate gear, settle twice, or permanently destroy valid source items because of missing transient state.

Potential durable entities include:

- clan war record
- roster
- loadout
- loadout items
- runtime settlement events
- final settlement

Use uniqueness/idempotency constraints so each player/war settlement can occur only once.

## Economic role

War should be a resource sink and demand event, not a mechanism for deleting another player's entire progression.

Expected demand includes equipment wear, arrows/ammunition where enabled, potions/consumables, repairs, and explicitly configured war entry supplies/costs.
