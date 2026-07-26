# Deferred Empirical Acceptance

Status: **Deferred real-machine/client evidence.** These checks do not block independent architecture/code work. They must be executed as a batch before the affected capability is enabled for production.

The repository's automated suite proves authority, custody, idempotency, protocol isolation, fail-closed admission, runtime materialization structure, and terminal settlement. It cannot prove Minecraft-1.8.9 client feel or every Bukkit/vanilla behavior that depends on a real client/server session.

## Competitive legacy runtime

### Ranked 1v1 — real 1.8.9 client

Prerequisites:

- dedicated legacy backend behind Velocity;
- dedicated narrow PostgreSQL runtime principal;
- `supports_ranked_arena = TRUE`;
- two real 1.8.9 client sessions/accounts;
- disposable test ratings/state.

Evidence to capture:

1. Both players are routed only to the assigned legacy backend and an unrelated account is rejected.
2. The arena rebuilds with the configured deterministic environment and both players receive only the standardized temporary loadout.
3. Normal 1.8.9 sword hits, knockback, sprint-reset behavior, damage immunity frames, movement, and latency feel are acceptable for the intended Ranked category.
4. A player disconnect pauses effective Ranked combat; reconnect restores a runnable match without creating a second execution/result.
5. The first valid death closes local combat immediately and produces exactly one trusted winner settlement/rating update.
6. Drops, XP, block mutation, buckets, cross-execution damage, and temporary inventory cannot escape the disposable runtime.
7. Match timeout produces failure/cancellation rather than an invented winner.
8. Returning to the persistent MMO does not import the temporary 1.8.9 kit or other disposable player state.

### Clan War — baseline structural slice

Do **not** enable `supports_clan_war` in production merely to run ordinary gameplay. Use a disposable acceptance principal/backend or temporary test capability only.

Current automated representation contract:

- baseline `equipment.starter_sword -> IRON_SWORD` only;
- `roll_state = {}`;
- `upgrade_level = 0`;
- one frozen snapshot row maps to one normal 1.8 inventory slot;
- no truncation/merging/hidden storage;
- unsupported definitions, rolled gear, upgraded gear, or inventory overflow fail closed.

Evidence to capture:

1. A finalized `ROSTER_LOCKED` war is assigned only when the test backend explicitly has `supports_clan_war = TRUE`.
2. Every frozen roster participant must be online before combat opens; incomplete roster presence never opens the gate or renews an unmaterialized execution indefinitely.
3. The disposable Clan-War arena is separate from Ranked, rebuilds deterministically, and spawns the complete frozen roster at the configured symmetric positions.
4. Each baseline frozen item appears in the exact projected inventory slot and no persistent item UUID or MMO custody object exists inside the legacy JVM/client state.
5. Persistent real gear remains in PostgreSQL `WAR_CUSTODY` for the whole legacy execution.
6. Uncontested control-point presence advances the correct clan; contested or empty presence pauses; dead/spectator players do not contribute progress.
7. A participant death produces no item/XP leak and the current structural death rule keeps that participant out of objective play for the remaining local execution.
8. Disconnect/reconnect behavior is observed specifically for control progress, spectator/elimination state, temporary inventory, and location. Any reset that changes intended match semantics must be resolved before production capability is enabled.
9. Objective completion submits only one frozen clan-side winner, closes combat locally, settles rating exactly once, and returns all real gear from `WAR_CUSTODY` through trusted delivery.
10. Runtime timeout/failure returns gear without rating/winner fabrication.
11. Cancelling a still-`ROSTER_LOCKED` war while no capable backend is available returns custody safely.
12. Temporary database/backend interruption fails combat closed and does not leave a stale local gate blocking the next execution.

### Broader Clan-War gear remains code/content work

The following are **not** deferred empirical acceptance; they are intentionally unsupported implementation work and must remain fail-closed until explicitly defined:

- rolled-stat translation;
- upgrade translation;
- armor/equipment-slot semantics;
- additional active-use equipment representation;
- any V1 item whose combat behavior cannot be represented faithfully on Minecraft 1.8.9.

A new representation should first gain deterministic automated translation tests. Real-client acceptance then verifies its actual 1.8.9 behavior.

## Production capability rule

`supports_clan_war` remains `FALSE` for production principals until:

1. the accepted V1 Clan-War gear/representation set is implemented and green in CI; and
2. the relevant real-client Clan-War acceptance batch above has passed.

Failure of any empirical check freezes only that assumption/capability. It does not block unrelated MMO systems or independent competitive-control work.
