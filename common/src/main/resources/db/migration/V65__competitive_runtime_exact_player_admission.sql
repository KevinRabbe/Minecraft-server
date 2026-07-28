-- Exact admission lookup for a player connecting to an isolated legacy runtime.
-- The runtime remains scoped to its SESSION_USER-mapped backend and receives only the same sanitized manifest
-- already exposed by competitive_runtime_poll_active. Unlike polling, this lookup is not bounded by a batch limit.

CREATE OR REPLACE FUNCTION competitive_runtime_find_player_execution(target_minecraft_uuid UUID)
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
    candidate_execution_ids UUID[];
    target_execution_id UUID;
BEGIN
    IF target_minecraft_uuid IS NULL THEN
        RAISE EXCEPTION 'target_minecraft_uuid must not be null'
            USING ERRCODE = 'not_null_violation';
    END IF;

    mapped_backend := require_competitive_runtime_backend();

    SELECT ARRAY_AGG(candidate.execution_id ORDER BY candidate.execution_id)
    INTO candidate_execution_ids
    FROM (
        SELECT DISTINCT e.execution_id
        FROM competitive_executions e
        JOIN competitive_execution_participants requested_participant
          ON requested_participant.execution_id = e.execution_id
        JOIN competitive_player_execution_reservations reservation
          ON reservation.execution_id = e.execution_id
         AND reservation.player_id = requested_participant.player_id
        WHERE e.backend_id = mapped_backend
          AND e.status = 'ACTIVE'
          AND e.lease_expires_at > NOW()
          AND requested_participant.minecraft_uuid = target_minecraft_uuid
          AND NOT EXISTS (
              SELECT 1
              FROM competitive_result_reports report
              WHERE report.execution_id = e.execution_id
          )
    ) candidate;

    IF candidate_execution_ids IS NULL OR CARDINALITY(candidate_execution_ids) = 0 THEN
        RETURN;
    END IF;

    IF CARDINALITY(candidate_execution_ids) <> 1 THEN
        RAISE EXCEPTION 'player % has multiple live executions on backend %', target_minecraft_uuid, mapped_backend
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    target_execution_id := candidate_execution_ids[1];

    RETURN QUERY
    SELECT e.execution_id,
           e.activity_kind,
           e.activity_id,
           e.state_version,
           e.lease_expires_at,
           spec.ruleset_id,
           spec.ruleset_version,
           spec.team_size,
           participant.participant_index,
           participant.side_key,
           participant.side_id,
           participant.player_id,
           participant.minecraft_uuid,
           participant.player_name
    FROM competitive_executions e
    JOIN competitive_execution_specs spec ON spec.execution_id = e.execution_id
    JOIN competitive_execution_participants participant ON participant.execution_id = e.execution_id
    WHERE e.execution_id = target_execution_id
    ORDER BY participant.participant_index ASC;
END;
$$;
