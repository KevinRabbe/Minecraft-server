CREATE TABLE starter_map_issuances (
    resource_kill_operation_id UUID PRIMARY KEY
        REFERENCES resource_harvests(operation_id) ON DELETE RESTRICT,
    issue_operation_id UUID NOT NULL UNIQUE,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    source_definition_id TEXT NOT NULL,
    delivery_id UUID NOT NULL UNIQUE
        REFERENCES pending_unique_deliveries(delivery_id) ON DELETE RESTRICT,
    item_instance_id UUID NOT NULL UNIQUE
        REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT starter_map_issuances_source_definition_not_blank
        CHECK (BTRIM(source_definition_id) <> '')
);

CREATE INDEX starter_map_issuances_player_time_idx
    ON starter_map_issuances(player_id, created_at DESC, resource_kill_operation_id);

CREATE OR REPLACE FUNCTION reject_starter_map_issuance_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'starter_map_issuances is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER starter_map_issuances_append_only
BEFORE UPDATE OR DELETE
ON starter_map_issuances
FOR EACH ROW
EXECUTE FUNCTION reject_starter_map_issuance_mutation();
