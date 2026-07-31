# Legacy Competitive Runtime Operations

Status: **Operational contract for the isolated Minecraft 1.8.9 competitive backend.**

The legacy runtime is intentionally an untrusted/disposable execution environment relative to persistent MMO authority. It receives only the narrow competitive runtime API and must never receive ordinary table privileges for player inventory, economy, item custody, ratings, or clan authority.

## One database login per backend

Each legacy backend uses one dedicated PostgreSQL login and one row in `competitive_runtime_principals`.

The values must agree:

```text
COMPETITIVE_BACKEND_ID == competitive_runtime_principals.backend_id
COMPETITIVE_DATABASE_USER == competitive_runtime_principals.database_role
```

Use a distinct database login for each backend. Do not reuse the persistent MMO/application login.

## Database privileges

Migration V78 revokes `PUBLIC` execution from the privileged runtime functions. Migration V86 replaces the original registration-by-heartbeat signatures with incarnation-fenced registration, heartbeat, and shutdown functions. A trusted database owner/operator grants the dedicated runtime login only:

1. `CONNECT` to the target database;
2. `USAGE` on schema `public`;
3. `EXECUTE` on the eight externally callable runtime functions below.

Example, replacing the placeholders with the real database/role names:

```sql
GRANT CONNECT ON DATABASE minecraft TO legacy_competitive_01;
GRANT USAGE ON SCHEMA public TO legacy_competitive_01;

GRANT EXECUTE ON FUNCTION public.competitive_runtime_register(UUID, INTEGER)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_heartbeat(UUID, INTEGER)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_mark_offline(UUID)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_poll_active(INTEGER)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_heartbeat_execution(UUID, BIGINT, INTEGER)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_submit_report(UUID, UUID, TEXT, UUID)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_find_player_execution(UUID)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_page_loadout(UUID, INTEGER, INTEGER, INTEGER)
    TO legacy_competitive_01;
```

`require_competitive_runtime_backend()` is an internal helper executed under the owning `SECURITY DEFINER` functions. Do **not** grant it directly to the runtime login.

V86 drops the old `competitive_runtime_heartbeat(INTEGER)` and `competitive_runtime_mark_offline()` functions. Existing runtime-role grants on those removed signatures do not transfer to the replacements; apply the UUID-signature grants above after deploying V86.

Do not grant the runtime role direct `SELECT`, `INSERT`, `UPDATE`, or `DELETE` privileges on persistent tables.

## Runtime incarnation lifecycle

Each legacy JVM generates one random UUID incarnation token. Its first successful control-plane call uses `competitive_runtime_register(UUID, INTEGER)`, which claims the principal's stable backend ID and rotates `backends.incarnation_id`. Recurring polls use `competitive_runtime_heartbeat(UUID, INTEGER)` with the same token, and shutdown uses `competitive_runtime_mark_offline(UUID)`.

A replacement JVM registers a new token. Any overlapping older JVM still has the same database login, but its heartbeat and shutdown calls update no row and fail closed because it no longer owns the current backend incarnation. Recurring heartbeat must never call the registration function; otherwise a stale process could reclaim authority after replacement.

## Principal registration

The trusted operator/application authority creates the matching principal row. Activity capabilities are explicit dispatch authority, not descriptive metadata.

Ranked has a qualified disposable 1.8.9 materializer. Clan War now has a structurally qualified control-point path for the **currently explicit baseline representation set**: sealed identity-free snapshots, exact roster/spawn coverage, exact non-truncating inventory projection, deterministic arena materialization, objective progress, death isolation, timeout/failure handling, and exactly-once winner reporting are wired and green in CI.

That does **not** make Clan War production-enabled. The `war.legacy_1_8_9@1` representation mapping is frozen in code and currently contains only baseline `equipment.starter_sword -> IRON_SWORD`. Operator YAML cannot override that mapping; stale `clan-war.representations` entries from older deployments are ignored. Rolled/upgraded items remain fail-closed, broader equipment placement semantics are not yet defined, and real 1.8.9 client combat/objective behavior still requires empirical acceptance. A change to combat representation requires an explicit code/ruleset compatibility review rather than a silent deployment edit. Production principals therefore remain Ranked-only:

```sql
INSERT INTO competitive_runtime_principals(
    database_role,
    backend_id,
    max_execution_lease_seconds,
    dispatch_enabled,
    max_active_executions,
    supports_ranked_arena,
    supports_clan_war
) VALUES (
    'legacy_competitive_01',
    'legacy-competitive-01',
    120,
    TRUE,
    1,
    TRUE,
    FALSE
);
```

Migration V79 defaults `supports_clan_war` to `FALSE`, including existing principal rows, and dispatch tests require an explicit opt-in before a ready Clan War can be assigned. Do not set it to `TRUE` until the accepted V1 legacy representation set and real-client Clan-War behavior have both been proven.

The current 36-slot projection is a Minecraft-1.8 representation feasibility boundary, **not** a player-facing Clan-War loadout cap. The persistent loadout authority remains unconstrained by that legacy client surface; a selection that cannot be represented faithfully fails closed rather than being truncated, merged, or silently reinterpreted.

Capacity and lease values are deployment tuning. Activity capability flags control which execution kinds the trusted dispatcher may assign to that backend; they do not change persistent match/war authority.

## Runtime environment

Required process environment:

```text
COMPETITIVE_BACKEND_ID
COMPETITIVE_DATABASE_URL
COMPETITIVE_DATABASE_USER
COMPETITIVE_DATABASE_PASSWORD
```

Optional:

```text
COMPETITIVE_EXECUTION_LEASE_SECONDS
```

The configured execution lease must not exceed the principal's `max_execution_lease_seconds`.

## Network boundary

The legacy backend stays behind Velocity/firewall isolation. Players enter the competitive category only through the proxy's competitive route and reconnect with the supported 1.8.9 client family. The backend must not be exposed as a normal persistent-MMO destination.

## Value boundary

The legacy runtime never owns persistent MMO value:

- Ranked uses disposable standardized state only.
- Clan-War gear remains in PostgreSQL `WAR_CUSTODY`.
- Clan-War runtime snapshots contain no persistent item UUID.
- A legacy process may only heartbeat its assigned execution and submit `WINNER`/`FAILURE` through the narrow API.
- Unsupported or unrepresentable Clan-War state fails closed; it is never flattened into different combat value.
- Runtime failure or lease expiry converges on trusted common-side settlement/recovery.

Any deployment change that grants broader database authority or enables an unaccepted activity capability requires an explicit architecture/security review before production use.
