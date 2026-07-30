# Renewable Resource Integrity Boundary

Status: **implemented and CI-qualified when the corresponding branch is green.**

Renewable gathering blocks and managed ordinary-PvE mobs share one persistent value path:

`resource source cycle -> immutable harvest entitlement -> commodity delivery + optional skill XP`

Managed ordinary-PvE sources add a runtime identity layer:

`source cycle -> managed spawn -> prepared kill claim -> immutable harvest`

`ResourceSourceIntegrityVerifier` reconstructs those relationships read-only with bounded issue output.

It verifies:

- each `resource_sources` authority head keeps its cycle and state version coherent, unresolved managed spawns remain attached to the current cycle, and terminal managed spawns refer only to historical consumed/retired cycles;
- every immutable `resource_harvests` row matches its exact `RESOURCE_SOURCE_HARVEST` processed-operation entitlement and the referenced historical player session;
- a recorded `resource_harvest_fulfillments` row matches the exact commodity delivery, `RESOURCE_HARVEST_COMMODITY_FULFILL` processed evidence, and optional `skill_xp_awards` evidence for that harvest;
- sources with no configured XP are valid and require no XP operation;
- a prepared managed-entity kill claim retains the exact entity UUID it was created for;
- an entity-bound harvest matches the exact source/cycle kill claim, KILLED spawn, and killer player;
- every KILLED managed spawn reconciles back to one exact immutable resource harvest.

An immutable harvest without a `resource_harvest_fulfillments` row is intentionally **not** corruption. Fulfillment is separately recoverable and may be resumed after a crash. Likewise, a prepared kill claim can legitimately exist before its harvest commits, and a claim can remain as historical evidence after a no-reward terminal entity resolution.

## Live content compatibility

Resource-source content is live balance data, but stable source identity must remain interpretable while runtime or recovery can still use it.

Paper therefore validates the loaded resource catalog after migration and before backend registration:

- a source head attached to a `STARTING`, `ACTIVE`, or `DRAINING` zone instance retains its stable `definition_id`;
- that definition must retain the persisted logical `zone_id` and `template_version` identity;
- a source with an unresolved managed-entity cycle in `PENDING` or `ACTIVE` state retains the same dependency even if its zone instance has already become terminal;
- commodity quantity, optional requested skill XP, respawn delay, cooldown, and equivalent balance values may change behind the same stable definition identity;
- once the instance is terminal and no managed-entity cycle remains unresolved, historical source rows may release an obsolete definition;
- immutable harvest entitlements do not pin old source definitions because commodity identity, quantity, optional skill and requested XP were frozen when the harvest committed.

The operator `/integrity` pass applies the same catalog-aware identity check to currently runnable source heads. The startup validator is stricter around recovery by also retaining definitions needed by draining instances and unresolved entity cycles.

This verifier proves issuance/origin evidence only. The generic commodity-delivery claim verifier remains responsible for later materialization into fenced player state, so the resource verifier does not duplicate delivery-consumption checks.
