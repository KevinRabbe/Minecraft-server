-- A player may participate in only one live competitive execution across Ranked Arena and Clan War.
-- Reservation begins with backend assignment, before durable activity activation, and releases only when execution closes.

CREATE TABLE competitive_player_execution_reservations (
    player_id UUID PRIMARY KEY REFERENCES players(player_id) ON DELETE RESTRICT,
    execution_id UUID NOT NULL REFERENCES competitive_executions(execution_id) ON DELETE RESTRICT,
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT competitive_player_execution_reservations_pair_unique UNIQUE (execution_id, player_id)
);

CREATE INDEX competitive_player_execution_reservations_execution_idx
    ON competitive_player_execution_reservations(execution_id, player_id);

CREATE OR REPLACE FUNCTION validate_competitive_player_execution_reservation_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    execution_status TEXT;
BEGIN
    SELECT status INTO execution_status
    FROM competitive_executions
    WHERE execution_id = NEW.execution_id;

    IF NOT FOUND OR execution_status NOT IN ('ASSIGNED', 'ACTIVE') THEN
        RAISE EXCEPTION 'competitive player reservation requires a live execution'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM competitive_execution_participants p
        WHERE p.execution_id = NEW.execution_id
          AND p.player_id = NEW.player_id
    ) THEN
        RAISE EXCEPTION 'competitive player reservation must match the frozen runtime manifest'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_player_execution_reservations_validate_insert
BEFORE INSERT
ON competitive_player_execution_reservations
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_player_execution_reservation_insert();

CREATE OR REPLACE FUNCTION reserve_competitive_execution_players()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_count INTEGER;
    reserved_count INTEGER;
BEGIN
    SELECT team_size * 2 INTO expected_count
    FROM competitive_execution_specs
    WHERE execution_id = NEW.execution_id;

    IF expected_count IS NULL THEN
        RAISE EXCEPTION 'competitive execution % has no frozen runtime spec before player reservation', NEW.execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    INSERT INTO competitive_player_execution_reservations(player_id, execution_id)
    SELECT player_id, NEW.execution_id
    FROM competitive_execution_participants
    WHERE execution_id = NEW.execution_id
    ORDER BY player_id;

    GET DIAGNOSTICS reserved_count = ROW_COUNT;
    IF reserved_count <> expected_count THEN
        RAISE EXCEPTION 'competitive execution % reserved % players; expected %',
            NEW.execution_id, reserved_count, expected_count
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

-- PostgreSQL fires same-kind triggers in name order. The existing
-- competitive_executions_materialize_runtime_manifest trigger therefore populates the frozen manifest first.
CREATE TRIGGER competitive_executions_reserve_players
AFTER INSERT
ON competitive_executions
FOR EACH ROW
EXECUTE FUNCTION reserve_competitive_execution_players();

CREATE OR REPLACE FUNCTION reject_live_competitive_player_reservation_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    execution_status TEXT;
BEGIN
    SELECT status INTO execution_status
    FROM competitive_executions
    WHERE execution_id = OLD.execution_id;

    IF FOUND AND execution_status <> 'CLOSED' THEN
        RAISE EXCEPTION 'live competitive player reservation cannot be released directly'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN OLD;
END;
$$;

CREATE TRIGGER competitive_player_execution_reservations_validate_delete
BEFORE DELETE
ON competitive_player_execution_reservations
FOR EACH ROW
EXECUTE FUNCTION reject_live_competitive_player_reservation_delete();

CREATE OR REPLACE FUNCTION release_closed_competitive_execution_players()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM competitive_player_execution_reservations
    WHERE execution_id = NEW.execution_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_executions_release_players
AFTER UPDATE OF status
ON competitive_executions
FOR EACH ROW
WHEN (OLD.status IS DISTINCT FROM 'CLOSED' AND NEW.status = 'CLOSED')
EXECUTE FUNCTION release_closed_competitive_execution_players();

-- Replace dispatch with a cross-category player-busy preflight. Busy participants are deferred, not treated as errors.
CREATE OR REPLACE FUNCTION competitive_dispatch_execution(
    target_assignment_operation_id UUID,
    target_activity_kind TEXT,
    target_activity_id UUID,
    backend_freshness_seconds INTEGER,
    lease_seconds INTEGER
)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    existing_execution competitive_executions%ROWTYPE;
    selected_backend TEXT;
    activity_status TEXT;
    new_execution_id UUID;
BEGIN
    IF target_assignment_operation_id IS NULL OR target_activity_id IS NULL THEN
        RAISE EXCEPTION 'competitive dispatch operation/activity id must not be null'
            USING ERRCODE = 'not_null_violation';
    END IF;

    IF target_activity_kind NOT IN ('RANKED_ARENA', 'CLAN_WAR') THEN
        RAISE EXCEPTION 'invalid competitive dispatch activity kind %', target_activity_kind
            USING ERRCODE = 'check_violation';
    END IF;

    IF backend_freshness_seconds < 1 OR backend_freshness_seconds > 3600 THEN
        RAISE EXCEPTION 'backend_freshness_seconds must be between 1 and 3600'
            USING ERRCODE = 'check_violation';
    END IF;

    IF lease_seconds < 1 OR lease_seconds > 3600 THEN
        RAISE EXCEPTION 'lease_seconds must be between 1 and 3600'
            USING ERRCODE = 'check_violation';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(target_assignment_operation_id::TEXT, 0));

    SELECT * INTO existing_execution
    FROM competitive_executions
    WHERE assignment_operation_id = target_assignment_operation_id
    FOR UPDATE;

    IF FOUND THEN
        IF existing_execution.activity_kind IS DISTINCT FROM target_activity_kind
           OR existing_execution.activity_id IS DISTINCT FROM target_activity_id THEN
            RAISE EXCEPTION 'competitive dispatch operation_id reused for another activity'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN existing_execution.execution_id;
    END IF;

    IF target_activity_kind = 'RANKED_ARENA' THEN
        SELECT status INTO activity_status
        FROM ranked_matches
        WHERE match_id = target_activity_id
        FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'unknown ranked activity %', target_activity_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF activity_status <> 'CREATED' THEN
            RAISE EXCEPTION 'ranked activity % is not ready for dispatch: %', target_activity_id, activity_status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        SELECT status INTO activity_status
        FROM clan_wars
        WHERE war_id = target_activity_id
        FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'unknown clan-war activity %', target_activity_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF activity_status <> 'ROSTER_LOCKED' THEN
            RAISE EXCEPTION 'clan-war activity % is not ready for dispatch: %', target_activity_id, activity_status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    SELECT * INTO existing_execution
    FROM competitive_executions
    WHERE activity_kind = target_activity_kind
      AND activity_id = target_activity_id
    FOR UPDATE;

    IF FOUND THEN
        RETURN existing_execution.execution_id;
    END IF;

    IF target_activity_kind = 'RANKED_ARENA' THEN
        IF EXISTS (
            SELECT 1
            FROM ranked_matches m
            JOIN competitive_player_execution_reservations r
              ON r.player_id IN (m.player_a_id, m.player_b_id)
            WHERE m.match_id = target_activity_id
        ) THEN
            RETURN NULL;
        END IF;
    ELSE
        IF EXISTS (
            SELECT 1
            FROM clan_war_rosters roster
            JOIN competitive_player_execution_reservations reservation
              ON reservation.player_id = roster.player_id
            WHERE roster.war_id = target_activity_id
              AND roster.released_at IS NULL
        ) THEN
            RETURN NULL;
        END IF;
    END IF;

    SELECT p.backend_id INTO selected_backend
    FROM competitive_runtime_principals p
    JOIN backends b ON b.backend_id = p.backend_id
    LEFT JOIN LATERAL (
        SELECT COUNT(*)::INTEGER AS live_count
        FROM competitive_executions e
        WHERE e.backend_id = p.backend_id
          AND e.status IN ('ASSIGNED', 'ACTIVE')
          AND e.lease_expires_at > NOW()
    ) live ON TRUE
    WHERE p.dispatch_enabled
      AND p.max_execution_lease_seconds >= lease_seconds
      AND b.status = 'ONLINE'
      AND b.last_heartbeat_at >= NOW() - make_interval(secs => backend_freshness_seconds)
      AND live.live_count < p.max_active_executions
    ORDER BY live.live_count ASC,
             b.player_count ASC,
             b.last_heartbeat_at DESC,
             p.backend_id ASC
    FOR UPDATE OF p SKIP LOCKED
    LIMIT 1;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    new_execution_id := gen_random_uuid();
    INSERT INTO competitive_executions(
        execution_id,
        assignment_operation_id,
        activity_kind,
        activity_id,
        backend_id,
        status,
        lease_expires_at,
        state_version
    ) VALUES (
        new_execution_id,
        target_assignment_operation_id,
        target_activity_kind,
        target_activity_id,
        selected_backend,
        'ASSIGNED',
        NOW() + make_interval(secs => lease_seconds),
        0
    );

    RETURN new_execution_id;
END;
$$;
