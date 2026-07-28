CREATE TABLE salvage_records (
    salvage_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    item_instance_id UUID NOT NULL UNIQUE REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    item_definition_id TEXT NOT NULL,
    destroyed_item_version BIGINT NOT NULL,
    coin_return_minor BIGINT NOT NULL DEFAULT 0,
    commodity_returns JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT salvage_records_definition_check CHECK (
        item_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT salvage_records_item_version_check CHECK (destroyed_item_version > 0),
    CONSTRAINT salvage_records_coin_return_check CHECK (coin_return_minor >= 0),
    CONSTRAINT salvage_records_commodity_returns_object_check CHECK (jsonb_typeof(commodity_returns) = 'object')
);

CREATE INDEX salvage_records_player_time_idx
    ON salvage_records(player_id, created_at DESC);

CREATE OR REPLACE FUNCTION validate_salvage_record()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_definition TEXT;
    current_location TEXT;
    current_version BIGINT;
BEGIN
    SELECT definition_id, location_kind, state_version
    INTO current_definition, current_location, current_version
    FROM item_instances
    WHERE item_instance_id = NEW.item_instance_id;

    IF current_definition IS DISTINCT FROM NEW.item_definition_id
       OR current_location IS DISTINCT FROM 'DESTROYED'
       OR current_version IS DISTINCT FROM NEW.destroyed_item_version THEN
        RAISE EXCEPTION 'salvage record does not match destroyed item authority %', NEW.item_instance_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM item_provenance p
        WHERE p.item_instance_id = NEW.item_instance_id
          AND p.sequence_no = NEW.destroyed_item_version
          AND p.operation_id = NEW.operation_id
          AND p.to_location_kind = 'DESTROYED'
          AND p.to_location_id IS NULL
    ) THEN
        RAISE EXCEPTION 'salvage record lacks matching destruction provenance %', NEW.item_instance_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER salvage_records_validate_authority
AFTER INSERT
ON salvage_records
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_salvage_record();

CREATE OR REPLACE FUNCTION reject_salvage_record_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'salvage_records is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER salvage_records_append_only
BEFORE UPDATE OR DELETE
ON salvage_records
FOR EACH ROW
EXECUTE FUNCTION reject_salvage_record_mutation();
