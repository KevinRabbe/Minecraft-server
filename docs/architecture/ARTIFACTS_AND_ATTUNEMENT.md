# Hidden Artifacts and Attunement

Status: **Canonical V1 content-category contract.** This extends the already-planned artifact collection; it does not create a new item/equipment/resource category.

## Purpose

The persistent world should reward actual exploration with a small amount of build power without becoming a mandatory giant checklist or copying Hypixel Fairy Souls.

A small set of hidden world artifacts is placed across authored/persistent exploration spaces. Players permanently record discoveries. As new worlds and major progression areas become available, a few additional artifacts may be added to those new spaces.

The exact count is content tuning. An initial world might contain roughly ten, but world size and discovery quality matter more than a fixed number.

## Identity

Every hidden artifact has a stable opaque `artifact_id`.

Location, world, visual model, display name and hint text are attributes, not identity.

This means an artifact can be physically relocated during a world rebuild/migration without taking the discovery away from players who already found that artifact.

## Discovery state

Discovery is:

- per player;
- permanent once legitimately completed;
- server authoritative;
- idempotent;
- non-tradable;
- not represented as a valuable inventory item that can be duplicated or sold.

The live world interaction only requests discovery. Persistent authority decides whether that `artifact_id` was already found and grants progress once.

## World progression

Artifacts grow with the world rather than all existing on Day 0.

Example progression shape:

- starter world: small initial set;
- newly opened district/exploration space: zero or a few depending on size and purpose;
- Nether-scale expansion: additional hidden set;
- later world/End-scale expansion: additional hidden set.

The number is never used as a blueprint requirement for player-built districts. Ordinary district construction remains player-directed.

Artifacts should primarily live in exploration-compatible locations where later player construction cannot permanently make a required discovery impossible. If a location becomes invalid, the stable artifact identity allows relocation without resetting collection state.

## Attunement points

Artifact discovery contributes to a small permanent pool of **Attunement Points**.

Exact point cadence and stat conversion are configuration/tuning. The structural rule is that the total bonus remains small relative to equipment/progression and is useful for build specialization rather than mandatory baseline power.

The player chooses **one active attunement profile at a time**.

Example:

- an Arcane/Magic attunement can convert the available exploration points into Intelligence;
- another attunement could map the same point pool to a different build-relevant stat.

Exact profile names and stat mappings remain content decisions. V1 should use only a small number of clearly distinct profiles.

## Why one active profile

One active profile prevents hidden collectibles from becoming passive universal stat inflation.

The same exploration progress instead becomes a build decision:

`found artifacts -> attunement points -> choose one current specialization`

A magic build can emphasize Intelligence while another build can use the same discoveries differently. Players are not expected to collect separate copies of the exploration system for every build.

## Switching

Switching the active attunement changes where the already-earned point pool applies; it does not consume or duplicate artifact discoveries.

Whether switching is free, City-only, cooldown-bound or has a small cost is a tuning/UI decision. The authority model must support safe atomic switching without rerolling or rewriting discovery history.

## Power budget

Hidden artifacts are intentionally **small power**.

They should:

- reward exploration;
- give optimization-minded players something meaningful to hunt;
- support build identity;
- create long-lived secrets in new worlds;
- remain secondary to gear, skills and actual combat progression.

They should not:

- create a second leveling system;
- unlock core content;
- be required to use bounty materials or gear;
- grant enormous multiplicative bonuses;
- force all builds to use the same stat;
- turn every expansion into hundreds of mandatory collectibles.

## Content design rule

The system must not reveal a canonical path or exact physical blueprint for discovery.

Hints, environmental language and community knowledge are acceptable, but the intended experience is finding hidden things in a persistent world—not following a built-in coordinate checklist.

The initial set stays deliberately small. Expansion adds a few high-quality discoveries rather than filling every new area with collectibles.

## Persistent records

The eventual authority layer should minimally persist:

- stable artifact definitions/content version;
- `player_id + artifact_id` discovery evidence;
- discovery timestamp/world-era context;
- current active attunement profile;
- derived available/active point totals.

Point totals should be derived from discovery/config where practical rather than independently mutable counters.

## Acceptance proof

The system is correct when:

1. one player can discover one artifact at most once;
2. concurrent/retried interactions cannot duplicate progression;
3. relocating an artifact does not erase historical discovery;
4. artifacts introduced in later world eras become discoverable without rewriting old discoveries;
5. one active attunement determines the stat effect from the earned point pool;
6. switching attunement cannot duplicate points or leave multiple profiles active;
7. artifact progress remains independent from tradable economy custody.
