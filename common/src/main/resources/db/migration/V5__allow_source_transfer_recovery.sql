CREATE OR REPLACE FUNCTION enforce_routed_transfer_claim()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'TRANSFERRING'
       AND NEW.status = 'ACTIVE'
       AND (
           NEW.owner_backend_id IS DISTINCT FROM OLD.owner_backend_id
           OR NEW.owner_instance_id IS DISTINCT FROM OLD.owner_instance_id
       ) THEN
        IF NOT EXISTS (
            SELECT 1
            FROM transfer_tickets tt
            WHERE tt.network_session_id = OLD.network_session_id
              AND tt.consumed_at IS NULL
              AND tt.expires_at > NOW()
              AND tt.expected_state_version = OLD.state_version
              AND tt.target_backend_id = NEW.owner_backend_id
              AND tt.target_instance_id = NEW.owner_instance_id
        ) THEN
            RAISE EXCEPTION 'active transfer claim does not match a routed transfer ticket for session %',
                OLD.network_session_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;
