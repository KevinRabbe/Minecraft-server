# Skills, Gathering, and Modifiers

## Launch skills

- Mining
- Woodcutting
- Farming
- Combat
- Refining
- Crafting
- Enchanting

Fishing is deferred until the Fishing feature is introduced.

## Progression philosophy

Skills support specialization and optional routes. No skill should become a universal mandatory path merely because it exists.

Level cap is currently planned around 100. Exact XP curves are balance configuration.

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
7. authoritative output/XP is created once;
8. source enters respawn/reset/next-cycle state.

Player-place -> break loops must not mint XP/resources.

## Woodcutting

Planned mechanics:

- skill speed
- skill luck
- better axes/use requirements
- connected valid-tree breaking at higher progression
- specialized Wood pouch
- authorized tree sources only

High level should increase useful throughput without turning into AFK automation.

## Mining

Mining is an extraction profession, not merely "pickaxe use".

Potential Mining-benefit materials may include ores, stone, sand/gravel, Quartz/Glowstone/Soul Sand later, while XP eligibility remains separately configured.

Planned mechanics:

- speed
- luck
- better tools/use requirements
- multi-block/vein-style manual extraction at higher progression
- Mining pouch
- authorized nodes/sections and reset/respawn behavior

## Farming

Planned mechanics:

- speed
- luck
- better tools/multi-harvest
- Farming pouch
- authoritative crop/livestock cycles where needed

Only valid mature/legitimate cycles grant economic output/XP. Plant/break spam or immature-cycle abuse must not.

## Combat

Combat governs PvE effectiveness/equipment requirements/drop rules as needed.

- normal mobs may grant Minecraft XP
- ranked PvP disables permanent gear/skill advantage through standardized temporary state
- clan war intentionally consumes real economic equipment/resources through separate settlement rules

## Refining

XP comes from legitimate irreversible processing, not reversible compression.

Specialization may increase:

- processing speed
- capacity/concurrency
- recipe access

Do not create bonus-output resource generation without an explicit economic design reason.

## Crafting

XP comes from legitimate recipe completion.

Specialization may increase:

- speed
- capacity/concurrency
- recipe access

V1 does not require random item quality.

## Modifier pipeline

Keep calculation centralized and deterministic.

Conceptual sources:

`base action/item -> player skill -> tool/equipment -> enchantment -> temporary effect/context -> final result`

Do not persist derived effective values that can be recomputed from authoritative inputs.

## Throughput target philosophy

High skill progression should feel materially stronger. Large effective throughput improvements can come from the product of several understandable mechanics:

- skill speed
- luck
- tool speed
- manual multi-block interaction
- inventory-friction reduction via pouches

Do not solve progression with a single giant opaque multiplier.

## Pouches

Pouches are convenience/progression tools, not generic storage.

- resource-family allowlist
- skill-gated use
- persistent capacity/state
- eligible authorized drops may route directly into them
- capacity/tier numbers remain balance configuration

## No gameplay automation

Permitted automation removes friction after player action. It must not create resources while the player is absent.
