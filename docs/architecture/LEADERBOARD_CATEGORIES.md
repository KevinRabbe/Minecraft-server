# Leaderboard Categories

Status: **Canonical architecture policy.** Leaderboards are separated by game category because the network intentionally uses different runtime/combat rules for the persistent MMO and competitive PvP.

## Core rule

There is no single cross-mode "best player" leaderboard.

The network exposes two top-level leaderboard categories:

1. **Persistent MMO** — late 1.21.x gameplay and persistent-world progression;
2. **Competitive PvP** — 1.8.9-only Ranked Arena and Clan Wars.

They may reference the same stable `PlayerId`, `ClanId`, Chronicle and historical identity, but their records, ranking formulas, balance/ruleset context and acceptance proofs remain separate.

## Persistent MMO leaderboards

These represent achievement inside the canonical persistent game world.

Initial V1 examples include:

- Highest Solo Map Clear;
- Highest Group Map Clear;
- Fastest clear at a meaningful configured difficulty;
- other persistent-world prestige boards only when the underlying authoritative system warrants one.

Every competitive PvE clear record preserves enough context to interpret it historically:

- player/party identities;
- Map difficulty;
- environment;
- enemy family;
- objective;
- modifiers/elite composition;
- completion time;
- relevant loadout references/snapshot context;
- combat/balance version;
- world era;
- completion timestamp.

Major progression changes such as Nether/End may raise the practical power ceiling. Old records remain historical evidence rather than being silently rewritten or deleted.

A pre-Nether Difficulty 68 clear can therefore remain meaningful even after a later world era permits Difficulty 90+.

## Competitive PvP leaderboards

These belong exclusively to the dedicated 1.8.9 competitive category.

### Ranked Arena

The Arena leaderboard is based on authoritative standardized-loadout matches.

Candidate displayed fields include:

- rating;
- rank position;
- wins/losses where useful;
- highest historical rating;
- match/ruleset version;
- timestamp/history context.

The temporary Arena backend never owns persistent equipment, so Arena ranking is independent from MMO gear wealth.

### Clan Wars

Clan-War standings are separate from Ranked Arena and from ordinary persistent-world clan prestige.

Candidate displayed fields include:

- clan competitive rating/score;
- rank position;
- wins/losses;
- significant war results;
- ruleset/version context;
- historical peak where useful.

Real economic gear may influence a Clan War only through the existing `WAR_CUSTODY` + temporary combat-snapshot contract. The leaderboard records the authoritative match result, not the disposable legacy-backend inventory representation.

## No cross-category normalization

Do not attempt to calculate a universal score from PvE Map clears, Arena rating, Clan Wars, wealth, skills or other unrelated activities.

A player can be:

- #1 in Ranked Arena;
- mediocre in PvE;
- a top crafter;
- part of the strongest Clan-War clan;

without the game needing to collapse those achievements into one synthetic number.

This preserves specialization and makes rankings interpretable.

## History and Chronicle

Leaderboard categories are separate, but genuine major records can feed the shared Chronicle.

Examples:

- first player to clear a historically important Map difficulty;
- first group to reach a major PvE threshold;
- historically exceptional Arena achievement;
- major Clan-War result;
- clan reaching a meaningful competitive milestone.

Chronicle entries describe what happened and under which rules/world era. They do not merge rankings.

## Town / UI presentation

Persistent-world town displays may surface selected prestige boards.

Previously locked behavior remains valid:

- clan leaderboard/prestige can be visible in town;
- player inventory/profile UI may show the player's current rank position where relevant;
- physical town displays do not need live-per-tick updates and may refresh periodically (for example hourly).

The UI must clearly label the category, e.g.:

- `PvE / Maps`;
- `Ranked Arena (1.8.9)`;
- `Clan Wars (1.8.9)`.

A player should never mistake a legacy PvP ranking for a persistent-MMO progression ranking.

## Ruleset/version identity

Every leaderboard record must carry enough immutable context to identify the rules under which it was earned.

For persistent MMO records this includes world/balance era.

For competitive records this includes the competitive ruleset/version and 1.8.9 category identity.

If competitive rules materially change later, old records remain historical records under their original ruleset rather than being compared as though nothing changed.

## Acceptance proof

The separation is correct when:

1. a 1.8.9 Arena/Clan-War result cannot enter a modern MMO/PvE ranking table;
2. a modern PvE clear cannot affect Arena or Clan-War rating;
3. standardized Arena results never depend on persistent MMO inventory wealth;
4. Clan-War results settle exactly once against authoritative match/custody evidence;
5. historical records retain their original world/ruleset context;
6. UI always identifies which category a ranking belongs to;
7. the shared Chronicle can reference achievements from both categories without merging their ranking semantics.
