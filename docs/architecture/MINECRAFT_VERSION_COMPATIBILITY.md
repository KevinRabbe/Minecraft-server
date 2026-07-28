# Minecraft Version and Client Compatibility

Status: **Canonical architecture policy.** The network intentionally has separate Minecraft-version categories instead of forcing one compatibility matrix across every game mode.

## Category split

### Persistent MMO category

The persistent MMO uses a late **1.21.x** native Paper backend. The exact 1.21 point release remains under research.

This category owns:

- City and persistent world spaces;
- gathering/refining/crafting;
- Bazaar, Auction House, Bank and direct trade;
- Maps and Bounties;
- persistent inventory and individualized items;
- clans/social state outside competitive matches;
- expansion voting, Chronicle/history and hidden-artifact exploration;
- all ordinary PvE progression.

Only the selected modern client family and nearby explicitly tested compatible versions may enter these backends.

### Competitive PvP category

Ranked Arena and Clan Wars use a dedicated **1.8.9** gameplay category.

The 1.8.9 client is intentionally required for those modes rather than translated into the persistent 1.21 MMO world.

This gives competitive modes authentic legacy combat semantics without forcing the entire MMO to inherit the 1.8 item/world/runtime platform.

The categories are separate products on the same network authority:

`modern MMO client/backend -> persistent MMO systems`

`1.8.9 client/backend -> ranked Arena / Clan Wars`

There is no requirement that one client version can enter every backend.

## User transition rule

A player entering a category their current Minecraft client cannot represent must disconnect, launch the required supported client version and reconnect.

The network may use the same Velocity entry layer or separate public hostnames, but version routing must never silently translate a player into a category whose rules/client assumptions do not match their protocol.

The UX cost of switching versions is accepted in exchange for a much smaller and safer compatibility surface.

## Why the split is simpler

The project does **not** need to prove that a 1.8.9 client can safely represent:

- modern persistent inventories;
- rolled/custom items;
- Map objects;
- Bazaar/Auction House/direct-trade custody;
- modern world height;
- modern custom-model components;
- every PvE entity/mechanic.

And the modern MMO does **not** need to reproduce authentic 1.8 PvP purely for compatibility.

Each category is tested against the client/runtime it is designed for.

## Platform rule

Minecraft is the network's client/world/combat platform, not the product ruleset.

Reuse platform primitives when they remove real engineering work, while Steward owns progression, economy, content and persistent authority.

For the modern MMO, useful primitives include:

- entity/world simulation;
- modern item components and resource-pack item models;
- attack damage, attack speed and knockback attributes;
- movement, collision and interaction packets;
- blocks/building/rendering used for the persistent world.

The modern MMO may choose any attack-speed values its weapon designs require. High attack speed is merely an available design parameter, not a requirement to imitate 1.8 combat.

The competitive 1.8.9 category intentionally uses legacy combat behavior for Arena/Clan Wars.

## Persistent authority across categories

Minecraft backend version never becomes ownership authority.

PostgreSQL/common network authority remains canonical across both categories.

### Ranked Arena

Ranked Arena uses standardized temporary loadouts.

The 1.8.9 backend receives only the match participants, temporary combat configuration and authoritative match identity. It does not receive ownership of the player's persistent MMO inventory.

It returns an authoritative match result which is applied exactly once to rating/history.

### Clan Wars

Real economic equipment participating in a Clan War remains in network-side **WAR_CUSTODY**.

The 1.8.9 backend receives a temporary combat snapshot derived from the authorized persistent equipment, for example:

- weapon/armor combat statistics;
- allowed abilities translated into the legacy-mode vocabulary;
- match-specific consumable/resources;
- visual 1.8-compatible temporary representations.

The legacy backend never becomes the owner of the real `ItemId` and never writes persistent item inventory directly.

After the match, deterministic settlement/history operates against the authoritative custody records.

This means modern custom-item representation does not need to be translated losslessly into 1.8 NBT/items.

## Legacy-backend isolation

Because 1.8.9 is an old runtime, its blast radius must be deliberately small.

The competitive backend should be:

- reachable only through the network proxy/firewall boundary;
- isolated from direct public backend access;
- given the minimum database/service permissions required for match execution;
- unable to mutate ordinary persistent inventory/economy state directly;
- disposable/replaceable between matches where practical;
- limited to a very small audited plugin/runtime surface;
- treated as an untrusted execution environment relative to persistent authority.

Compromising or crashing a legacy PvP backend must not grant ownership of persistent MMO value.

## Positive-allowlist vanilla policy

The persistent MMO replaces most vanilla progression, items, loot, economy and activity loops. Vanilla content is therefore managed primarily by **allowing legitimate acquisition paths**, not by maintaining an ever-growing item blacklist.

New Mojang items existing in the registry do not automatically become obtainable.

Authoritative acquisition sources include only explicitly configured systems such as:

- approved crafting/refining recipes;
- approved gathering/resource sources;
- approved mob/Bounty/Map rewards;
- approved shops/system rewards where intentionally used;
- approved world containers/loot tables;
- approved player-to-player transfers of already legitimate assets.

Unapproved vanilla recipes, loot tables, villager trades, mob drops, fishing outputs, structure loot, natural resource paths and other acquisition mechanisms are disabled/replaced at their source where they conflict with the game.

## Modern MMO version selection

The exact modern baseline remains a late 1.21.x research decision.

Important technical cutoffs include:

- **1.20.5**: structured Item Stack Components and `minecraft:custom_data`;
- **1.21.4**: substantially expanded custom-model/item-model flexibility useful to a mostly-custom item ecosystem;
- later 1.21 releases: potentially larger active player share and additional capabilities, but each must justify migration/plugin/protocol cost.

The final 1.21 point release is selected from player adoption, Paper maintenance, plugin/protocol support, custom-item/model capability, security and migration cost.

The repository's current pinned Paper 26.1.2 dependency remains only a temporary development/CI baseline until this selection is implemented.

## Security rule

"Older means the exploits are already known" is not sufficient by itself.

The modern MMO should use a practically maintained Paper build and exact dependency pinning.

The legacy 1.8.9 competitive backend is acceptable only because its responsibilities and authority are intentionally constrained. Security depends on isolation and minimal authority, not on assuming the old runtime is safe.

Across both categories:

1. backend servers remain behind the proxy/firewall boundary;
2. clients never become economic authority;
3. malformed/unauthorized actions are rejected server-side;
4. persistent-value changes remain idempotent/auditable;
5. protocol/backend compromise must not bypass PostgreSQL/common authority;
6. production versions/builds are pinned and promoted through explicit tests.

## Compatibility acceptance

### Modern MMO category

Before accepting a modern client version, prove at minimum:

1. login/reconnect/backend transfer;
2. resource-pack and custom-model rendering;
3. full inventory movement/stack behavior;
4. individualized item identity;
5. Bazaar/AH/direct-trade/commission custody;
6. modern combat and active-use items;
7. world/chunk/entity representation;
8. Maps/Bounties and death/reconnect recovery;
9. no protocol-specific duplicate/lost persistent value.

### 1.8.9 competitive category

Prove at minimum:

1. login/routing to legacy competitive backends only;
2. ranked temporary-loadout correctness;
3. Clan War combat-snapshot mapping;
4. authentic intended legacy hit/knockback/combat behavior;
5. match reconnect/timeout/failure handling;
6. exactly-once rating/result/history settlement;
7. WAR_CUSTODY cannot be bypassed or duplicated;
8. legacy backend cannot mutate ordinary persistent inventory/economy state;
9. no path from the competitive backend into modern MMO worlds without reconnecting under an accepted modern protocol.

## Upgrade rule

Modern MMO native-version upgrades are infrastructure releases and require migration/compatibility acceptance before touching the canonical world.

The legacy competitive category may remain on 1.8.9 independently as long as its isolated runtime remains operationally acceptable. Modern MMO upgrades therefore do not automatically force PvP combat-version changes.
