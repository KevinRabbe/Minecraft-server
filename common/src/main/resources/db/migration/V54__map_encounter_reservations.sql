-- Map consumption must have a concrete disposable encounter slot reserved first. The reservation is persistent state
-- linking the source Map item and player to one exact live zone instance before the Map is destroyed.

CREATE TABLE map_encounter_reservations (
    reservation_id UUID PRIMARY KEY,
    source_map_item_id UUID NOT NULL REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    target_instance_id UUID NOT NULL REFERENCES zone_instances(instance_id) ON DELETE RESTRICT,
    target_backend_id TEXT NOT NULL REFERENCES backends(backend_id) ON DELETE RESTRICT,
    target_zone_id TEXT NOT NULL,
    target_template_version TEXT NOT NULL,
    status TEXT NOT NULL,
    run_id UUID UNIQUE REFERENCES map_runs(run_id) ON DELETE RESTRICT,
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

CREATE OR REPLACE FUNCTION validate_map_encounter_reservation_target()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    actual_backend TEXT;
    actual_zone TEXT;
    actual_template TEXT;
BEGIN
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

CREATE TRIGGER map_encounter_reservations_validate_target
BEFORE INSERT
ON map_encounter_reservations
FOR EACH ROW
EXECUTE FUNCTION validate_map_encounter_reservation_target();

CREATE OR REPLACE FUNCTION validate_map_encounter_reservation_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reservation_id IS DISTINCT FROM OLD.reservation_id
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
        ) THEN
            RAISE EXCEPTION 'Map encounter reservation run does not match source Map/player'
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
    ADD COLUMN encounter_reservation_id UUID UNIQUE
        REFERENCES map_encounter_reservations(reservation_id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION validate_map_open_encounter_reservation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.encounter_reservation_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM map_encounter_reservations r
        WHERE r.reservation_id = NEW.encounter_reservation_id
          AND r.status = 'BOUND'
          AND r.run_id = NEW.run_id
    ) THEN
        RAISE EXCEPTION 'Map open evidence reservation is not bound to run %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER map_open_player_state_evidence_validate_reservation
BEFORE INSERT
ON map_open_player_state_evidence
FOR EACH ROW
EXECUTE FUNCTION validate_map_open_encounter_reservation();
