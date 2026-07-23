# Community Projects and Persistent History

## Principle

Players physically construct the infrastructure through which future server functions can enter the world.

V1 needs the reusable project lifecycle even though future feature buildings such as a Museum or Fishing Harbour are explicitly deferred.

## Lifecycle

```text
ANNOUNCED
-> CONTRIBUTING
-> BUILDING
-> REVIEW
-> COMPLETED
-> ARCHIVED
```

A project may define:

- resource requirements
- construction region
- eligible builders/contributors
- completion/review conditions
- completion action/unlock
- historical reward rules

Initial project sequencing is developer-curated. Player voting among future project branches is deferred.

## Regions

City distinguishes at least:

- protected civic regions
- persistent player/build plots where allowed
- active community-project regions with explicit permissions

Resource worlds are renewable activity areas and are not permanent civic construction/storage worlds.

## Completion snapshot

When a significant project completes:

1. freeze/finalize accepted build state
2. export WorldEdit-compatible schematic/blueprint
3. create immutable project-version metadata
4. calculate checksum
5. store/archive the artifact
6. record qualifying contributors/entitlements

Metadata includes at least:

- project ID
- project version
- completion timestamp/date
- contributors
- content/resource-pack compatibility version where relevant
- checksum

Never silently overwrite a completed project version.

## Blueprint entitlement

Qualifying contributors may receive access to the completed blueprint. No DRM is required after delivery; the value of the live build is its shared history/context, not exclusive geometry.

## Historical rewards

Projects/events may issue simple zero-power commemorative items, for example an ordinary Cake carrying immutable provenance metadata.

Historical metadata may include:

- event/project ID
- completion date
- original recipient
- contribution role
- closed issuance identity

Once issuance closes, authentic issuance cannot be reopened through ordinary GM powers. Retries must be idempotent so one entitlement cannot mint multiple authentic rewards.

## Staff boundary

GM/community staff may inspect/review/rollback within authorized project scope but cannot mint transferable economy items, Coins, skill XP, leaderboard state, or historical rewards outside the system-defined workflow.
