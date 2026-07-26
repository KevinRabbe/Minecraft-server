-- Narrow SECURITY DEFINER API for isolated legacy runtime database principals.
-- Deployment grants the runtime login CONNECT + function EXECUTE only; direct persistent tables remain unavailable.

CREATE TABLE competitive_runtime_principals (
    database_role TEXT PRIMARY KEY,
    backend_id TEXT NOT NULL UNIQUE,
    max_execution_lease_seconds INTEGER NOT NULL DEFAULT 120,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT competitive_runtime_principals_role_check CHECK (BTRIM(database_role) <> ''),
    CONSTRAINT competitive_runtime_principals_backend_check CHECK (BTRIM(backend_id) <> ''),
    CONSTRAINT competitive_runtime_principals_lease_check CHECK (
        max_execution_lease_seconds BETWEEN 1 AND 3600
    )
);

CREATE OR REPLACE FUNCTION require_competitive_runtime_backend()
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
BEGIN
    SELECT backend_id INTO mapped_backend
    FROM competitive_runtime_principals
    WHERE database_role = SESSION_USER::TEXT;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'database role % is not an authorized competitive runtime principal', SESSION_USER
            USING ERRCODE = 'invalid_authorization_specification';
    END IF;

    RETURN mapped_backend;
END;
$$;

CREATE OR REPLACE FUNCTION competitive_runtime_heartbeat(player_count INTEGER)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
BEGIN
    IF player_count < 0 THEN
        RAISE EXCEPTION 'player_count must not be negative'
            USING ERRCODE = 'check_violation';
    END IF;

    mapped_backend := require_competitive_runtime_backend();

    INSERT INTO backends(
        backend_id,
        status,
        started_at,
        last_heartbeat_at,
        player_count
    ) VALUES (mapped_backend, 'ONLINE', NOW(), NOW(), player_count)
    ON CONFLICT (backend_id) DO UPDATE SET
        status = 'ONLINE',
        started_at = CASE
            WHEN backends.status = 'ONLINE' THEN backends.started_at
            ELSE NOW()
        END,
        last_heartbeat_at = NOW(),
        player_count = EXCLUDED.player_count;

    RETURN mapped_backend;
END;
$$;

CREATE OR REPLACE FUNCTION competitive_runtime_mark_offline()
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
BEGIN
    mapped_backend := require_competitive_runtime_backend();
    UPDATE backends
    SET status = 'OFFLINE',
        last_heartbeat_at = NOW(),
        player_count = 0
    WHERE backend_id = mapped_backend;
    RETURN mapped_backend;
END;
$$;

CREATE OR REPLACE FUNCTION competitive_runtime_poll_active(execution_limit INTEGER)
RETURNS TABLE (
    execution_id UUID,
    activity_kind TEXT,
    activity_id UUID,
    state_version BIGINT,
    lease_expires_at TIMESTAMPTZ,
    ruleset_id TEXT,
    ruleset_version INTEGER,
    team_size INTEGER,
    participant_index INTEGER,
    side_key TEXT,
    side_id UUID,
    player_id UUID,
    minecraft_uuid UUID,
    player_name TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
BEGIN
    IF execution_limit < 1 OR execution_limit > 50 THEN
        RAISE EXCEPTION 'execution_limit must be between 1 and 50'
            USING ERRCODE = 'check_violation';
    END IF;

    mapped_backend := require_competitive_runtime_backend();

    RETURN QUERY
    WITH selected AS (
        SELECT e.execution_id
        FROM competitive_executions e
        WHERE e.backend_id = mapped_backend
          AND e.status = 'ACTIVE'
          AND e.lease_expires_at > NOW()
          AND NOT EXISTS (
              SELECT 1
              FROM competitive_result_reports r
              WHERE r.execution_id = e.execution_id
          )
        ORDER BY e.activated_at ASC, e.execution_id ASC
        LIMIT execution_limit
    )
    SELECT e.execution_id,
           e.activity_kind,
           e.activity_id,
           e.state_version,
           e.lease_expires_at,
           s.ruleset_id,
           s.ruleset_version,
           s.team_size,
           p.participant_index,
           p.side_key,
           p.side_id,
           p.player_id,
           p.minecraft_uuid,
           p.player_name
    FROM selected selected_execution
    JOIN competitive_executions e ON e.execution_id = selected_execution.execution_id
    JOIN competitive_execution_specs s ON s.execution_id = e.execution_id
    JOIN competitive_execution_participants p ON p.execution_id = e.execution_id
    ORDER BY e.activated_at ASC, e.execution_id ASC, p.participant_index ASC;
END;
$$;

CREATE OR REPLACE FUNCTION competitive_runtime_heartbeat_execution(
    target_execution_id UUID,
    expected_state_version BIGINT,
    requested_lease_seconds INTEGER
)
RETURNS TABLE (
    state_version BIGINT,
    lease_expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
    max_lease INTEGER;
BEGIN
    SELECT backend_id, max_execution_lease_seconds
    INTO mapped_backend, max_lease
    FROM competitive_runtime_principals
    WHERE database_role = SESSION_USER::TEXT;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'database role % is not an authorized competitive runtime principal', SESSION_USER
            USING ERRCODE = 'invalid_authorization_specification';
    END IF;

    IF expected_state_version < 0 THEN
        RAISE EXCEPTION 'expected_state_version must be nonnegative'
            USING ERRCODE = 'check_violation';
    END IF;

    IF requested_lease_seconds < 1 OR requested_lease_seconds > max_lease THEN
        RAISE EXCEPTION 'requested lease exceeds runtime principal limit'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN QUERY
    UPDATE competitive_executions e
    SET lease_expires_at = GREATEST(
            e.lease_expires_at + INTERVAL '1 millisecond',
            NOW() + make_interval(secs => requested_lease_seconds)
        ),
        state_version = e.state_version + 1
    WHERE e.execution_id = target_execution_id
      AND e.backend_id = mapped_backend
      AND e.status = 'ACTIVE'
      AND e.state_version = expected_state_version
      AND e.lease_expires_at > NOW()
    RETURNING e.state_version, e.lease_expires_at;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive execution heartbeat rejected for %', target_execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION competitive_runtime_submit_report(
    target_report_operation_id UUID,
    target_execution_id UUID,
    target_report_kind TEXT,
    target_winner_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
    existing competitive_result_reports%ROWTYPE;
    report_id UUID;
BEGIN
    mapped_backend := require_competitive_runtime_backend();

    IF target_report_kind NOT IN ('WINNER', 'FAILURE') THEN
        RAISE EXCEPTION 'invalid competitive report kind %', target_report_kind
            USING ERRCODE = 'check_violation';
    END IF;

    IF (target_report_kind = 'WINNER' AND target_winner_id IS NULL)
       OR (target_report_kind = 'FAILURE' AND target_winner_id IS NOT NULL) THEN
        RAISE EXCEPTION 'competitive report winner shape is invalid'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO existing
    FROM competitive_result_reports
    WHERE report_operation_id = target_report_operation_id;

    IF FOUND THEN
        IF existing.execution_id IS DISTINCT FROM target_execution_id
           OR existing.backend_id IS DISTINCT FROM mapped_backend
           OR existing.report_kind IS DISTINCT FROM target_report_kind
           OR existing.winner_id IS DISTINCT FROM target_winner_id THEN
            RAISE EXCEPTION 'report operation_id reused for another request'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN existing.report_id;
    END IF;

    report_id := gen_random_uuid();
    INSERT INTO competitive_result_reports(
        report_id,
        report_operation_id,
        execution_id,
        backend_id,
        report_kind,
        winner_id,
        status
    ) VALUES (
        report_id,
        target_report_operation_id,
        target_execution_id,
        mapped_backend,
        target_report_kind,
        target_winner_id,
        'PENDING'
    );

    RETURN report_id;
END;
$$;
