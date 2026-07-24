# Enchanting and Brewing

## Enchanting role

Enchanting is a real skill using the same staged active-cap framework as other skills:

- launch active cap: 50
- later expansion: 75
- much later era: 100

Its primary systemic role is intentionally narrow: amplify XP earned in **other** skills.

It must never amplify Enchanting XP itself.

Conceptually:

`other_skill_xp_earned × enchanting_amplifier(level)`

The exact XP/amplifier curves are balance configuration.

No Enchanting XP accumulates invisibly above the currently active cap.

## Why this stays narrow

Enchanting should be optional capital investment, not a mandatory production profession or giant standalone subgame.

A player may:

- ignore Enchanting
- invest in it early
- make money through another path first and invest later

No route is globally correct.

## Normal Minecraft XP

Enchanting uses normal Minecraft XP as the actual operational resource.

XP bottles are only a tradable/storable way to obtain normal XP after use.

The project should not invent a second parallel "enchanting energy" currency without a demonstrated need.

## Minecraft enchanting/anvil mechanics

Retain Minecraft mechanics where they already provide useful gameplay:

- Enchanting Table
- Anvil
- Lapis
- normal XP
- vanilla-style randomized enchanting

Custom validation may enforce project-specific source/tier/use rules, but avoid rebuilding the entire UI/mechanic unnecessarily.

## Enchanting skill XP

The exact conversion from normal XP spent/invested into Enchanting skill XP remains open and must avoid self-feedback exploits.

Requirements:

- Enchanting XP cannot be multiplied by the Enchanting skill itself;
- repeated reversible actions cannot generate infinite Enchanting XP;
- source/tier restrictions cannot be bypassed through vanilla combination if such restrictions exist;
- active skill cap is enforced exactly like other skills;
- a later cap increase reopens progression without duplicating earlier unlocks/rewards.

## Enchantment model

The architecture supports:

- vanilla enchantments;
- a small future custom-enchantment set;
- valid item categories;
- max levels/source rules.

Do not ship dozens of custom enchantments simply because the framework can represent them.

Enchantments remain separate from intrinsic rolled-item quality and upgrade state.

## Witch/Apothecary

The starter region contains a small Witch/Apothecary function that provides:

- basic guaranteed enchantment books at expensive fixed Coin prices;
- basic Nether-derived brewing inputs before Nether access opens;
- nearby brewing interaction where useful.

The Witch is a bootstrap supplier, not the long-term source of rare/custom/endgame enchantments.

## Brewing

Use Minecraft brewing as the launch mechanic where practical:

- Brewing Stand;
- potion inputs;
- Nether Wart;
- Blaze Powder;
- Glowstone/redstone/etc. where allowed.

V1 does not require a separate Alchemy skill.

## Pre-Nether supply

Because Nether access is locked at Day 0, basic brewing remains functional through controlled bootstrap supply.

Natural mass supply later comes from unlocked Nether content and should alter player-market supply rather than replacing the Witch through a hard switch.

Nether accessibility is determined by player-directed feature state, not by the Witch/backend existing.

## Market routing

Fungible/stackable brewing inputs/potions follow normal Bazaar rules.

Individualized/non-fungible enchanted books/items follow Auction House rules where identity matters.

## Configuration

Balance/config owns:

- Enchanting XP curve;
- XP amplifier curve;
- staged-cap reward/unlock content;
- Witch prices/allowlist;
- allowed potion inputs/effects;
- source-gated enchant tiers if any;
- normal XP -> Enchanting XP conversion.