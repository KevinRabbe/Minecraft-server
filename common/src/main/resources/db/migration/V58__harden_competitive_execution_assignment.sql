-- Competitive execution assignment is an authority transition, not a loose routing hint.
-- Fail closed unless the selected backend is online, the lease starts live, and the durable activity is ready to enter runtime execution.

CREATE OR REPLACE FUNCTION validate_competitive_execution_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    backend_status TEXT;
    ranked_status TEXT;
    war_status TEXT;
BEGIN
    IF NEW.status <> 'ASSIGNED'
       OR NEW.state_version <> 0
       OR NEW.activated_at IS NOT NULL
       OR NEW.close_reason IS NOT NULL
       OR NEW.settlement_operation_id IS NOT NULL
       OR NEW.closed_at IS NOT NULL THEN
        RAISE EXCEPTION 'new competitive execution must begin as pristine ASSIGNED state'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.lease_expires_at <= NOW() THEN
        RAISE EXCEPTION 'competitive execution assignment requires a live lease'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT status INTO backend_status
    FROM backends
    WHERE backend_id = NEW.backend_id;

    IF NOT FOUND OR backend_status <> 'ONLINE' THEN
        RAISE EXCEPTION 'competitive execution backend % is not ONLINE', NEW.backend_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.activity_kind = 'RANKED_ARENA' THEN
        SELECT status INTO ranked_status
        FROM ranked_matches
        WHERE match_id = NEW.activity_id;

        IF NOT FOUND OR ranked_status <> 'CREATED' THEN
            RAISE EXCEPTION 'ranked activity % is not ready for assignment', NEW.activity_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.activity_kind = 'CLAN_WAR' THEN
        SELECT status INTO war_status
        FROM clan_wars
        WHERE war_id = NEW.activity_id;

        IF NOT FOUND OR war_status <> 'ROSTER_LOCKED' THEN
            RAISE EXCEPTION 'clan-war activity % is not ready for assignment', NEW.activity_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown competitive activity kind %', NEW.activity_kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_executions_validate_insert
BEFORE INSERT
ON competitive_executions
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_execution_insert();
