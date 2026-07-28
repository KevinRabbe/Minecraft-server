-- Encounter transfers may reserve one exact live instance. Once pinned, ordinary zone routing must never substitute another
-- backend/instance if that target later becomes unhealthy. The transfer simply fails/recoveries instead.

ALTER TABLE transfer_tickets
    ADD COLUMN pinned_instance BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE transfer_tickets
    ADD CONSTRAINT transfer_tickets_pinned_instance_shape_check CHECK (
        NOT pinned_instance
        OR (
            target_backend_id IS NOT NULL
            AND target_instance_id IS NOT NULL
            AND routed_at IS NOT NULL
        )
    );

CREATE OR REPLACE FUNCTION enforce_pinned_transfer_target()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.pinned_instance THEN
        IF NOT NEW.pinned_instance
           OR NEW.target_backend_id IS DISTINCT FROM OLD.target_backend_id
           OR NEW.target_instance_id IS DISTINCT FROM OLD.target_instance_id
           OR NEW.routed_at IS DISTINCT FROM OLD.routed_at THEN
            RAISE EXCEPTION 'pinned transfer target is immutable for %', OLD.transfer_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER transfer_tickets_freeze_pinned_target
BEFORE UPDATE
ON transfer_tickets
FOR EACH ROW
EXECUTE FUNCTION enforce_pinned_transfer_target();
