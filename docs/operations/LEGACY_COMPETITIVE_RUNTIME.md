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

Migration V78 revokes `PUBLIC` execution from the privileged runtime functions. A trusted database owner/operator grants the dedicated runtime login only:

1. `CONNECT` to the target database;
2. `USAGE` on schema `public`;
3. `EXECUTE` on the seven externally callable runtime functions below.

Example, replacing the placeholders with the real database/role names:

```sql
GRANT CONNECT ON DATABASE minecraft TO legacy_competitive_01;
GRANT USAGE ON SCHEMA public TO legacy_competitive_01;

GRANT EXECUTE ON FUNCTION public.competitive_runtime_heartbeat(INTEGER)
    TO legacy_competitive_01;
GRANT EXECUTE ON FUNCTION public.competitive_runtime_mark_offline()
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

Do not grant the runtime role direct `SELECT`, `INSERT`, `UPDATE`, or `DELETE` privileges on persistent tables.

## Principal registration

The trusted operator/application authority creates the matching principal row, for example:

```sql
INSERT INTO competitive_runtime_principals(
    database_role,
    backend_id,
    max_execution_lease_seconds,
    dispatch_enabled,
    max_active_executions
) VALUES (
    'legacy_competitive_01',
    'legacy-competitive-01',
    120,
    TRUE,
    1
);
```

Capacity and lease values are deployment tuning. They do not change persistent match/war authority.

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
- Runtime failure or lease expiry converges on trusted common-side settlement/recovery.

Any deployment change that grants broader database authority than this document requires an explicit architecture/security review before production use.
