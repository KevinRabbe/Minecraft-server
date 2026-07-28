-- Map consumption must have a concrete disposable encounter slot reserved first. The reservation is persistent state
-- linking the source Map item and player to one exact live zone instance before the Map is destroyed.
--
-- Paper uses the same open_operation_id for reservation and Map opening. A database trigger therefore binds the exact
-- reservation to the newly created run inside the existing state-coupled Map-open transaction without duplicating the
-- mature Map item/player-state authority.
--
-- Cross-table validity is enforced with triggers rather than foreign keys so adding this feature does not make unrelated
-- integration-test/item/session TRUNCATE fixtures depend on the Map reservation table.

CREATE TABLE map_encounter_reservations (
    reservation_id UUID PRIMARY KEY,
    open_operation_id UUID NOT NULL UNIQUE,
    source_map_item_id UUID NOT NULL,
    player_id UUID NOT NULL,
    target_instance_id UUID NOT NULL,
    target_backend_id TEXT NOT NULL,
    target_zone_id TEXT NOT NULL,
    target_template_version TEXT NOT NULL,
    status TEXT NOT NULL,
    run_id UUID UNIQUE,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    bound_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT map_encounter_reservations_status_check CHECK (
        status IN ('RESERVED', 'BOUND', 'RELEASED', 'EXPIRED')
    ),
    CONSTRAINT map_encounter_reservations_version_check CHECK (state_version >= 0),
    CONSTRAINT map_encounter_reservations_zone_check CHECK (
        target_zone_id ~ '^[a-z0-9][a-z0-9._-]{0,95}$'
    ),
    CONSTRAINT map_encounter_reservations_template_check CHECK (
        length(btrim(target_template_version)) BETWEEN 1 AND 96
    ),
    CONSTRAINT map_encounter_reservations_shape_check CHECK (
        (status = 'RESERVED'
            AND run_id IS NULL
            AND bound_at IS NULL
            AND resolved_at IS NULL)
        OR
        (status = 'BOUND'
            AND run_id IS NOT NULL
            AND bound_at IS NOT NULL
            AND resolved_at IS NULL)
        OR
        (status = 'RELEASED'
            AND resolved_at IS NOT NULL
            AND ((run_id IS NULL AND bound_at IS NULL) OR (run_id IS NOT NULL AND bound_at IS NOT NULL)))
        OR
        (status = 'EXPIRED'
            AND run_id IS NULL
            AND bound_at IS NULL
            AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX map_encounter_reservations_active_item_idx
    ON map_encounter_reservations(source_map_item_id)
    WHERE status IN ('RESERVED', 'BOUND');

CREATE UNIQUE INDEX map_encounter_reservations_active_instance_idx
    ON map_encounter_reservations(target_instance_id)
    WHERE status IN ('RESERVED', 'BOUND');

CREATE INDEX map_encounter_reservations_recovery_idx
    ON map_encounter_reservations(status, lease_expires_at)
    WHERE status = 'RESERVED';

CREATE OR REPLACE FUNCTION validate_map_encounter_reservation_source_and_target()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    actual_backend TEXT;
    actual_zone TEXT;
    actual_template TEXT;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM item_instances i
        JOIN map_item_profiles p ON p.item_instance_id = i.item_instance_id
        WHERE i.item_instance_id = NEW.source_map_item_id
          AND i.location_kind = 'PLAYER_INVENTORY'
          AND i.location_id = NEW.player_id
    ) THEN
        RAISE EXCEPTION 'Map encounter reservation source item is not an owned Map for player %', NEW.player_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT backend_id, zone_id, template_version
    INTO actual_backend, actual_zone, actual_template
    FROM zone_instances
    WHERE instance_id = NEW.target_instance_id;

    IF actual_backend IS NULL
       OR actual_backend IS DISTINCT FROM NEW.target_backend_id
       OR actual_zone IS DISTINCT FROM NEW.target_zone_id
       OR actual_template IS DISTINCT FROM NEW.target_template_version THEN
        RAISE EXCEPTION 'Map encounter reservation target snapshot does not match zone instance %', NEW.target_instance_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER map_encounter_reservations_validate_source_target
BEFORE INSERT
ON map_encounter_reservations
FOR EACH ROW
EXECUTE FUNCTION validate_map_encounter_reservation_source_and_target();

CREATE OR REPLACE FUNCTION validate_map_encounter_reservation_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reservation_id IS DISTINCT FROM OLD.reservation_id
       OR NEW.open_operation_id IS DISTINCT FROM OLD.open_operation_id
       OR NEW.source_map_item_id IS DISTINCT FROM OLD.source_map_item_id
       OR NEW.player_id IS DISTINCT FROM OLD.player_id
       OR NEW.target_instance_id IS DISTINCT FROM OLD.target_instance_id
       OR NEW.target_backend_id IS DISTINCT FROM OLD.target_backend_id
       OR NEW.target_zone_id IS DISTINCT FROM OLD.target_zone_id
       OR NEW.target_template_version IS DISTINCT FROM OLD.target_template_version
       OR NEW.lease_expires_at IS DISTINCT FROM OLD.lease_expires_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Map encounter reservation identity is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('RELEASED', 'EXPIRED') THEN
        RAISE EXCEPTION 'terminal Map encounter reservation is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'RESERVED' AND NEW.status NOT IN ('BOUND', 'RELEASED', 'EXPIRED') THEN
        RAISE EXCEPTION 'invalid Map encounter reservation transition RESERVED -> %', NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'BOUND' AND NEW.status <> 'RELEASED' THEN
        RAISE EXCEPTION 'invalid Map encounter reservation transition BOUND -> %', NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status = 'BOUND' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM map_runs r
            WHERE r.run_id = NEW.run_id
              AND r.source_map_item_id = NEW.source_map_item_id
              AND r.opened_by_player_id = NEW.player_id
              AND r.open_operation_id = NEW.open_operation_id
        ) THEN
            RAISE EXCEPTION 'Map encounter reservation run does not match source Map/player/open operation'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_encounter_reservations_validate_transition
BEFORE UPDATE
ON map_encounter_reservations
FOR EACH ROW
EXECUTE FUNCTION validate_map_encounter_reservation_transition();

CREATE OR REPLACE FUNCTION reject_map_encounter_reservation_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'map_encounter_reservations history cannot be deleted'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER map_encounter_reservations_no_delete
BEFORE DELETE
ON map_encounter_reservations
FOR EACH ROW
EXECUTE FUNCTION reject_map_encounter_reservation_delete();

ALTER TABLE map_open_player_state_evidence
    ADD COLUMN encounter_reservation_id UUID UNIQUE;

CREATE OR REPLACE FUNCTION bind_map_open_encounter_reservation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    reservation RECORD;
    run_source_map_item_id UUID;
    run_player_id UUID;
BEGIN
    SELECT r.*
    INTO reservation
    FROM map_encounter_reservations r
    WHERE r.open_operation_id = NEW.open_operation_id
    FOR UPDATE;

    IF FOUND THEN
        IF reservation.status IS DISTINCT FROM 'RESERVED'
           OR reservation.lease_expires_at <= NOW() THEN
            RAISE EXCEPTION 'Map open reservation is no longer valid for operation %', NEW.open_operation_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        SELECT source_map_item_id, opened_by_player_id
        INTO run_source_map_item_id, run_player_id
        FROM map_runs
        WHERE run_id = NEW.run_id;

        IF run_source_map_item_id IS DISTINCT FROM reservation.source_map_item_id
           OR run_player_id IS DISTINCT FROM reservation.player_id THEN
            RAISE EXCEPTION 'Map open run does not match reserved source Map/player'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        UPDATE map_encounter_reservations
        SET status = 'BOUND',
            run_id = NEW.run_id,
            state_version = state_version + 1,
            bound_at = NOW()
        WHERE reservation_id = reservation.reservation_id
          AND status = 'RESERVED'
          AND lease_expires_at > NOW();

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Map encounter reservation changed concurrently during open'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        NEW.encounter_reservation_id := reservation.reservation_id;
    END IF;

    IF NEW.encounter_reservation_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM map_encounter_reservations r
        WHERE r.reservation_id = NEW.encounter_reservation_id
          AND r.status = 'BOUND'
          AND r.run_id = NEW.run_id
          AND r.open_operation_id = NEW.open_operation_id
    ) THEN
        RAISE EXCEPTION 'Map open evidence reservation is not bound to run %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_open_player_state_evidence_bind_reservation
BEFORE INSERT
ON map_open_player_state_evidence
FOR EACH ROW
EXECUTE FUNCTION bind_map_open_encounter_reservation();
