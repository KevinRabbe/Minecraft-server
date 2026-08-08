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
- recipe quantities, crafting/refining costs, and processing durations;
- exact rolled-stat ranges/distributions per item within the locked approximate 10–30% low-to-high value envelope;
- upgrade costs and salvage return values;
- Bazaar/AH/direct-trade fees;
- bounty contract fees, kill requirements, boss numerical scaling, and exact material drop rates;
- Map difficulty curves, Coin reward curves, Map-material quantities, map-drop progression variance, modifier strengths, elite frequency, and exact supported visible difficulty range;
- exact leaderboard cache/refresh intervals;
- Witch/bootstrap prices and exact pre-Nether bootstrap allowlist;
- clan member cap;
- ranked-PvP rating constants;
- clan-war entry/resource costs and reward values;
- exact pre-launch announcement/countdown duration (~30 days is only an example).

## World/content decisions

- final names/visual themes and exact build layouts of the Hub Region and compact activity regions;
- exact first ordinary starter-combat elite identity/placement used as the renewable first-Map source;
- exact portal placement/visual treatment inside each regional map;
- which zones keep a warm instance versus scale to zero when unused;
- final persistent City strategy if City concurrency eventually exceeds one physical copy;
- exact content/theme of future portal-chain regions beyond the locked initial expansion candidates;
- exact content/theme of each future player-built district beyond already locked examples/directions;
- exact activation/completion signal for exceptional major world projects such as Nether/End if they require more than the authoritative vote/feature transition; no developer-authored physical blueprint or minimum block count may be introduced implicitly.

## Item/content decisions

- exact per-item rolled properties and probability distributions;
- exact upgrade progression and salvage recipe/output design;
- exact recipe quantities and Crafting-XP values for the locked V1 recipe-source graph;
- exact custom enchantments, if any, beyond vanilla mechanics;
- whether ordinary enchanted equipment always receives network item identity or only items whose individuality matters;
- precise high-value/unique-item drop restrictions in disposable zones.

## Map/PvE decisions

- exact encounter layouts and runtime materialization details for Forgotten Bastion, Flooded Depths, and Windscar Ruins;
- exact vanilla/custom runtime bases and behavior composition for Relic Guard, Deep Brood, and Ruin Raiders;
- exact conditions under which a Map objective displays detailed progress versus only state/goal information;
- exact party-size limits and whether any Map types later permit late joining;
- exact numerical/timing details of Rootborn, Ashbound, and Veilborn boss encounters beyond the locked family identities/mechanic language;
- exact runtime implementation order for Elite Hunt, Fortified, Relentless, Swarming, Bulwark, Hunter, and Volatile.

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

- the Hub is a compact starter civic core with simple launch buildings and all essential launch NPCs/services;
- starter Combat, Woodcutting, Mining, and Farming spaces are directly walkable from the Hub;
- later normal-world regions are small/dense and connected primarily through **spatial portal chains** rather than a central destination-selector portal;
- players normally traverse the current region to reach its onward portal so regional geography remains part of play;
- the initial combat chain is `Hub / starter Combat -> Rootborn Region -> Ashbound Region -> Veilborn Region`;
- the first expansion-candidate pool is Deeper Woodcutting Region vs Deeper Mining Region vs Deeper Farming Region;
- backend identity is infrastructure, not gameplay identity, and portal/world boundaries may align with backend transfers without turning travel into a menu;
- horizontal zone-instance replication for concurrency;
- per-zone demand determines instance count; total network population does not;
- PostgreSQL durable authority for critical persistent state/value movement;
- exactly one backend may mutate a player's persistent live state at a time;
- commodities use quantity accounting; individualized items use stable identity/custody;
- player prices are market-discovered;
- Bazaar handles fungible commodities; Auction House handles individualized rolled gear/maps by default;
- bounty-family materials are fully Bazaar-tradable;
- finished gear/materials are not soulbound by default;
- ownership may be unrestricted while use remains skill/content-gated;
- launch active skill cap is 50, later 75, much later 100; no hidden overflow XP above the active cap;
- Maps are a launch PvE pillar and capital-M Maps are separate instanced challenge content from normal-world Bounty regions;
- first Map acquisition comes from a renewable authored starter-combat elite encounter and does not require Bounty completion, Coin, a vendor purchase, or crafting;
- successful Map clears are the initial fresh-player Coin faucet, using the controlled Coin wallet authority with idempotent per-run/player payout identity;
- the canonical V1 Map pool uses Forgotten Bastion / Flooded Depths / Windscar Ruins, Relic Guard / Deep Brood / Ruin Raiders, Extermination / Elite Hunt, Fortified / Relentless / Swarming, and Bulwark / Hunter / Volatile;
- V1 Map materials are Relic Alloy, Resonant Crystal, and Waystone Shard, without a separate numbered rarity ladder;
- Bounties use the original Rootborn, Ashbound, and Veilborn enemy ecosystems rather than one-to-one vanilla mob categories;
- Bounty families inhabit normal-world activity regions rather than Bounty dungeons/Map instances;
- the launch Bounty envelope is two tiers per family, four normal creature roles plus one boss identity per family;
- the locked family-material ladders are Root Fiber -> Ancient Resin -> Heartwood Core, Cinder Shard -> Blackglass -> Kilnheart, and Veil Thread -> Phaseglass -> Gate Fragment;
- the launch allowlist is 28 meaningful items and the recipe-source relationships are locked in issue #104; exact quantities remain tuning;
- boss components are limited to the signature launch recipes Thornhook / Kilnbreaker / Phase Anchor;
- bounty-family pouches are specialized storage/QoL, not binding or non-tradable custody;
- individualized gear uses bounded roll variance, roughly 10–30% low-to-high relevant value depending on item;
- perfect rolls are optional luxury optimization, not required progression;
- player voting determines future expansion direction and developers do not steer valid outcomes;
- ordinary player-built districts have no developer-authored blueprint, required appearance, or minimum block count;
- a normal build project's material cost is the **actual Minecraft blocks builders place**, not a second abstract material-deposit requirement;
- physical district form belongs to players;
- most ordinary districts expand capability horizontally; Nether/End are later major vertical-power milestones;
- Nether/End gear raises practical Map ceilings rather than unlocking Map difficulty numbers;
- ranked 1v1 PvP and controlled clan wars remain opt-in V1 competitive systems;
- Dungeons are deferred until Maps + Bounties are mature;
- public launch has one canonical Day-0 opening; closed/private beta progress is disposable;
- structure/system correctness dominates development effort; balance numbers remain cheap configuration where practical.
