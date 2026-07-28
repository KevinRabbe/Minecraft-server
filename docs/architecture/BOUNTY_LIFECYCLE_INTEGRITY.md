# Bounty Lifecycle Integrity Boundary

Status: **implemented and CI-qualified when the corresponding branch is green.**

The Bounty authority is intentionally split across durable lifecycle evidence rather than one mutable quest row being trusted by itself:

`contract start + Coin fee -> kill progress -> summon prepare -> runtime lease/materialization -> completion | failure`

Managed ordinary-PvE kills add a durable bridge from the already-authorized resource kill into Bounty-family progression.

`BountyLifecycleIntegrityVerifier` reconstructs those relationships read-only with bounded issue output.

It verifies:

- every Bounty contract begins from the exact `BOUNTY_CONTRACT_START` processed request, immutable start snapshot, and exact Coin-fee ledger shape;
- historical direct `BOUNTY_KILL_PROGRESS` operations remain bound to the intended contract/player and never claim a future contract state;
- every `BOUNTY_MANAGED_KILL_PROGRESS` bridge matches the exact resource harvest/kill claim/source definition, applied/no-op result shape, player/family, and optional contract identity;
- current contract and summon states form a coherent pair (`SUMMONED` with READY/ACTIVE, `COMPLETED` with DEFEATED, `FAILED` with FAILED), while pre-summon contract states do not already own a summon;
- every summon has one exact historical `BOUNTY_SUMMON_PREPARE` result with its original READY snapshot and SUMMONED contract identity;
- historical claim/heartbeat operations remain bound to the same summon and backend request, preserve ACTIVE snapshots, and advance heartbeat state by one without requiring old owners to equal a later reclaimed owner;
- every persisted boss materialization matches one exact `BOUNTY_BOSS_MATERIALIZE` operation including entity, backend, boss definition, world, and coordinates;
- terminal completion/failure has exactly one matching terminal operation, with the final contract/summon state-version transition and expected reward-operation semantics.

Historical materialization is deliberately **not pinned to the current summon owner backend**. An expired ACTIVE summon may be reclaimed according to the existing lease/recovery authority, so an earlier materialization remains historical evidence rather than current ownership authority.

Likewise, the absence of a managed-kill bridge is not automatically corruption: `BountyKillProgressRepository` has a bounded recovery scan precisely because an authorized resource kill can commit immediately before its Bounty classification is persisted. Once a bridge row exists, however, its evidence must reconcile exactly.

`PersistentPveIntegrityVerifier` continues to own completed reward evidence, Bounty pouch conservation, and pouch-withdrawal delivery checks. This lifecycle verifier complements those checks rather than duplicating reward accounting.
