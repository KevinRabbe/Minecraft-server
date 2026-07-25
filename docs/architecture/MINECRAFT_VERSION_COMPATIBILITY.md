# Minecraft Version and Client Compatibility

Status: **Architecture policy with launch baseline under active research.** The compatibility/security principles below are locked; the final native Minecraft/Paper launch baseline is not yet selected.

## Current development baseline versus launch baseline

The repository currently compiles against a pinned Paper 26.1.2 stable build so development/CI is reproducible while version research continues. This is a **temporary development baseline**, not a settled launch decision.

The launch baseline should be the **oldest sufficiently modern, still-maintainable version that provides every engine/client capability this project materially needs**, unless player-access data or maintenance/security considerations justify a newer baseline.

That means a newer Minecraft release must justify its permanent migration/protocol/content surface rather than being adopted merely because it is newer.

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

## Version-selection principle

The research candidate range should begin where the modern engine features we actually need already exist.

Important technical cutoffs include:

- **1.20.5**: structured Item Stack Components, `minecraft:custom_data`, Java 21, and server transfer/cookie protocol support;
- **1.21.4**: substantially expanded `minecraft:custom_model_data` and resource-pack item-model flexibility useful to a mostly-custom item ecosystem;
- later 1.21 releases: additional item/entity/model capabilities that must be evaluated for actual product value rather than adopted automatically;
- 26.x: current maintenance line and Java 25, but with additional migration/version surface that must justify itself for this project.

Therefore pre-1.20.5 versions are poor baseline candidates unless later research finds an overwhelming player-access reason. Versions around late 1.21 are currently especially important candidates because they combine modern custom-item/runtime capabilities with a mature ecosystem.

## Player-base research

There is no authoritative public Mojang table giving active Java players by exact client version. Version choice must therefore combine several imperfect but useful signals:

- Paper/bStats server-version adoption;
- large public server compatibility matrices;
- ViaVersion ecosystem telemetry where available;
- mod/plugin ecosystem support;
- launcher/default-version behavior;
- direct beta telemetry from this server once public testing exists.

Server-list counts alone must not be treated as exact player share.

## Why not copy Hypixel's 1.8.9 baseline

Hypixel's long-lived 1.8.9 optimization is useful evidence that a network can decouple its gameplay from Mojang's current vanilla feature set. It is **not** evidence that 1.8.9 is the safest or simplest baseline for a new network.

Hypixel has a historical player/mod ecosystem and gameplay semantics built around that era. This project has no legacy installed base to preserve. Starting on 1.8.x would add old protocol/world/item limitations and a much larger compatibility burden before those costs provide product value.

## Security rule

"Older means the exploits are already known" is not sufficient as a security strategy.

Known exploits are only an advantage while the chosen runtime is still maintained or those fixes are intentionally backported. Paper recommends updating supported installations and does not guarantee indefinite exploit backports to older releases.

Security posture:

1. choose a version that remains practically maintainable;
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

The exact launch matrix remains open until the native baseline and real adoption data are selected.

Every supported client protocol becomes another representation of persistent gameplay state. Compatibility testing is especially important for:

- individualized/rolled items;
- inventory transactions and escrow;
- custom resource-pack models and item components;
- combat/input behavior;
- entity metadata;
- world height/block representation;
- GUIs, books, signs, chat and interactive components;
- Map/Portal instances;
- anti-dupe validation;
- future anti-cheat/input validation.

ViaVersion/ViaBackwards may be used, but a client version is supported only after these paths are proven correct. Technical connectability is not sufficient.

## Compatibility acceptance suite

Before adding or retaining a client version, test at minimum:

1. login, reconnect, transfer between network backends;
2. resource-pack acceptance and custom-model rendering;
3. full inventory click/move/stack behavior;
4. individualized item identity surviving inventory actions;
5. Bazaar/AH/direct-trade/commission custody paths;
6. combat input and active-use items;
7. chunk/world-height rendering in every supported world type;
8. entity metadata and custom mob presentation;
9. books/signs/chat/interactive UI components used by the network;
10. Portal/Map entry, death, reconnect and exit;
11. no packet/client-version path can bypass server-side validation;
12. no version-specific desync can produce duplicate or lost persistent value.

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
