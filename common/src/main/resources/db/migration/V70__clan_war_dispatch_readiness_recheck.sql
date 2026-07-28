-- Candidate discovery is advisory. Recheck finalized Clan-War loadout readiness while holding the durable war row lock
-- inside the assignment transaction so a concurrent custody mutation cannot race past dispatch.

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
    clan_war_team_size INTEGER;
    live_roster_count INTEGER;
    finalized_roster_count INTEGER;
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
        SELECT status, team_size
        INTO activity_status, clan_war_team_size
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

        SELECT COUNT(*)::INTEGER,
               COUNT(confirmation.player_id)::INTEGER
        INTO live_roster_count, finalized_roster_count
        FROM clan_war_rosters roster
        LEFT JOIN clan_war_loadout_confirmations confirmation
          ON confirmation.war_id = roster.war_id
         AND confirmation.player_id = roster.player_id
        WHERE roster.war_id = target_activity_id
          AND roster.released_at IS NULL;

        IF live_roster_count <> clan_war_team_size * 2
           OR finalized_roster_count <> live_roster_count THEN
            RAISE EXCEPTION 'clan-war activity % does not have finalized loadouts for its exact live roster', target_activity_id
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

-- Deposits lock the same clan_wars row before moving persistent item custody. If dispatch wins that row lock and commits
-- an execution first, a later deposit must fail and roll back its entire custody/player-state transaction.
CREATE OR REPLACE FUNCTION reject_clan_war_item_insert_after_dispatch()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM competitive_executions execution
        WHERE execution.activity_kind = 'CLAN_WAR'
          AND execution.activity_id = NEW.war_id
    ) THEN
        RAISE EXCEPTION 'Clan-War custody cannot change after competitive dispatch'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_war_items_reject_insert_after_dispatch
BEFORE INSERT
ON clan_war_items
FOR EACH ROW
EXECUTE FUNCTION reject_clan_war_item_insert_after_dispatch();
