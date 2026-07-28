# Community Projects and Server History

## Scope

Community projects are an **optional explicit workflow** for features that genuinely need authoritative contribution/build/archival semantics.

They are **not** the default lifecycle for every player-voted district.

Ordinary world-expansion voting is specified separately in `WORLD_VOTING_AND_HISTORY.md`.

## Critical boundary

A voted ordinary district has no developer-authored physical blueprint, required appearance, hidden material quota, or minimum block count.

If players choose a Fishing capability and create a huge harbor, that is valid. If they create a small fishing area and collectively treat it as their district, that is also valid.

Do not silently reinterpret an ordinary district as:

`ANNOUNCED -> CONTRIBUTING -> BUILDING -> REVIEW -> COMPLETED`

unless that specific feature has been explicitly designed as a Community Project.

## Explicit community-project purpose

When intentionally used, a Community Project lets players physically/economically participate in a bounded global objective while producing durable attribution/history.

A project may use a lifecycle such as:

`ANNOUNCED -> CONTRIBUTING -> BUILDING -> REVIEW/ACTIVATION -> COMPLETED`

Exact states may vary by project definition. The lifecycle itself must be explicit rather than inferred from district visuals.

## Generic project model

A reusable project may include:

- `ProjectDefinition`;
- `ProjectInstance`;
- configured contribution requirements, if any;
- optional controlled build region;
- lifecycle state;
- contributor records;
- completion/activation actions;
- archive metadata;
- historical reward definitions.

Do not create one-off `if Museum` / `if Nether` code where a generic configured project mechanism is genuinely appropriate.

## Contributions

Where a project intentionally accepts economic contributions, they are authoritative transactions.

When a player contributes materials/Coins:

- value leaves player ownership exactly once;
- project contribution state/history updates exactly once;
- contributor attribution is durable;
- physical chest contents are not the authority.

A project that does **not** define a material quota must not invent one from physical block count.

## Build regions

Construction permissions exist only where building is intended gameplay.

An explicit project may define a controlled region that is temporarily editable and later protected/archived.

This does not mean all ordinary districts must be inside a controlled project region or satisfy a canonical schematic.

## Completion/activation actions

Explicit project actions may include:

- `ENABLE_FEATURE(feature_id)`;
- set protection/project state;
- grant exactly-once historical entitlement;
- trigger archive/snapshot workflow;
- expose a new travel/interaction point;
- mark a world era transition when the feature materially changes available power.

The feature's logical accessibility is separate from runtime infrastructure activation.

## Nether / End boundary

Nether and End are later major progression milestones. They may use an explicit vote, project, activation event, or combination defined by the canonical world-progression contract.

Locked constraints:

- developers do not force the population to choose them on a preferred schedule;
- physical construction is not required to match a developer-authored blueprint;
- Map difficulty numbers are not permission-gated by Nether/End;
- their stronger content/gear may raise the practical Map ceiling;
- exactly-once feature/world-era transitions remain authoritative.

The exact activation/completion signal for these exceptional milestones remains a planned/open content decision until explicitly locked.

## No empty future buildings

Do not construct unused Museum/Harbour/portal/etc. placeholders merely because they might exist later.

When a future capability is selected/unlocked, players may create its physical district as part of the world that actually emerges.

## Archive/preservation

Significant player-created builds/events may be preserved through versioned archives such as WorldEdit schematics plus metadata where preservation is useful:

- project/event/district reference;
- archive version;
- timestamp;
- contributor references where known/meaningful;
- content/resource-pack version where useful;
- checksum.

Do not silently overwrite previous archive versions.

Archiving is historical preservation, not a canonical-blueprint requirement for ordinary districts.

## Historical rewards

Rewards should emphasize provenance, participation, and memory rather than mandatory mechanical power.

Examples can be ordinary Minecraft items/titles/records with immutable event metadata/entitlement proving authenticity.

Issuance closes permanently when the event/project rules say it closes. Staff should not be able to mint an indistinguishable authentic copy through ordinary commands.

## Contributor reuse

No DRM obsession is required for community-created build schematics. Contributors may be allowed to keep/reuse/share blueprints according to explicit policy.

## Global project state

Where a project exists, contribution/progress is network-global rather than separate per equivalent City/zone instance.

If persistent City geography eventually has multiple rendered copies, physical canonical-build behavior must be deliberately coordinated rather than creating independent authoritative histories.

## Principle

**Projects preserve explicit collective work; voting determines direction; players determine ordinary district form.**