-- A submitted WINNER/FAILURE report is terminal input from the isolated runtime. The execution remains ACTIVE only
-- until trusted common-side settlement applies that report, but the reporting runtime must not prolong that window.

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
      AND NOT EXISTS (
          SELECT 1
          FROM competitive_result_reports report
          WHERE report.execution_id = e.execution_id
      )
    RETURNING e.state_version, e.lease_expires_at;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive execution heartbeat rejected for %', target_execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;
