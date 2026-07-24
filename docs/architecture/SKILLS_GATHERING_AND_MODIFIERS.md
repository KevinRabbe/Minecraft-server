# Skills, Gathering, and Modifiers

## Launch/early skills

The generic progression framework supports at minimum:

- Mining
- Woodcutting/Foraging
- Farming
- Combat-related progression where useful
- Refining
- Crafting
- Enchanting
- bounty-family progression such as Spider, Zombie, and Golem

Fishing is not required on Day 0 and may be introduced through a player-selected Fishing expansion/district.

## Progression philosophy

Skills support specialization and optional routes. No skill should become a universal mandatory path merely because it exists.

Players are not assigned permanent classes. Time investment, capability unlocks, equipment, capital, and market access create specialization; players may broaden later.

## Staged active caps

The progression framework has a world-era/configured **active cap**:

- Day-0/launch era: 50
- later expansion era: 75
- much later era: 100

Exact timing and XP curves are balance/content configuration.

Rules:

1. XP cannot accumulate invisibly beyond the active cap.
2. raising the cap reopens progression from the player's committed capped state;
3. earlier milestone rewards/unlocks do not reissue when a cap rises;
4. a skill may define fewer meaningful rewards than the global active cap, but it cannot silently bypass the active cap;
5. cap state belongs to feature/world configuration, not one player's local client state.

## Skill XP versus skill benefit

These are separate decisions.

An action/resource may benefit from a skill's speed/luck/tool effects without being a valid XP source for that skill.

Example: sand can benefit from Mining-oriented extraction bonuses while granting no Mining XP if it is not an authorized Mining training source.

This prevents accidental optimal training routes from being defined by tool type alone.

## Authorized economic sources

Economic output/XP is granted only from authorized source state, not arbitrary player-placed blocks/entities.

Conceptual validation:

1. player action occurs;
2. target belongs to an authorized zone/source node/cycle;
3. target is in a valid state (mature, alive, not already consumed, etc.);
4. player/use requirements are valid;
5. base yield is determined;
6. skill/tool/enchantment/context modifiers are applied;
7. authoritative output/XP is created once using a stable operation/event identity where retries are possible;
8. source enters respawn/reset/next-cycle state.

Player-place -> break loops must not mint XP/resources unless the source is explicitly designed to do so.

## Woodcutting / Foraging

Planned mechanics:

- skill speed
- skill luck/quality where meaningful
- better axes/use requirements
- connected valid-tree breaking at higher progression
- specialized Wood pouch where throughput justifies it
- authorized tree sources only

High level should increase useful throughput without turning into AFK automation.

## Mining

Mining is an extraction profession, not merely "pickaxe use".

Potential Mining-benefit materials may include ores, stone, sand/gravel, Quartz/Glowstone/Soul Sand later, while XP eligibility remains separately configured.

Planned mechanics:

- speed
- luck/quality where meaningful
- better tools/use requirements
- multi-block/vein-style manual extraction at higher progression
- Mining pouch
- authorized nodes/sections and reset/respawn behavior

## Farming

Planned mechanics:

- speed
- luck/yield where meaningful
- better tools/multi-harvest
- Farming pouch
- authoritative crop/livestock cycles where needed

Only valid mature/legitimate cycles grant economic output/XP. Plant/break spam or immature-cycle abuse must not.

## Combat

Combat progression may govern PvE effectiveness/equipment requirements/drop rules as needed.

- normal mobs may grant Minecraft XP and/or configured progression;
- Portal/Map difficulty is **not** permission-gated by Combat level;
- Bounty tier access may depend on the relevant bounty-family progression rather than one global combat ladder;
- ranked PvP disables permanent gear/skill advantage through standardized temporary state;
- clan war intentionally uses real economic equipment/resources through separate custody/settlement rules.

## Bounty-family progression

A bounty family is a specialization track tied to one mob category, for example Spider, Zombie, or Golem.

Progression may unlock:

- higher bounty tiers;
- higher-grade family material access through those tiers;
- family-specific recipes/use requirements where appropriate;
- larger family pouch capacity/QoL;
- other category-specific capability.

Do not make bounty progression a generic infinite `+damage%` ladder. Much of the combat specialization should come from equipment/build choices.

Bounty materials remain Bazaar-tradable. A player does not need personal progression in every family merely to buy/own/craft with its fungible materials unless an explicit use requirement says otherwise.

## Refining

XP comes from legitimate irreversible processing, not reversible compression.

Specialization may increase:

- processing speed
- capacity/concurrency
- recipe access
- modest material/resource efficiency where economically safe

Do not create bonus-output resource generation without an explicit economic design reason.

## Crafting

XP comes from legitimate recipe completion.

Specialization may increase:

- recipe access;
- speed/batch convenience;
- capacity/concurrency;
- modest material efficiency or fee reduction where configured.

Crafting level should **not** create a runaway perfect-roll monopoly. Individualized gear roll quality is generated authoritatively from the item's configured roll profile and remains fundamentally an item-instance property.

Dedicated crafters remain valuable through access, throughput, capital, market knowledge, and volume even when the roll distribution itself is not heavily skill-biased.

## Enchanting

Enchanting retains normal Minecraft XP as the operational enchanting resource where suitable.

Its progression may amplify configured XP gain for other skills but must never amplify itself. It obeys the same staged active-cap rules.

## Modifier pipeline

Keep calculation centralized and deterministic.

Conceptual sources:

`base action/item -> player skill -> tool/equipment -> enchantment -> temporary effect/context -> final result`

Do not persist derived effective values that can be recomputed from authoritative inputs.

Map modifiers/bounty context may contribute through explicit context inputs rather than one-off event-handler arithmetic.

## Throughput target philosophy

High skill progression should feel materially stronger. Large effective throughput improvements can come from the product of several understandable mechanics:

- skill speed
- luck/yield
- tool speed
- manual multi-block interaction
- inventory-friction reduction via pouches

Do not solve progression with a single giant opaque multiplier.

## Pouches

Pouches are convenience/progression tools, not generic storage.

Two broad uses are allowed:

### Gathering pouches
Examples: Mining, Woodcutting, Farming, later Fishing.

### Bounty-family pouches
One pouch may store the fungible materials for one bounty family such as Spider/Zombie/Golem.

Common rules:

- explicit resource-family allowlist;
- persistent capacity/state;
- eligible authorized drops may route directly into them;
- capacity/tier numbers remain balance configuration;
- moving/selling contents uses the same authoritative commodity accounting as normal inventory;
- pouch custody never changes Bazaar tradability.

## No gameplay automation

Permitted automation removes friction after player action. It must not create resources while the player is absent.