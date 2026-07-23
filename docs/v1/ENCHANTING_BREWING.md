# Enchanting and Brewing

## Principle

Reuse Minecraft's native enchanting and brewing interactions wherever they already work. Custom code controls progression, economy, availability, and rare/custom content around those systems.

## Vanilla-style enchanting

Players may use normal Enchanting Tables and Anvils in their own bases where appropriate.

Enchanting consumes normal Minecraft XP and Lapis according to the supported interaction.

## Baseline Enchanter/Witch

A City Enchanter/Witch may sell guaranteed baseline enchantment books for fixed, deliberately expensive Coin prices.

Purpose:

- provide a deterministic fallback to random enchanting
- create a Coin sink
- guarantee access to ordinary enchantments

The NPC catalog contains only ordinary/baseline enchantments and allowed tiers. It does **not** sell unique custom enchantments or rare endgame tiers.

## Rare and custom enchantments

The system must support enchantments not sold by the NPC. Future sources may include bosses, dungeons, events, rare ingredients, discoveries, or community-unlocked content.

Those future content sources are not themselves V1 requirements.

If a tier is intended to be source-exclusive, normal combining must not bypass that boundary. Example: if Efficiency IV must enter through rare content, ordinary III + III combining cannot manufacture IV.

Because enchanted books are non-stackable under the chosen item behavior, player resale routes through the Auction House.

## Enchanting skill

Enchanting is a 1-100 meta skill whose primary reward is an account-wide **Skill XP Amplifier** for other skills.

Rules:

- amplifies Mining/Woodcutting/Farming/Combat/Refining/Crafting and future Fishing XP
- never amplifies Enchanting XP itself
- is intentionally expensive to progress
- consumes real Minecraft XP through the designated progression operation
- must not become an obviously mandatory first skill

The exact XP-to-Enchanting progression curve and amplifier curve are balance configuration.

## Experience bottles

Experience bottles are tradable storage/access to normal Minecraft XP, not a direct Enchanting-skill ingredient.

V1 adds an economic recipe using Lapis (plus whatever bottle/material inputs are selected during balance/content freeze) to create a stackable experience-bottle item.

Using the bottle grants normal Minecraft XP. The bottle itself is therefore a Bazaar commodity.

This allows peaceful players to obtain enchanting XP economically rather than being forced into mob combat.

## Brewing

Use Minecraft Brewing Stands and recognizable brewing behavior.

V1 Nether access exists partly because brewing depends on valuable Nether materials such as:

- Nether Wart
- Blaze Powder/Blaze materials
- Glowstone Dust
- other explicitly enabled vanilla ingredients

Glowstone remains useful beyond brewing because builders also demand it.

Potion Brewing does not require a separate skill in V1 unless real gameplay later demonstrates a need.

A player may personally gather Nether ingredients or buy all required inputs from other players.

Before implementation, freeze the V1 potion allowlist and ensure every enabled ingredient has a legitimate renewable source.
