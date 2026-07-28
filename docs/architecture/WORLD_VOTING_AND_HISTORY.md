# World Voting, Feature Progression, and History

## Purpose

This architecture preserves the project's defining persistent-world rule:

**Developers define valid choices and mechanics; players determine which valid path the world actually takes.**

The system must remain correct without developers caring which expansion wins.

## Core boundaries

- player voting determines future expansion direction;
- vote integrity is server-authoritative;
- ordinary district physical form belongs to players;
- ordinary districts have no developer-authored required blueprint, appearance, or minimum block count;
- unexpected legitimate outcomes are not defects;
- feature accessibility is persistent global state;
- runtime infrastructure activation is separate from feature accessibility;
- world history records actual authoritative events rather than invented narrative;
- staff/recovery actions are audited and cannot silently rewrite legitimate outcomes.

## Expansion candidate set

A vote operates against an immutable/versioned candidate set.

Conceptual fields:

- `vote_id`
- `candidate_set_version`
- candidate IDs
- eligibility/open/close rules
- created/opened/closed timestamps
- status
- resolution operation/version

A candidate describes a capability/theme and any configured mechanical feature actions. It does not define a canonical physical district blueprint.

Examples may include Fishing, logistics/production, specialized combat/economy content, or major future milestones.

## Vote authority

PostgreSQL is durable authority for vote state and ballots.

The UI/Discord/client representation may display candidates/results, but cannot create authoritative counts.

Each ballot uses stable player identity and the configured uniqueness/eligibility rule.

V1 default should be simple: one authoritative ballot per eligible player per vote, updateable or immutable according to the vote definition.

Whatever policy is chosen must satisfy:

- retry cannot count the same ballot twice;
- stale candidate-set versions are rejected;
- closed/resolved votes reject new mutations;
- resolution is deterministic from authoritative ballots/config;
- one vote resolves at most once.

## Developer neutrality

The system may control which options are valid/available, but implementation/operations must not contain a hidden preferred winner.

Do not:

- inject secret weighting because developers prefer one path;
- silently alter a legitimate result;
- buff one candidate only to force its selection;
- force Nether/End because players are "behind schedule";
- repair an unexpected but valid social/economic outcome merely because it differs from expectations.

Intervene only for explicit rule violations, defects, fraud/abuse, or documented recovery.

## Resolution and feature actions

A resolved vote produces an immutable enough result/history record and executes configured exactly-once actions.

Possible actions:

- `ENABLE_FEATURE(feature_id)`
- expose resource/recipe/item definitions
- enable a new skill/activity
- make a travel/portal interaction available
- begin a new world era if the selected capability materially changes available power
- optionally instantiate an explicit Community Project if that candidate was intentionally designed to require one

Actions are idempotent and tied to one resolution identity.

A vote result and its feature transition cannot be applied twice because of retry/restart.

## Ordinary district physical form

The mechanical expansion capability and the player-built district are separate concepts.

Locked rule:

- no canonical blueprint is given to players;
- no hidden canonical blueprint exists as a completion truth for ordinary districts;
- no minimum ordinary-district block count is required by default;
- no developer review decides whether the district "looks enough like" the intended theme;
- players may build as much or as little as they choose around the unlocked capability.

The server may still enforce ordinary build permissions, protected infrastructure, claim rules, or safety boundaries. Those protect correctness/gameplay; they do not prescribe the district's artistic form.

## Expansion order and path dependence

The world is intentionally path-dependent.

Example histories:

`Fishing -> Logistics -> Nether`

and

`Combat specialization -> Nether -> Fishing`

are both valid if produced by legitimate votes.

Later systems must not assume one canonical historical order unless an explicit dependency is unavoidable and documented.

Prefer independently valid capability modules whose combinations create different opportunities rather than brittle linear prerequisite chains.

## Horizontal versus vertical expansion

Most ordinary districts should primarily add:

- QoL
- sidegrades
- specialization
- production/economic capability
- resource chains
- new build options
- active-use/utility equipment

Selected milestones such as Nether and End may add much stronger gear and therefore raise the practical PvE ceiling.

They do **not** unlock permission to enter a particular Map difficulty number.

## World era

A `world era` is historical context for a major change in the available power/content ecosystem.

Examples:

- Founding / pre-Nether era
- Nether-enabled era
- End-enabled era

Not every district creates a new era. QoL/horizontal changes may remain within the same era unless historical/leaderboard semantics benefit from a boundary.

World era is persistent global state and can be referenced by:

- Map clear records
- leaderboards
- Chronicle events
- historical achievements
- analytics

It is not a seasonal reset and does not erase earlier records.

## Chronicle/event history

The Chronicle is derived/presented from authoritative historical source records.

Potential event families:

- canonical Day-0 opening
- expansion vote opened/resolved
- feature enabled
- world era changed
- first significant Map clear
- all-time/era leaderboard record
- explicit Community Project completion
- historically meaningful competitive result
- other future events with a concrete authoritative source

Do not let freeform UI text become source authority. The historical event references the underlying vote/run/project/match/etc.

## Historical recognition

Historical participation/records may create:

- titles
- profile badges
- cosmetic/decorative recognition
- Chronicle references
- statues/plaques/displays
- authentic event items where appropriate

Do not make one-time historical participation a source of mandatory future combat power.

## Day 0 boundary

Private alpha/closed beta worlds are non-canonical/disposable.

The public countdown ends at Day 0. From that point legitimate persistent world state and player decisions become canonical history.

Test/admin-created assets/events must remain distinguishable from authentic Day-0 production history.

## Pre-launch organization

Players may form clans/alliances, recruit specialists, discuss strategies, and organize socially before Day 0.

This social preparation must not mutate canonical launch progression/economic authority before the opening unless a specific pre-launch system is intentionally defined.

Creator beta footage may be embargoed; media logistics do not affect game authority.

## Exceptional Community Projects

An expansion candidate may explicitly launch a Community Project for a major feature when the mechanic genuinely benefits from collective contribution/archival semantics.

This is opt-in per feature definition, not automatic for ordinary districts.

See `COMMUNITY_PROJECTS.md`.

## Nether / End

Nether and End are later major vertical-power milestones.

Locked architecture:

- their feature state can exist as implemented-but-locked;
- players determine when/if the relevant valid expansion path wins according to the world-progression system;
- activation transition is authoritative/idempotent;
- the exact physical player build is not blueprint-prescribed;
- stronger content/gear may raise practical Map difficulty ceilings;
- old pre-power-jump clear records remain historically queryable.

The exact candidate/project/activation UX remains configuration/content design until separately locked.

## Failure/recovery

### Database/transaction failure

Vote mutation/resolution fails closed if authoritative persistence cannot be guaranteed.

### Lost response

Retrying the same ballot/resolution operation reconstructs/returns the committed result rather than creating another ballot/count/action.

### Backend restart

Votes/features/history survive because they are global persistent state, not zone-instance state.

### Staff recovery

Any manual correction to ballots, resolution, feature state, era, or Chronicle source records requires explicit audit reason/capability. Avoid direct invisible SQL edits.

## Verification

Integrity tooling should detect at minimum:

- duplicate effective ballots violating the vote's uniqueness rule;
- resolved vote without valid candidate-set version;
- vote resolved more than once;
- feature action applied without its authoritative source operation;
- feature enabled twice in incompatible ways;
- world-era transition with unknown source;
- Chronicle event referencing missing/invalid source state;
- ordinary district implementation accidentally depending on a canonical build-size/blueprint completion field.

## Principle

**The server guarantees valid rules and remembers the consequences; players create the world's trajectory and physical history.**