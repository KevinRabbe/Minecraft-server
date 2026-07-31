# Live Content Compatibility

This document defines the deployment-time boundary between durable PostgreSQL/player-state authority and mutable loaded content.

A content identifier is not retained forever merely because it appears in history. It remains a deployment requirement only while current durable authority still needs that definition to represent player value, resume an operation, or finish a recovery obligation.

## Core rule

Before a Paper backend is allowed to serve gameplay, every live durable content reference must be representable by the loaded catalogs and Paper adapters.

A deployment must fail closed when a required stable identifier is missing or its identity changes incompatibly. Common/catalog gates run before backend bootstrap publication. The backend is then registered as non-routeable `STARTING` while adapter-specific validation and runtime composition complete; only an explicit final publication may transition it to routeable `ONLINE`.

Compatibility does **not** freeze ordinary tuning unless frozen durable state depends on that tuning. Material, display text, quantities, timing, XP curves, ranges, and similar values may change when the owning validator explicitly permits it.

## Dependency lifetime

Each content family follows the same lifecycle:

1. **Acquire** — durable authority records a stable identifier or frozen version.
2. **Retain** — startup requires that identifier/version while the authority remains live or recoverable.
3. **Handoff** — completion may transfer responsibility to another durable authority, such as pending delivery or individualized-item custody.
4. **Release** — terminal history no longer pins obsolete content once no live/recoverable authority depends on it.

A handoff must be explicit. Two validators may overlap during a transaction boundary, but historical evidence must not retain definitions indefinitely without a recovery reason.

## Current live-content gates

| Durable authority | Required loaded compatibility | Tuning intentionally allowed | Dependency release / handoff |
|---|---|---|---|
| Non-destroyed `item_instances` | Stable `definition_id`; identity remains `INDIVIDUAL`; frozen roll keys remain interpretable; non-zero upgrade state remains legal equipment | Material, display, roll ranges, and bounded value tuning that still represent frozen state | `DESTROYED` item heads release the definition |
| Serialized `player_state.state_payload` | Every recursively discovered managed commodity/individualized representation matches the item catalog and authoritative item head/version/location | Presentation changes that remain representable through the Paper codec/scanner | Replaced serialized state releases removed representations; remaining authority is checked by its next owner |
| Generic economy commodity custody | Pending commodity deliveries, open Bazaar orders, open/locked Secure Trade escrow, and positive clan-storage balances retain `COMMODITY` IDs | Material, display, and stack-limit tuning; relational quantity is independent of one ItemStack limit | Claimed delivery, terminal order/trade, or zero storage balance releases the dependency |
| Current Attunement selections | Every selected `active_profile_id` remains loaded | Profile tuning/stat mapping behind the stable ID | No player selecting the profile releases it |
| Durable skill state/evidence | Referenced skill IDs remain loaded and the active-cap ceiling can still represent durable XP | XP-curve retuning within the durable ceiling | Current policy intentionally retains IDs referenced by durable skill/XP evidence |
| Runnable/recoverable resource sources | Stable source definition/zone/template identity remains available for STARTING, ACTIVE, DRAINING, and unresolved managed-entity cycles | Quantity, XP, respawn, and ordinary balance tuning | Terminal cleaned sources/cycles release obsolete source content |
| Unfulfilled `resource_harvests` | Frozen commodity remains `COMMODITY`; optional frozen skill ID remains loaded | Material/display/stack-limit and XP-threshold tuning | `resource_harvest_fulfillments` hands commodity custody to pending delivery and XP state to the skill authority |
| Current Bank accounts | Current tier ID remains loaded and still represents the stored capacity/state | Interest and other compatible tier tuning | Account tier transition removes the old live dependency |
| Live crafting obligations | OPEN/ACCEPTED commissions retain exact recipe + XP-policy versions; unfulfilled craft XP retains its exact policy | New versions and unrelated tuning; historical completed output does not pin recipes | Terminal commission state and `craft_experience_fulfillments` release their respective obligations |
| Non-terminal Bounty contracts | Exact `(family, tier, content_version)` remains resolvable through progress, summon, boss, and terminal reward recovery | New versions for new contracts; unrelated version tuning | Terminal completed/failed contract with no unresolved recovery work releases the version |
| Positive Bounty pouch balances | Stored material ID remains `COMMODITY` | Material, display, and stack-limit tuning | Zero balance releases the definition; withdrawal transfers custody to pending delivery |
| Pending Map reward grants | `COMMODITY` grants remain commodity definitions; `UNIQUE_ITEM` and `MAP` grants remain individualized definitions | Material, display, stack-limit, and compatible item tuning | Fulfillment transfers authority to pending delivery, individualized item heads, and frozen Map profiles |
| Persisted Maps and unresolved Map runs | Non-destroyed Map items and CREATED/ACTIVE or completed-unsettled runs retain exact encounter/balance content; bound live routes retain their target zone/template | New content versions; tuning only through the exact version contract | Destroyed source item hands off to the run; settled completed runs and terminal released routes become history |

## Startup composition

The main Paper bootstrap performs migrations and common/catalog compatibility checks before creating a routeable backend.

After those checks pass, `BackendRegistry.registerStarting(...)` records the backend identity with status `STARTING` and player count zero. Bootstrap-zone rows may reference that backend, but routing and competitive dispatch continue requiring exact `ONLINE`, so even an ACTIVE zone instance remains hidden during initialization.

Paper then completes adapter-specific Map content/route validation, repository/controller construction, listener and command registration, recovery scheduling, and recurring task installation. `BackendRegistry.publishOnline(...)` is the final enable transition. Ordinary heartbeat refuses to publish a STARTING backend, preventing a timer or partial runtime from accidentally bypassing this boundary.

A failure before final publication never exposes the backend to routing. A publication failure invokes full plugin shutdown, marks the backend offline where possible, and fails enable.

The order is not a substitute for ownership. Each validator owns one durable dependency family and must not silently absorb unrelated tables merely to reduce bootstrap calls.

## What must not pin content forever

The following are historical evidence rather than live content promises once their authority has completed:

- destroyed individualized items;
- claimed deliveries after custody has moved;
- terminal Bazaar orders and Secure Trades;
- zero clan-storage or Bounty-pouch balances;
- fulfilled resource harvests;
- fulfilled Map reward grants;
- settled completed Map runs;
- closed competitive execution snapshots;
- immutable economic/provenance/Chronicle rows that are not themselves replay obligations.

History may retain the old identifier as evidence. The current deployment does not need to load the old definition unless another live authority still references it.

## Versioned content discipline

A stable `(id, version)` is an immutable semantic contract. Deployments may add a new version, but must not reinterpret an existing version that live authority can still select.

Startup compatibility proves that required versions remain loadable and structurally usable. It cannot infer that a developer silently changed the meaning of an unchanged version number; repository review and versioned-content tests must prevent that class of mutation.

## Acceptance boundary

Automated PostgreSQL tests prove each retain/handoff/release lifecycle and the fail-closed startup composition.

This does not replace the empirical coherent restore rehearsal. The Windows/Docker/Minecraft restore boundary remains a separate release requirement tracked by issue #58; recovery evidence must demonstrate that restored durable state reaches these same startup gates cleanly on a real machine.
