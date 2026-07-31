-- Registration and recurring heartbeat are separate authority transitions. A stale legacy process may keep its database
-- login, but once a replacement rotates the backend incarnation it cannot renew or shut down the replacement row.
DROP FUNCTION competitive_runtime_heartbeat(INTEGER);
DROP FUNCTION competitive_runtime_mark_offline();

CREATE FUNCTION competitive_runtime_register(runtime_incarnation_id UUID, player_count INTEGER)
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
    IF player_count < 0 THEN
        RAISE EXCEPTION 'player_count must not be negative'
            USING ERRCODE = 'check_violation';
    END IF;

    mapped_backend := require_competitive_runtime_backend();

    INSERT INTO backends(
        backend_id,
        incarnation_id,
        status,
        started_at,
        last_heartbeat_at,
        player_count
    ) VALUES (mapped_backend, runtime_incarnation_id, 'ONLINE', NOW(), NOW(), player_count)
    ON CONFLICT (backend_id) DO UPDATE SET
        incarnation_id = EXCLUDED.incarnation_id,
        status = 'ONLINE',
        started_at = NOW(),
        last_heartbeat_at = NOW(),
        player_count = EXCLUDED.player_count;

    RETURN mapped_backend;
END;
$$;

CREATE FUNCTION competitive_runtime_heartbeat(runtime_incarnation_id UUID, player_count INTEGER)
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
    IF player_count < 0 THEN
        RAISE EXCEPTION 'player_count must not be negative'
            USING ERRCODE = 'check_violation';
    END IF;

    mapped_backend := require_competitive_runtime_backend();

    UPDATE backends
    SET status = 'ONLINE',
        last_heartbeat_at = NOW(),
        player_count = player_count
    WHERE backend_id = mapped_backend
      AND incarnation_id = runtime_incarnation_id
      AND status IN ('ONLINE', 'DRAINING');

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive runtime incarnation is not heartbeat-eligible for backend %', mapped_backend
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    RETURN mapped_backend;
END;
$$;

CREATE FUNCTION competitive_runtime_mark_offline(runtime_incarnation_id UUID)
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

    UPDATE backends
    SET status = 'OFFLINE',
        last_heartbeat_at = NOW(),
        player_count = 0
    WHERE backend_id = mapped_backend
      AND incarnation_id = runtime_incarnation_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive runtime incarnation no longer owns backend %', mapped_backend
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    RETURN mapped_backend;
END;
$$;

REVOKE EXECUTE ON FUNCTION competitive_runtime_register(UUID, INTEGER) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_heartbeat(UUID, INTEGER) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_mark_offline(UUID) FROM PUBLIC;
