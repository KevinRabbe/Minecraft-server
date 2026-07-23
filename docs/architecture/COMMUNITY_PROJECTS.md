# Community Projects and Server History

## Purpose

Community projects let players physically and economically participate in the arrival of future systems while creating durable server history.

Core lifecycle:

`ANNOUNCED -> CONTRIBUTING -> BUILDING -> REVIEW -> COMPLETED`

Exact UI/workflow can evolve; the persistent lifecycle and attribution are the important contract.

## Generic project model

A project should be defined through reusable data/contracts rather than one-off `if Museum`/`if Nether` code.

Conceptual components:

- `ProjectDefinition`
- `ProjectInstance`
- contribution requirements
- build region
- lifecycle state
- contributor records
- completion actions
- archive metadata
- reward definitions

## Contributions

Economic contributions are authoritative transactions.

When a player contributes materials/Coins:

- value leaves player ownership exactly once
- project progress/contribution history increments exactly once
- contributor attribution is durable
- physical chest contents are not the source of truth

## Build regions

Construction occurs only where building is intended gameplay.

A project region may be temporarily editable by authorized contributors/build roles and later frozen/protected when completed.

Normal resource/combat zones are not arbitrary build worlds.

## Completion actions

Use generic actions such as:

- `ENABLE_FEATURE(feature_id)`
- set project/world protection state
- grant exactly-once historical entitlement
- trigger archive/snapshot workflow
- expose new travel/interaction point

The feature's implementation/accessibility is separate from whether runtime infrastructure is active at that moment.

## Nether Entry example

The first major use case is the later Nether unlock:

1. Nether feature exists in implementation but remains locked.
2. Nether Entry/Gate project appears in the starter region.
3. Players contribute resources and physically build the structure.
4. Build is reviewed/completed.
5. schematic/archive and contributor records are created.
6. exactly-once contributor rewards/entitlements are issued.
7. `NETHER_ACCESS` becomes available.
8. Nether zone instances start only when players actually use the feature.

## No empty future buildings

Do not construct unused Museum/Harbour/portal/etc. placeholders merely because they might exist later.

When a future system is ready, its project/construction can become part of the content event that introduces it.

## Archive/preservation

Significant completed community builds should be preserved through versioned archives such as WorldEdit schematics/blueprints plus metadata:

- project ID
- archive version
- completion timestamp
- contributor references
- content/resource-pack version where useful
- checksum

Do not silently overwrite previous archive versions.

## Historical rewards

Rewards should emphasize provenance, participation, and memory rather than mechanical power.

Examples can be simple ordinary Minecraft items with immutable event metadata/entitlement proving authenticity.

Issuance closes permanently when the event/project rules say it closes. Staff should not be able to mint an indistinguishable authentic copy through ordinary commands.

## Contributor reuse

No DRM obsession is required for community-created build schematics. Contributors may be allowed to keep/reuse/share blueprints according to project policy.

## Global project state

Project contribution/progress is network-global, not separate per City/zone instance.

If the City ever has multiple instances, they render the same authoritative project progress. Physical canonical-build behavior must be deliberately coordinated rather than creating independent competing copies.

## Principle

**Scarcity of memories and participation, not scarcity of power.**
