-- Immutable evidence connecting one created Map run and its bound encounter reservation to one exact pinned transfer.
-- The transfer/run/reservation lifecycle remains owned by their existing authorities; this row only proves the handoff.

CREATE TABLE map_encounter_handoffs (
    run_id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE,
    transfer_id UUID NOT NULL UNIQUE,
    player_id UUID NOT NULL,
    target_instance_id UUID NOT NULL,
    target_backend_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX map_encounter_handoffs_transfer_idx
    ON map_encounter_handoffs(transfer_id);

CREATE OR REPLACE FUNCTION validate_map_encounter_handoff()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    reservation RECORD;
    run RECORD;
    transfer RECORD;
BEGIN
    SELECT *
    INTO reservation
    FROM map_encounter_reservations
    WHERE reservation_id = NEW.reservation_id;

    IF NOT FOUND
       OR reservation.status IS DISTINCT FROM 'BOUND'
       OR reservation.run_id IS DISTINCT FROM NEW.run_id
       OR reservation.player_id IS DISTINCT FROM NEW.player_id
       OR reservation.target_instance_id IS DISTINCT FROM NEW.target_instance_id
       OR reservation.target_backend_id IS DISTINCT FROM NEW.target_backend_id THEN
        RAISE EXCEPTION 'Map encounter handoff does not match BOUND reservation %', NEW.reservation_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT status, opened_by_player_id
    INTO run
    FROM map_runs
    WHERE run_id = NEW.run_id;

    IF NOT FOUND
       OR run.status IS DISTINCT FROM 'CREATED'
       OR run.opened_by_player_id IS DISTINCT FROM NEW.player_id THEN
        RAISE EXCEPTION 'Map encounter handoff requires matching CREATED run %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT player_id,
           target_zone_id,
           target_backend_id,
           target_instance_id,
           pinned_instance,
           consumed_at,
           expires_at
    INTO transfer
    FROM transfer_tickets
    WHERE transfer_id = NEW.transfer_id;

    IF NOT FOUND
       OR transfer.player_id IS DISTINCT FROM NEW.player_id
       OR transfer.target_zone_id IS DISTINCT FROM reservation.target_zone_id
       OR transfer.target_backend_id IS DISTINCT FROM NEW.target_backend_id
       OR transfer.target_instance_id IS DISTINCT FROM NEW.target_instance_id
       OR transfer.pinned_instance IS DISTINCT FROM TRUE
       OR transfer.consumed_at IS NOT NULL
       OR transfer.expires_at <= NOW() THEN
        RAISE EXCEPTION 'Map encounter handoff transfer is not the matching live pinned target %', NEW.transfer_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_encounter_handoffs_validate
BEFORE INSERT
ON map_encounter_handoffs
FOR EACH ROW
EXECUTE FUNCTION validate_map_encounter_handoff();

CREATE OR REPLACE FUNCTION reject_map_encounter_handoff_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'map_encounter_handoffs is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER map_encounter_handoffs_append_only
BEFORE UPDATE OR DELETE
ON map_encounter_handoffs
FOR EACH ROW
EXECUTE FUNCTION reject_map_encounter_handoff_mutation();
