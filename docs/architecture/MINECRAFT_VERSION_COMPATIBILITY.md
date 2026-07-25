# Minecraft Version and Client Compatibility

Status: **Canonical architecture policy.** The native launch target family is late **1.21.x**; the exact 1.21 point release remains under research. Older clients such as 1.8.9 are compatibility targets only and do not define authoritative combat semantics.

## Current development baseline versus launch baseline

The repository currently compiles against a pinned Paper 26.1.2 stable build so development/CI remains reproducible while the exact 1.21.x target is selected. This is a **temporary development baseline**, not the intended launch family.

The intended launch platform is late **1.21.x**, because it already provides the modern item/component/model and combat primitives this custom game materially benefits from without requiring the network to chase every later Mojang release.

The exact point release is selected by comparing player adoption, Paper maintenance, plugin/protocol support, item/resource-pack capabilities and migration cost.

## Platform rule

Minecraft is the network's client/world/combat platform, not the product ruleset.

Reuse vanilla/platform primitives when they save substantial implementation or compatibility work, but keep progression/economy/content authority in Steward.

Examples of useful platform primitives include:

- entity/world simulation;
- modern item components and resource-pack item models;
- attributes such as attack damage, attack speed and knockback;
- client attack-cooldown presentation on native modern clients;
- movement, collision and interaction packets;
- blocks/building/rendering used for the persistent world.

Examples of product rules Steward owns include:

- legal item/resource acquisition;
- weapon definitions and stat ranges;
- gear rolls/upgrades;
- damage/ability rules beyond selected platform primitives;
- Maps/Bounties;
- skills/progression;
- economy/custody;
- PvP formats and rewards.

## Combat baseline

The canonical gameplay model uses **modern Java combat primitives**, not authentic 1.8 server combat.

This is intentional. The platform already exposes attack-speed/cooldown and related combat attributes, allowing custom weapon families to receive distinct attack pacing without Steward implementing a complete independent attack-timer system from zero.

For example, a fast blade and a heavy hammer can use different authoritative attack-speed values while their special abilities, roll quality and other MMO mechanics remain Steward-owned.

The chosen Minecraft version still affects both PvE and PvP, so combat compatibility is part of the version acceptance matrix. Map bosses, Bounties, ranked Arena and Clan Wars should all be designed against the same canonical combat semantics unless a later feature explicitly proves that a separate ruleset is worth the complexity.

## Why not make 1.8.9 the native rules engine

1.8.9 remains relevant because many competitive Minecraft players prefer its combat feel. It should therefore be researched as an important client-access target.

However, making the entire MMO native 1.8.9 would trade away modern item/model/component and runtime capabilities that directly help a mostly-custom game. It would also require Steward to rebuild weapon attack pacing if it wanted differentiated attack speeds rather than universal legacy spam-click behavior.

Therefore:

- **late 1.21.x** is the full-fidelity target family;
- **1.8.9** is an optional compatibility target;
- supporting a 1.8.9 client does **not** change the server's canonical modern attack-speed rules into 1.8 combat.

## 1.8.9 compatibility consequences

A 1.8.9 client connected through ViaVersion/ViaBackwards/ViaRewind cannot represent every modern client feature faithfully.

Important limitations for this project include:

- modern custom item-model/component presentation may need degraded/fallback visuals;
- the 1.8 client has no native modern attack-cooldown indicator, so canonical modern attack pacing would need alternate feedback if 1.8 is supported in combat;
- clients older than 1.17 cannot represent modern world-height ranges outside the old 0..255 space through ViaBackwards;
- ViaBackwards documents inventory-desynchronization risks for sufficiently old clients on modern servers;
- newer entities, sounds, GUIs and metadata may require substitutions or may not be acceptable at all.

Because the server is economy-authoritative, 1.8.9 is supported only if its compatibility path passes the same custody/inventory tests as the native client.

If full-world compatibility proves unsafe or visually too degraded, 1.8.9 may be restricted to compatible isolated modes—or not supported—without changing the native 1.21.x game architecture.

## Positive-allowlist vanilla policy

The project replaces most vanilla progression, items, loot, economy and activity loops. Therefore vanilla content must be managed primarily by **allowing legitimate acquisition paths**, not by maintaining an ever-growing blacklist of individual vanilla items.

New Mojang items existing in the registry do not automatically become obtainable.

Authoritative acquisition sources include only explicitly configured systems such as:

- approved crafting/refining recipes;
- approved gathering/resource sources;
- approved mob/bounty/Map rewards;
- approved shops/system rewards where intentionally used;
- approved world containers/loot tables;
- approved player-to-player transfers of already legitimate assets.

Unapproved vanilla recipes, loot tables, villager trades, mob drops, fishing outputs, structure loot, natural resource paths and other acquisition mechanisms are disabled/replaced at their source where they conflict with the game.

This is safer and lower-maintenance than adding every new Mojang item to a deny list after each update.

## Version-selection principle inside 1.21.x

Important technical cutoffs include:

- **1.20.5**: structured Item Stack Components and `minecraft:custom_data`;
- **1.21.4**: substantially expanded `minecraft:custom_model_data` and resource-pack item-model flexibility useful to a mostly-custom item ecosystem;
- later 1.21 releases: additional capabilities and potentially larger active player share, but each must justify its migration/plugin/protocol surface.

Therefore the exact launch comparison now focuses primarily on **1.21.4 and later 1.21.x releases**, not on pre-modern versions.

## Player-base research

There is no authoritative public Mojang table giving active Java players by exact client version. Version choice must therefore combine several imperfect but useful signals:

- Paper/bStats server-version adoption;
- large public server compatibility matrices;
- ViaVersion ecosystem telemetry where available;
- mod/plugin ecosystem support;
- launcher/default-version behavior;
- direct beta telemetry from this server once public testing exists.

Server-list counts alone must not be treated as exact player share.

## Security rule

"Older means the exploits are already known" is not sufficient as a security strategy.

Known exploits are only an advantage while the chosen runtime is still maintained or those fixes are intentionally backported. Paper recommends updating supported installations and does not guarantee indefinite exploit backports to older releases.

Security posture:

1. choose a 1.21.x point release that remains practically maintainable;
2. pin the exact Paper build used by CI/release;
3. never float production on `build.+`;
4. run migration, protocol, inventory, economy, instance and recovery tests before any native upgrade;
5. keep backend servers behind the network/proxy boundary;
6. treat protocol compatibility as presentation/input compatibility, never as the authority boundary;
7. reject malformed/unauthorized actions server-side regardless of client version.

A newly released version can contain unknown bugs. A mature version can contain known-but-unpatched bugs. The correct control is explicit maintenance status + test gates, not blindly choosing either newest or oldest.

## Client-version policy

Native server version and accepted client versions are separate decisions.

Support a **small explicitly tested client matrix**, not every release that a protocol translator can technically accept.

Compatibility tiers are:

- **full fidelity** — selected native 1.21.x client/version family;
- **compatible** — nearby versions that pass all presentation/combat/inventory tests;
- **legacy experimental** — 1.8.9 and similarly old clients, accepted only if compatibility testing proves their limitations are tolerable for the enabled game modes.

Every supported client protocol becomes another representation of persistent gameplay state. Compatibility testing is especially important for:

- individualized/rolled items;
- inventory transactions and escrow;
- custom resource-pack models and item components;
- combat/input behavior and attack-speed feedback;
- entity metadata;
- world height/block representation;
- GUIs, books, signs, chat and interactive components;
- Map/Portal instances;
- anti-dupe validation;
- future anti-cheat/input validation.

ViaVersion/ViaBackwards/ViaRewind may be used, but a client version is supported only after these paths are proven correct. Technical connectability is not sufficient.

## Compatibility acceptance suite

Before adding or retaining a client version, test at minimum:

1. login, reconnect, transfer between network backends;
2. resource-pack acceptance and custom-model rendering/fallbacks;
3. full inventory click/move/stack behavior;
4. individualized item identity surviving inventory actions;
5. Bazaar/AH/direct-trade/commission custody paths;
6. canonical attack-speed feedback, combat input and active-use items;
7. chunk/world-height rendering in every supported world type;
8. entity metadata and custom mob presentation;
9. books/signs/chat/interactive UI components used by the network;
10. Portal/Map entry, death, reconnect and exit;
11. ranked Arena and Clan War combat parity sufficient for the supported tier;
12. no packet/client-version path can bypass server-side validation;
13. no version-specific desync can produce duplicate or lost persistent value.

A version that fails an economy/custody invariant is unsupported even if it can technically join.

## Native-upgrade rule

A native Minecraft/Paper upgrade is an infrastructure release, not a casual dependency bump.

Promotion requires:

- material product/maintenance benefit;
- supported Paper build;
- exact dependency pin;
- world-format backup/migration plan where applicable;
- clean build and PostgreSQL suite;
- clean Paper integration/compatibility suite;
- clean supported-client matrix;
- explicit review of newly introduced vanilla acquisition/mechanic surfaces;
- rollback/recovery plan for everything that can roll back.

The canonical world is never upgraded first and tested afterward.
