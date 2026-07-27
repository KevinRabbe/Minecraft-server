# Deferred Empirical Acceptance

Status: **Deferred real-machine/client evidence.** These checks do not block independent architecture/code work. They must be executed as a batch before the affected capability is enabled for production.

The repository's automated suite proves authority, custody, idempotency, protocol isolation, fail-closed admission, runtime materialization structure, terminal settlement, and PowerShell syntax. It cannot prove Minecraft-1.8.9 client feel or every Windows/Docker/Bukkit/vanilla behavior that depends on a real machine or client/server session.

## Local coherent backup/restore rehearsal

The local recovery scripts are code-complete enough for empirical qualification, but **do not mark backup/restore proven until this has run on the developer Windows machine with Docker Desktop**.

Prerequisites:

- current local network can start/stop cleanly through `infra/local/start.ps1`;
- Docker Desktop/PostgreSQL local compose service is healthy;
- repository is on a deliberate clean recovery commit;
- a disposable test state is acceptable to destroy/restore;
- `backup.ps1`, `restore.ps1`, `setup.ps1` and `start.ps1` are the qualified versions under test.

Evidence to capture:

1. Create representative persistent state that spans multiple authorities: carried commodity + individualized item, Coin pocket/bank, skill XP, at least one market/crafting/provenance record, Map/Bounty state, clan state, and vote/history state where available.
2. Make a small recognizable persistent-world change in City so database and world recovery can be compared to one exact point.
3. Immediately before a controlled stop, move a recognizable carried item to a different inventory slot after normal play has settled. Press `Ctrl+C` in the PowerShell supervisor and capture the shutdown output: Velocity must stop first, the supervisor must announce the bounded 10-second final-player logout drain, and Paper must receive its backend stop only after that drain completes.
4. Start once through Velocity and verify the just-before-stop inventory-slot change survived reconnect. This is the empirical proof that proxy-first `PlayerQuit` finalization completed before Paper shutdown. Stop cleanly again before taking the backup.
5. Confirm Velocity and every configured Paper backend are no longer reachable, then run `infra/local/backup.ps1` and retain its snapshot ID, `manifest.json`, `checksums.sha256`, `COMPLETE`, PostgreSQL dump and captured world set.
6. Restart the network and deliberately change both database-backed player/economic state and the recognizable persistent-world state after the backup point.
7. Stop the network again and restore the selected snapshot using `restore.ps1 -BackupPath <snapshot> -ConfirmRestore`.
8. Start through Velocity and verify the post-backup mutations are gone while the pre-backup representative state and City world change are restored together to the same recovery point.
9. Run `/integrity 100`; no unexplained CRITICAL issue may remain. Verify specifically inventory/item custody, Coin/bank, Bazaar/AH or equivalent market evidence, crafting/provenance, skill state versus XP evidence/current cap, Map/Bounty state, clan custody, voting/world-era/history read models and session ownership after reconnect.
10. Verify valuable recovery does not require any disposable Map/Bounty/competitive runtime world to survive. A disposable instance may be absent/recreated while persistent value/history remains correct.
11. Tamper with one copied backup file and prove checksum verification rejects the snapshot before destructive restore begins.
12. Remove or omit `COMPLETE` on a copied backup and prove restore rejects it before destructive work.
13. Simulate an interrupted restore only in a disposable test setup: leave/create `runtime/restore.in-progress`, then prove both `setup.ps1` and `start.ps1` refuse to proceed until the selected restore completes and clears the marker.
14. Prove a repository commit mismatch is rejected by default. Exercise `-AllowVersionMismatch` only as an explicit test escape hatch and never treat it as normal recovery procedure.
15. Repeat one restore after a deliberate first-attempt interruption/failure and verify the final successful recovery still produces one coherent authoritative state with no duplicate value.

Acceptance result to record:

- exact repository commit;
- backup snapshot ID;
- Windows/Docker/PostgreSQL versions;
- selected representative state before backup, after mutation, and after restore;
- captured supervisor logout-drain output and the just-before-stop inventory-slot result after reconnect;
- `/integrity` output;
- whether any manual intervention was required;
- every observed mismatch or recovery ambiguity, even if the final restore succeeded.

A failure freezes only the backup/restore release assumption. Fix the specific recovery boundary and rerun this batch; do not stop unrelated MMO implementation work.

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
