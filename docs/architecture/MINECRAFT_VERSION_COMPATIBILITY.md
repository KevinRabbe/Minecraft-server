# Minecraft Version and Client Compatibility

Status: **Canonical architecture policy.** This document defines the native Minecraft/Paper version policy and the supported client-protocol policy. Exact supported client versions may move forward over time, but the compatibility principles below remain stable.

## Decision

Steward's Minecraft network does **not** intentionally target an old Minecraft server version merely because most vanilla content is replaced or ignored.

The native backend tracks the **latest Paper release that Paper itself marks stable**, after the repository's full build/integration suite passes on that version.

Current development baseline (July 2026):

- Minecraft/Paper: **26.1.2**
- Paper build: **74 stable**
- Java: **25**
- Paper API dependency is pinned exactly in source; production/release builds must not float on `build.+`.

Minecraft 26.2 is already a Mojang release, but Paper's public download channel currently still presents 26.1.2 as the stable build and 26.2 as experimental. Therefore the native backend remains 26.1.2 until Paper promotes a 26.2 build to stable and the migration/compatibility test matrix passes.

## Why not copy Hypixel's 1.8.9 baseline

Hypixel's long-lived 1.8.9 optimization is useful evidence that a network can decouple its gameplay from Mojang's current vanilla feature set. It is **not** evidence that 1.8.9 is the safer or simpler baseline for a new network.

Hypixel has a historical player/mod ecosystem and gameplay semantics built around that era. This project has no legacy installed base to preserve. Starting on 1.8.x would instead add protocol translation, legacy world/item limitations, older-client behavior, and a much larger compatibility test matrix before those costs provide any product value.

The project can ignore unwanted vanilla mechanics while still using a modern Paper runtime, current protocol hardening, modern item/data APIs, current world formats, current client rendering/resource-pack capabilities, and current Java support.

## Security rule

"Older means the exploits are already known" is not sufficient as a security strategy.

Known exploits only become an advantage if somebody continues to patch/backport them. Paper explicitly recommends updating to the latest supported release and states that exploit fixes are not necessarily backported after a newer version has been stable for a while.

Therefore the security posture is:

1. use a currently supported Paper stable release;
2. pin the exact Paper build used by CI/release;
3. move forward intentionally when a newer Paper release becomes stable;
4. run migration, protocol, inventory, economy, instance, and recovery tests before promotion;
5. never rely on an unsupported legacy server merely because its historic exploits are well documented;
6. keep backend servers behind the network/proxy boundary rather than treating client protocol compatibility as a security boundary.

A newly released version can still contain unknown bugs. The control is a stable-channel + test-gate policy, not permanent version stagnation.

## Client-version policy

Native server version and accepted client versions are separate decisions.

### Launch principle

Support a **small explicitly tested client matrix**, not every Minecraft release that a protocol translator can technically accept.

For the current 26.1.2 backend the intended initial matrix is:

- **26.1.2** — native/reference client;
- **26.2** — compatibility target through a maintained ViaVersion release after the compatibility suite passes.

Older clients (including 1.8.9 and the broad 1.21.x family) are **not launch requirements**. They may be added later only when player demand justifies the permanent testing cost and the version can represent the network's gameplay correctly.

When the native backend moves to 26.2, support for 26.1.2 may temporarily be retained through ViaBackwards only if the same compatibility tests pass. Compatibility is never assumed solely because ViaBackwards can translate the protocol.

## Why the client matrix stays narrow

Every additional client protocol becomes another representation of persistent gameplay state. For this project that matters especially for:

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

ViaBackwards documents real limitations for older clients, including world-height visibility and some inventory desynchronization on sufficiently old protocols. A protocol being connectable does not mean it is acceptable for an economy-authoritative MMO server.

## Protocol translation

ViaVersion/ViaBackwards may be used as compatibility infrastructure, but they are not part of game authority.

Rules:

- authoritative gameplay remains expressed in the native server model;
- translated clients never change item/economy identity semantics;
- protocol support is enabled only after automated/manual compatibility acceptance;
- the supported-version list is explicit and reject-by-default outside that list;
- protocol-plugin upgrades are pinned and promoted through the same release process as other infrastructure.

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

## Upgrade rule

A native Minecraft/Paper version upgrade is an infrastructure release, not a casual dependency bump.

Promotion requires:

- Paper stable-channel availability;
- exact dependency pin;
- world-format backup/migration plan where applicable;
- clean build and PostgreSQL test suite;
- clean Paper integration/compatibility suite;
- clean supported-client matrix;
- rollback/recovery plan for everything that can roll back (while respecting world formats that Paper/Minecraft explicitly cannot downgrade).

The canonical world is never upgraded first and tested afterward.
