# Permissions and Staff Boundaries

## Principle

Use named capabilities, not a broad `isAdmin`/unrestricted OP model for project systems.

Staff convenience must not bypass economic/provenance correctness.

## Capability examples

Capabilities may include narrowly scoped rights such as:

- inspect assigned regions
- duty fly/spectate
- teleport to assigned work
- inspect block/project history
- approve/reject project build markers
- protect/unprotect assigned construction regions
- perform scoped rollback
- moderate chat/player access
- inspect transaction/audit state
- initiate audited recovery workflows

Exact capability names live in code/config and should be stable identifiers.

## Explicitly forbidden ordinary staff powers

Normal GM/moderation capability must not permit:

- arbitrary transferable item minting
- arbitrary Coin creation
- direct skill/XP/leaderboard manipulation
- hidden market/order manipulation
- arbitrary drop-rate changes
- boosting a clan/rating
- bypassing historical reward uniqueness
- self-escalating capabilities

If an emergency recovery genuinely needs value correction, use a dedicated audited recovery path with explicit reason and authorization.

## GM mode isolation

Where a GM gameplay mode is useful, use isolated state:

- separate/non-economic inventory
- no normal XP/drop generation
- no market/trade participation
- no persistent economic item transfer from GM state
- actions audited where consequential

## Community project roles

Project build permissions can be temporary and region-scoped. Completion removes/reduces construction capability and protects the final historical build.

## Backend/console access

Operational infrastructure access is separate from in-game gameplay permission. A person able to restart a process should not automatically become authorized to mint persistent economic value.

## Audit

Record consequential staff/recovery actions with:

- actor
- capability/action
- target
- reason where applicable
- timestamp
- related operation/transaction ID

## Principle

**Staff can manage the game; they should not be able to silently rewrite its economy or history.**
