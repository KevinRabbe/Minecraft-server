# Permissions and Staff Boundaries

## Principle

Use named capabilities, not a broad `isAdmin`/unrestricted OP model for project systems.

Staff convenience must not bypass economic, vote, provenance, PvE-result, or history correctness.

## Capability examples

Capabilities may include narrowly scoped rights such as:

- inspect assigned regions
- duty fly/spectate
- teleport to assigned work
- inspect block/project/history state
- protect/unprotect assigned construction regions
- perform scoped rollback
- moderate chat/player access
- inspect transaction/audit state
- inspect Map/Bounty run/contract state
- inspect expansion ballots/result evidence
- initiate audited recovery workflows

Exact capability names live in code/config and should be stable identifiers.

## Explicitly forbidden ordinary staff powers

Normal GM/moderation capability must not permit:

- arbitrary transferable item minting
- arbitrary Coin creation
- direct skill/XP/leaderboard manipulation
- hidden bank-interest/pocket-loss manipulation
- hidden market/order manipulation
- arbitrary drop-rate changes
- force-setting Map/Bounty completion
- creating authentic bounty/Map rewards without their source operation
- boosting a clan/rating
- adding/removing hidden vote weight
- silently changing a resolved expansion winner
- silently enabling/disabling world features/eras
- bypassing historical reward uniqueness
- self-escalating capabilities

If emergency recovery genuinely needs value/state correction, use a dedicated audited recovery path with explicit reason and authorization.

## GM mode isolation

Where a GM gameplay mode is useful, use isolated state:

- separate/non-economic inventory
- no normal XP/drop generation
- no market/trade participation
- no persistent economic item transfer from GM state
- no authentic Map/Bounty/leaderboard achievement generation
- no ordinary expansion ballot participation if GM/test identity is not meant to be a real player
- actions audited where consequential

## Clan permissions

Clan roles/capabilities are separate from staff permissions.

Examples:

- invite/kick/manage role
- treasury deposit
- treasury withdraw/spend
- shared-storage deposit
- shared-storage withdraw
- war challenge/accept/roster actions

Permission validation occurs inside authoritative clan asset operations, not only in GUI visibility.

A role change/kick/leave race cannot bypass already-required custody/version checks.

## Expansion voting integrity

Ordinary eligible player ballots follow the same configured rule regardless of clan size, staff friendship, or developer preference.

Operational recovery capability may correct a demonstrable defect/fraud case only through an audited path that records actor, reason, target vote/ballot/result, and operation identity.

Do not create a normal staff button for "pick the winner".

## Ordinary district building

Staff/build protection may enforce where players can build and protect server infrastructure.

Staff review must **not** become a hidden requirement that an ordinary player-created district match a developer-authored blueprint, appearance, or minimum block count.

Explicit Community Projects may define separate scoped build/review permissions when their lifecycle intentionally requires them.

## PvE integrity

Staff/test tools may accelerate or inject content in disposable/private test environments, but test-generated Maps, rewards, items, clears, bounty completions, or history must remain distinguishable from authentic production outcomes.

In the canonical Day-0 world, ordinary moderation tools cannot forge legitimate leaderboard clears or bounty progression.

## Community project roles

For an explicitly defined Community Project, build permissions can be temporary and region-scoped. Completion may remove/reduce construction capability and protect/archive the final historical build.

This section does not apply automatically to ordinary voted districts.

## Backend/console access

Operational infrastructure access is separate from in-game gameplay permission. A person able to restart a process should not automatically become authorized to mint persistent economic value, alter votes, or forge history.

## Audit

Record consequential staff/recovery actions with:

- actor
- capability/action
- target
- reason where applicable
- timestamp
- related operation/transaction/source ID

Recovery actions that modify value, vote/feature state, world era, leaderboard source records, or historical authenticity require especially strong auditability.

## Principle

**Staff can manage and repair the game; they should not be able to silently rewrite its economy, progression, player choices, or history.**