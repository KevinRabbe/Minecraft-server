# Open Decisions

Only genuinely unresolved questions belong here. If a choice is locked elsewhere, implementation must not reopen it merely because code reaches that boundary.

## Balance/config values

These are intentionally deferred to playtesting/configuration rather than architecture:

- exact XP curves for each skill and bounty family;
- exact timing/date of the later active-cap increases from 50 -> 75 -> 100;
- exact bank capacities, upgrade costs, and daily interest rates;
- exact PvE pocket-money death-loss percentage/curve;
- exact zone soft/hard capacities and idle-retirement timeout;
- resource/mob respawn rates and gathering speed/yield curves;
- exact pouch capacities/upgrade levels;
- recipe ratios, crafting/refining costs, and processing durations;
- exact rolled-stat ranges/distributions per item within the locked approximate 10–30% low-to-high value envelope;
- upgrade costs and salvage return values;
- Bazaar/AH/direct-trade fees;
- bounty contract fees, kill requirements, number of tiers, boss numerical scaling, and exact material drop rates;
- Map difficulty curves, reward curves, map-drop progression variance, modifier strengths, elite frequency, and exact supported visible difficulty range;
- exact leaderboard cache/refresh intervals;
- Witch/bootstrap prices and exact pre-Nether bootstrap allowlist;
- clan member cap;
- ranked-PvP rating constants;
- clan-war entry/resource costs and reward values;
- exact pre-launch announcement/countdown duration (~30 days is only an example).

## World/content decisions

- final name/theme/layout of the starter region and compact activity spaces;
- exact first ordinary PvE mobs/locations;
- whether starter Mine/Forest/Farm transitions are visually seamless or explicit travel interactions;
- which zones keep a warm instance versus scale to zero when unused;
- final persistent City strategy if City concurrency eventually exceeds one physical copy;
- exact initial expansion-candidate pool shown to players;
- exact content/theme of each future district beyond already locked examples/directions;
- exact activation/completion signal for exceptional major world projects such as Nether/End if they require more than the authoritative vote/feature transition; no developer-authored physical blueprint or minimum block count may be introduced implicitly.

## Item/content decisions

- final launch item-definition allowlist within the locked roughly 25–30 meaningful-item target;
- exact weapon/equipment/artifact/active-item/consumable families and recipes;
- exact per-item rolled properties and probability distributions;
- exact upgrade progression and salvage recipe/output design;
- exact custom enchantments, if any, beyond vanilla mechanics;
- whether ordinary enchanted equipment always receives network item identity or only items whose individuality matters;
- precise high-value/unique-item drop restrictions in disposable zones.

## Map/PvE decisions

- exact initial environments, enemy-family count, modifier set, elite traits, and encounter layouts;
- exact Map material names and tier count;
- exact conditions under which a Map objective displays detailed progress versus only state/goal information;
- exact party-size limits and whether any Map types later permit late joining;
- exact boss mechanics and tier structure for each bounty family;
- exact names of higher-grade bounty materials beyond their family/tier role.

## Gameplay decisions

- exact ordinary PvE death consequence beyond the locked rule that protected bank money is safe and pocket money may be lost;
- exact Refining/Crafting commission UX and whether it ships on Day 0 or immediately after the base production loop;
- exact mechanism by which Enchanting skill XP is earned from Minecraft XP expenditure;
- exact rules preventing vanilla enchant combination from bypassing intentionally source-gated enchant tiers if such tiers exist;
- exact UI/interaction through which players declare/recognize the physical identity of a player-built district, if any explicit declaration is needed at all.

## Operations decisions

- exact local-PC process layout once real gameplay instances expand;
- threshold for splitting one Paper process into multiple backends;
- exact backend load signals used by later scheduling;
- backup cadence/retention policy before public alpha;
- exact closed-beta cohort size and creator embargo logistics.

## Explicit non-decisions — already locked

Do **not** reopen these during ordinary implementation:

- compact purpose-built zones rather than endless vanilla wilderness;
- horizontal zone-instance replication for concurrency;
- per-zone demand determines instance count; total network population does not;
- backend identity is infrastructure, not gameplay identity;
- PostgreSQL durable authority for critical persistent state/value movement;
- exactly one backend may mutate a player's persistent live state at a time;
- commodities use quantity accounting; individualized items use stable identity/custody;
- player prices are market-discovered;
- Bazaar handles fungible commodities; Auction House handles individualized rolled gear/maps by default;
- bounty-family materials are fully Bazaar-tradable;
- finished gear/materials are not soulbound by default;
- ownership may be unrestricted while use remains skill/content-gated;
- launch active skill cap is 50, later 75, much later 100; no hidden overflow XP above the active cap;
- Maps are a launch PvE pillar and Map difficulty is not player-level gated;
- difficulty values may exist beyond the currently achievable gear ceiling;
- Bounties are organized by mob category/family with multiple tiers and category-specialized gear;
- bounty fees unlock the contract/quest; players still complete category mob kills before summon access;
- bounty-family pouches are specialized storage/QoL, not binding or non-tradable custody;
- individualized gear uses bounded roll variance, roughly 10–30% low-to-high relevant value depending on item;
- perfect rolls are optional luxury optimization, not required progression;
- player voting determines future expansion direction and developers do not steer valid outcomes;
- ordinary player-built districts have no developer-authored blueprint, required appearance, or minimum block count;
- physical district form belongs to players;
- most ordinary districts expand capability horizontally; Nether/End are later major vertical-power milestones;
- Nether/End gear raises practical Map ceilings rather than unlocking Map difficulty numbers;
- ranked 1v1 PvP and controlled clan wars remain opt-in V1 competitive systems;
- Dungeons are deferred until Maps + Bounties are mature;
- public launch has one canonical Day-0 opening; closed/private beta progress is disposable;
- structure/system correctness dominates development effort; balance numbers remain cheap configuration where practical.