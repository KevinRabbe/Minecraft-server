-- One runtime principal may legally own up to 64 live executions (V63). The runtime poll must therefore be able to
-- observe/heartbeat all of them in one bounded pass; a lower API ceiling can strand legally dispatched executions.

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
    IF execution_limit < 1 OR execution_limit > 64 THEN
        RAISE EXCEPTION 'execution_limit must be between 1 and 64'
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
