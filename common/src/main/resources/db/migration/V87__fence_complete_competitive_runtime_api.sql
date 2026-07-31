-- The database role identifies the configured backend, but it does not identify one live JVM. Every externally callable
-- execution-surface function must also prove the caller holds the incarnation currently registered in backends.

CREATE FUNCTION require_competitive_runtime_incarnation(runtime_incarnation_id UUID)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
BEGIN
    IF runtime_incarnation_id IS NULL THEN
        RAISE EXCEPTION 'runtime_incarnation_id must not be null'
            USING ERRCODE = 'not_null_violation';
    END IF;

    mapped_backend := require_competitive_runtime_backend();

    PERFORM 1
    FROM backends backend
    WHERE backend.backend_id = mapped_backend
      AND backend.incarnation_id = runtime_incarnation_id
      AND backend.status = 'ONLINE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive runtime incarnation is not current and online for backend %', mapped_backend
            USING ERRCODE = 'invalid_authorization_specification';
    END IF;

    RETURN mapped_backend;
END;
$$;

DROP FUNCTION competitive_runtime_poll_active(INTEGER);
DROP FUNCTION competitive_runtime_heartbeat_execution(UUID, BIGINT, INTEGER);
DROP FUNCTION competitive_runtime_submit_report(UUID, UUID, TEXT, UUID);
DROP FUNCTION competitive_runtime_find_player_execution(UUID);
DROP FUNCTION competitive_runtime_page_loadout(UUID, INTEGER, INTEGER, INTEGER);

CREATE FUNCTION competitive_runtime_poll_active(runtime_incarnation_id UUID, execution_limit INTEGER)
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

    mapped_backend := require_competitive_runtime_incarnation(runtime_incarnation_id);

    RETURN QUERY
    WITH selected AS (
        SELECT execution.execution_id
        FROM competitive_executions execution
        WHERE execution.backend_id = mapped_backend
          AND execution.status = 'ACTIVE'
          AND execution.lease_expires_at > NOW()
          AND NOT EXISTS (
              SELECT 1
              FROM competitive_result_reports report
              WHERE report.execution_id = execution.execution_id
          )
          AND (
              execution.activity_kind <> 'CLAN_WAR'
              OR EXISTS (
                  SELECT 1
                  FROM competitive_execution_loadout_seals seal
                  WHERE seal.execution_id = execution.execution_id
              )
          )
        ORDER BY execution.activated_at ASC, execution.execution_id ASC
        LIMIT execution_limit
    )
    SELECT execution.execution_id,
           execution.activity_kind,
           execution.activity_id,
           execution.state_version,
           execution.lease_expires_at,
           specification.ruleset_id,
           specification.ruleset_version,
           specification.team_size,
           participant.participant_index,
           participant.side_key,
           participant.side_id,
           participant.player_id,
           participant.minecraft_uuid,
           participant.player_name
    FROM selected selected_execution
    JOIN competitive_executions execution
      ON execution.execution_id = selected_execution.execution_id
    JOIN competitive_execution_specs specification
      ON specification.execution_id = execution.execution_id
    JOIN competitive_execution_participants participant
      ON participant.execution_id = execution.execution_id
    ORDER BY execution.activated_at ASC,
             execution.execution_id ASC,
             participant.participant_index ASC;
END;
$$;

CREATE FUNCTION competitive_runtime_heartbeat_execution(
    runtime_incarnation_id UUID,
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
    mapped_backend := require_competitive_runtime_incarnation(runtime_incarnation_id);

    SELECT principal.max_execution_lease_seconds
    INTO max_lease
    FROM competitive_runtime_principals principal
    WHERE principal.database_role = SESSION_USER::TEXT
      AND principal.backend_id = mapped_backend;

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
    UPDATE competitive_executions execution
    SET lease_expires_at = GREATEST(
            execution.lease_expires_at + INTERVAL '1 millisecond',
            NOW() + make_interval(secs => requested_lease_seconds)
        ),
        state_version = execution.state_version + 1
    WHERE execution.execution_id = target_execution_id
      AND execution.backend_id = mapped_backend
      AND execution.status = 'ACTIVE'
      AND execution.state_version = expected_state_version
      AND execution.lease_expires_at > NOW()
      AND NOT EXISTS (
          SELECT 1
          FROM competitive_result_reports report
          WHERE report.execution_id = execution.execution_id
      )
    RETURNING execution.state_version, execution.lease_expires_at;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive execution heartbeat rejected for %', target_execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

CREATE FUNCTION competitive_runtime_submit_report(
    runtime_incarnation_id UUID,
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
    mapped_backend := require_competitive_runtime_incarnation(runtime_incarnation_id);

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

CREATE FUNCTION competitive_runtime_find_player_execution(
    runtime_incarnation_id UUID,
    target_minecraft_uuid UUID
)
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

    mapped_backend := require_competitive_runtime_incarnation(runtime_incarnation_id);

    SELECT ARRAY_AGG(candidate.execution_id ORDER BY candidate.execution_id)
    INTO candidate_execution_ids
    FROM (
        SELECT DISTINCT execution.execution_id
        FROM competitive_executions execution
        JOIN competitive_execution_participants requested_participant
          ON requested_participant.execution_id = execution.execution_id
        JOIN competitive_player_execution_reservations reservation
          ON reservation.execution_id = execution.execution_id
         AND reservation.player_id = requested_participant.player_id
        WHERE execution.backend_id = mapped_backend
          AND execution.status = 'ACTIVE'
          AND execution.lease_expires_at > NOW()
          AND requested_participant.minecraft_uuid = target_minecraft_uuid
          AND NOT EXISTS (
              SELECT 1
              FROM competitive_result_reports report
              WHERE report.execution_id = execution.execution_id
          )
          AND (
              execution.activity_kind <> 'CLAN_WAR'
              OR EXISTS (
                  SELECT 1
                  FROM competitive_execution_loadout_seals seal
                  WHERE seal.execution_id = execution.execution_id
              )
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
    SELECT execution.execution_id,
           execution.activity_kind,
           execution.activity_id,
           execution.state_version,
           execution.lease_expires_at,
           specification.ruleset_id,
           specification.ruleset_version,
           specification.team_size,
           participant.participant_index,
           participant.side_key,
           participant.side_id,
           participant.player_id,
           participant.minecraft_uuid,
           participant.player_name
    FROM competitive_executions execution
    JOIN competitive_execution_specs specification
      ON specification.execution_id = execution.execution_id
    JOIN competitive_execution_participants participant
      ON participant.execution_id = execution.execution_id
    WHERE execution.execution_id = target_execution_id
    ORDER BY participant.participant_index ASC;
END;
$$;

CREATE FUNCTION competitive_runtime_page_loadout(
    runtime_incarnation_id UUID,
    target_execution_id UUID,
    after_participant_index INTEGER,
    after_loadout_item_index INTEGER,
    item_limit INTEGER
)
RETURNS TABLE (
    participant_index INTEGER,
    loadout_item_index INTEGER,
    definition_id TEXT,
    roll_state_json TEXT,
    upgrade_level INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
    execution_kind TEXT;
    snapshot_sealed BOOLEAN;
BEGIN
    IF target_execution_id IS NULL THEN
        RAISE EXCEPTION 'target_execution_id must not be null'
            USING ERRCODE = 'check_violation';
    END IF;

    IF item_limit IS NULL OR item_limit < 1 OR item_limit > 500 THEN
        RAISE EXCEPTION 'item_limit must be between 1 and 500'
            USING ERRCODE = 'check_violation';
    END IF;

    IF (after_participant_index IS NULL) <> (after_loadout_item_index IS NULL) THEN
        RAISE EXCEPTION 'loadout cursor fields must both be null or both be present'
            USING ERRCODE = 'check_violation';
    END IF;

    IF after_participant_index IS NOT NULL
       AND (after_participant_index < 0 OR after_loadout_item_index < 0) THEN
        RAISE EXCEPTION 'loadout cursor values must be nonnegative'
            USING ERRCODE = 'check_violation';
    END IF;

    mapped_backend := require_competitive_runtime_incarnation(runtime_incarnation_id);

    SELECT execution.activity_kind,
           EXISTS (
               SELECT 1
               FROM competitive_execution_loadout_seals seal
               WHERE seal.execution_id = execution.execution_id
           )
    INTO execution_kind, snapshot_sealed
    FROM competitive_executions execution
    WHERE execution.execution_id = target_execution_id
      AND execution.backend_id = mapped_backend
      AND execution.status = 'ACTIVE'
      AND execution.lease_expires_at > NOW()
      AND NOT EXISTS (
          SELECT 1
          FROM competitive_result_reports report
          WHERE report.execution_id = execution.execution_id
      );

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF execution_kind <> 'CLAN_WAR' THEN
        RAISE EXCEPTION 'competitive runtime loadout requested for non-Clan-War execution %', target_execution_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF NOT snapshot_sealed THEN
        RAISE EXCEPTION 'competitive execution loadout snapshot is not sealed %', target_execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN QUERY
    SELECT loadout.participant_index,
           loadout.loadout_item_index,
           loadout.definition_id,
           loadout.roll_state::TEXT,
           loadout.upgrade_level
    FROM competitive_executions execution
    JOIN competitive_execution_loadout_seals seal
      ON seal.execution_id = execution.execution_id
    JOIN competitive_execution_loadout_items loadout
      ON loadout.execution_id = execution.execution_id
    WHERE execution.execution_id = target_execution_id
      AND execution.backend_id = mapped_backend
      AND execution.activity_kind = 'CLAN_WAR'
      AND execution.status = 'ACTIVE'
      AND execution.lease_expires_at > NOW()
      AND NOT EXISTS (
          SELECT 1
          FROM competitive_result_reports report
          WHERE report.execution_id = execution.execution_id
      )
      AND (
          after_participant_index IS NULL
          OR loadout.participant_index > after_participant_index
          OR (
              loadout.participant_index = after_participant_index
              AND loadout.loadout_item_index > after_loadout_item_index
          )
      )
    ORDER BY loadout.participant_index ASC, loadout.loadout_item_index ASC
    LIMIT item_limit;
END;
$$;

REVOKE EXECUTE ON FUNCTION require_competitive_runtime_incarnation(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_poll_active(UUID, INTEGER) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_heartbeat_execution(UUID, UUID, BIGINT, INTEGER) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_submit_report(UUID, UUID, UUID, TEXT, UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_find_player_execution(UUID, UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_page_loadout(UUID, UUID, INTEGER, INTEGER, INTEGER) FROM PUBLIC;
