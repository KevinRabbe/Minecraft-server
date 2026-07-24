CREATE TABLE item_instances (
    item_instance_id UUID PRIMARY KEY,
    definition_id TEXT NOT NULL,
    location_kind TEXT NOT NULL,
    location_id UUID,
    state_version BIGINT NOT NULL DEFAULT 0,
    original_owner_player_id UUID NOT NULL REFERENCES players(player_id),
    created_by_operation_id UUID NOT NULL UNIQUE,
    created_reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT item_instances_version_check CHECK (state_version >= 0),
    CONSTRAINT item_instances_location_kind_check CHECK (
        location_kind IN ('PLAYER_INVENTORY', 'QUARANTINE', 'DESTROYED')
    ),
    CONSTRAINT item_instances_location_shape_check CHECK (
        (location_kind = 'PLAYER_INVENTORY' AND location_id IS NOT NULL)
        OR
        (location_kind IN ('QUARANTINE', 'DESTROYED') AND location_id IS NULL)
    )
);

CREATE INDEX item_instances_player_location_idx
    ON item_instances(location_id, definition_id)
    WHERE location_kind = 'PLAYER_INVENTORY';

CREATE INDEX item_instances_definition_idx
    ON item_instances(definition_id)
    WHERE location_kind <> 'DESTROYED';

CREATE TABLE item_provenance (
    provenance_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_instance_id UUID NOT NULL REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    sequence_no BIGINT NOT NULL,
    operation_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    from_location_kind TEXT,
    from_location_id UUID,
    to_location_kind TEXT NOT NULL,
    to_location_id UUID,
    reason TEXT NOT NULL,
    actor_player_id UUID REFERENCES players(player_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT item_provenance_sequence_check CHECK (sequence_no >= 0),
    CONSTRAINT item_provenance_event_type_check CHECK (
        event_type IN ('CREATED', 'MOVED', 'QUARANTINED', 'DESTROYED', 'RECOVERED')
    ),
    CONSTRAINT item_provenance_to_location_kind_check CHECK (
        to_location_kind IN ('PLAYER_INVENTORY', 'QUARANTINE', 'DESTROYED')
    ),
    CONSTRAINT item_provenance_to_location_shape_check CHECK (
        (to_location_kind = 'PLAYER_INVENTORY' AND to_location_id IS NOT NULL)
        OR
        (to_location_kind IN ('QUARANTINE', 'DESTROYED') AND to_location_id IS NULL)
    ),
    CONSTRAINT item_provenance_from_location_kind_check CHECK (
        from_location_kind IS NULL
        OR from_location_kind IN ('PLAYER_INVENTORY', 'QUARANTINE', 'DESTROYED')
    ),
    CONSTRAINT item_provenance_from_location_shape_check CHECK (
        (from_location_kind IS NULL AND from_location_id IS NULL)
        OR
        (from_location_kind = 'PLAYER_INVENTORY' AND from_location_id IS NOT NULL)
        OR
        (from_location_kind IN ('QUARANTINE', 'DESTROYED') AND from_location_id IS NULL)
    ),
    CONSTRAINT item_provenance_item_sequence_unique UNIQUE (item_instance_id, sequence_no),
    CONSTRAINT item_provenance_item_operation_unique UNIQUE (item_instance_id, operation_id)
);

CREATE INDEX item_provenance_item_time_idx
    ON item_provenance(item_instance_id, created_at, provenance_event_id);

CREATE OR REPLACE FUNCTION validate_item_instance_player_location()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.location_id) THEN
        RAISE EXCEPTION 'PLAYER_INVENTORY location references unknown player_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER item_instances_validate_player_location
BEFORE INSERT OR UPDATE OF location_kind, location_id
ON item_instances
FOR EACH ROW
EXECUTE FUNCTION validate_item_instance_player_location();

CREATE OR REPLACE FUNCTION validate_item_provenance_player_locations()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.from_location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.from_location_id) THEN
        RAISE EXCEPTION 'item provenance from-location references unknown player_id %', NEW.from_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.to_location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.to_location_id) THEN
        RAISE EXCEPTION 'item provenance to-location references unknown player_id %', NEW.to_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER item_provenance_validate_player_locations
BEFORE INSERT
ON item_provenance
FOR EACH ROW
EXECUTE FUNCTION validate_item_provenance_player_locations();

CREATE OR REPLACE FUNCTION reject_item_provenance_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'item_provenance is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER item_provenance_append_only
BEFORE UPDATE OR DELETE
ON item_provenance
FOR EACH ROW
EXECUTE FUNCTION reject_item_provenance_mutation();

CREATE OR REPLACE FUNCTION validate_item_instance_provenance_head()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM item_provenance p
        WHERE p.item_instance_id = NEW.item_instance_id
          AND p.sequence_no = NEW.state_version
          AND p.to_location_kind = NEW.location_kind
          AND p.to_location_id IS NOT DISTINCT FROM NEW.location_id
    ) THEN
        RAISE EXCEPTION 'item authority head % version % has no matching provenance row',
            NEW.item_instance_id,
            NEW.state_version
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER item_instances_require_provenance_head
AFTER INSERT OR UPDATE
ON item_instances
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_item_instance_provenance_head();
