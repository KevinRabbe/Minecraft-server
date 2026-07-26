CREATE OR REPLACE FUNCTION validate_competitive_execution_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    backend_status TEXT;
    ranked_status TEXT;
    war_status TEXT;
BEGIN
    IF NEW.execution_id IS DISTINCT FROM OLD.execution_id
       OR NEW.assignment_operation_id IS DISTINCT FROM OLD.assignment_operation_id
       OR NEW.activity_kind IS DISTINCT FROM OLD.activity_kind
       OR NEW.activity_id IS DISTINCT FROM OLD.activity_id
       OR NEW.backend_id IS DISTINCT FROM OLD.backend_id
       OR NEW.assigned_at IS DISTINCT FROM OLD.assigned_at THEN
        RAISE EXCEPTION 'competitive execution identity/assignment is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'closed competitive execution is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.state_version <> OLD.state_version + 1 THEN
        RAISE EXCEPTION 'competitive execution state_version must advance exactly once'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status IN ('ASSIGNED', 'ACTIVE') THEN
        IF OLD.lease_expires_at <= NOW() THEN
            RAISE EXCEPTION 'expired competitive execution lease cannot be resurrected'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        SELECT status INTO backend_status
        FROM backends
        WHERE backend_id = NEW.backend_id;

        IF NOT FOUND OR backend_status <> 'ONLINE' THEN
            RAISE EXCEPTION 'competitive execution backend % is not ONLINE', NEW.backend_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    IF NEW.status = OLD.status THEN
        IF NEW.status NOT IN ('ASSIGNED', 'ACTIVE')
           OR NEW.lease_expires_at <= OLD.lease_expires_at
           OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
           OR NEW.close_reason IS DISTINCT FROM OLD.close_reason
           OR NEW.settlement_operation_id IS DISTINCT FROM OLD.settlement_operation_id
           OR NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
            RAISE EXCEPTION 'live competitive execution same-state update must only extend its lease'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'ASSIGNED' AND NEW.status = 'ACTIVE' THEN
        IF NEW.activated_at IS NULL OR NEW.lease_expires_at <= OLD.lease_expires_at THEN
            RAISE EXCEPTION 'competitive activation requires activation time and extended lease'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF NEW.activity_kind = 'RANKED_ARENA' THEN
            SELECT status INTO ranked_status
            FROM ranked_matches
            WHERE match_id = NEW.activity_id;
            IF NOT FOUND OR ranked_status <> 'ACTIVE' THEN
                RAISE EXCEPTION 'ranked activity % is not active for runtime activation', NEW.activity_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.activity_kind = 'CLAN_WAR' THEN
            SELECT status INTO war_status
            FROM clan_wars
            WHERE war_id = NEW.activity_id;
            IF NOT FOUND OR war_status <> 'ACTIVE' THEN
                RAISE EXCEPTION 'clan-war activity % is not active for runtime activation', NEW.activity_id
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'unknown competitive activity kind %', NEW.activity_kind
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('ASSIGNED', 'ACTIVE') AND NEW.status = 'CLOSED' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid competitive execution transition % -> %', OLD.status, NEW.status
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;
