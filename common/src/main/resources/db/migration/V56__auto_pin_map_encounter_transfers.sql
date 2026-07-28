-- A player may own at most one live Map encounter reservation at a time. When the existing session transfer authority
-- creates a ticket for that encounter zone, PostgreSQL pins the ticket to the reserved exact instance and records the
-- immutable run->reservation->transfer handoff in the same transfer transaction. Paper/Velocity need no second protocol.

CREATE UNIQUE INDEX map_encounter_reservations_active_player_idx
    ON map_encounter_reservations(player_id)
    WHERE status IN ('RESERVED', 'BOUND');

CREATE OR REPLACE FUNCTION auto_pin_map_encounter_transfer()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    reservation RECORD;
BEGIN
    SELECT r.*
    INTO reservation
    FROM map_encounter_reservations r
    JOIN map_runs mr ON mr.run_id = r.run_id
    LEFT JOIN map_encounter_handoffs h ON h.run_id = r.run_id
    WHERE r.player_id = NEW.player_id
      AND r.status = 'BOUND'
      AND r.target_zone_id = NEW.target_zone_id
      AND mr.status = 'CREATED'
      AND h.run_id IS NULL
    FOR UPDATE OF r;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF NEW.target_backend_id IS NOT NULL
       OR NEW.target_instance_id IS NOT NULL
       OR NEW.routed_at IS NOT NULL
       OR NEW.pinned_instance THEN
        RAISE EXCEPTION 'Map encounter transfer must begin unrouted before exact reservation pinning'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    UPDATE transfer_tickets
    SET target_backend_id = reservation.target_backend_id,
        target_instance_id = reservation.target_instance_id,
        routed_at = NOW(),
        pinned_instance = TRUE
    WHERE transfer_id = NEW.transfer_id
      AND consumed_at IS NULL
      AND expires_at > NOW()
      AND target_backend_id IS NULL
      AND target_instance_id IS NULL
      AND pinned_instance = FALSE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Map encounter transfer changed before exact-instance pinning'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    INSERT INTO map_encounter_handoffs(
        run_id,
        reservation_id,
        transfer_id,
        player_id,
        target_instance_id,
        target_backend_id
    ) VALUES (
        reservation.run_id,
        reservation.reservation_id,
        NEW.transfer_id,
        reservation.player_id,
        reservation.target_instance_id,
        reservation.target_backend_id
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER transfer_tickets_auto_pin_map_encounter
AFTER INSERT
ON transfer_tickets
FOR EACH ROW
EXECUTE FUNCTION auto_pin_map_encounter_transfer();
