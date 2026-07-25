CREATE TABLE resource_sources (
    source_id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES zone_instances(instance_id) ON DELETE RESTRICT,
    source_key TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    cycle_no BIGINT NOT NULL DEFAULT 0,
    next_available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (instance_id, source_key),
    CONSTRAINT resource_sources_key_check CHECK (source_key ~ '^[a-z0-9][a-z0-9._-]{0,95}$'),
    CONSTRAINT resource_sources_definition_check CHECK (definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT resource_sources_cycle_check CHECK (cycle_no >= 0),
    CONSTRAINT resource_sources_version_check CHECK (state_version >= 0)
);

CREATE INDEX resource_sources_instance_idx
    ON resource_sources(instance_id, source_key);

CREATE TABLE resource_harvests (
    harvest_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    source_id UUID NOT NULL REFERENCES resource_sources(source_id) ON DELETE RESTRICT,
    source_cycle_no BIGINT NOT NULL,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    commodity_definition_id TEXT NOT NULL,
    commodity_quantity BIGINT NOT NULL,
    skill_id TEXT,
    requested_experience BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (source_id, source_cycle_no),
    CONSTRAINT resource_harvests_cycle_check CHECK (source_cycle_no >= 0),
    CONSTRAINT resource_harvests_commodity_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT resource_harvests_quantity_check CHECK (commodity_quantity > 0),
    CONSTRAINT resource_harvests_skill_id_check CHECK (
        skill_id IS NULL OR skill_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT resource_harvests_experience_check CHECK (requested_experience >= 0),
    CONSTRAINT resource_harvests_skill_experience_shape_check CHECK (
        (skill_id IS NULL AND requested_experience = 0)
        OR (skill_id IS NOT NULL AND requested_experience > 0)
    )
);

CREATE INDEX resource_harvests_player_time_idx
    ON resource_harvests(player_id, created_at DESC);

CREATE TABLE resource_harvest_fulfillments (
    harvest_id UUID PRIMARY KEY REFERENCES resource_harvests(harvest_id) ON DELETE RESTRICT,
    commodity_delivery_id UUID NOT NULL UNIQUE REFERENCES pending_commodity_deliveries(delivery_id) ON DELETE RESTRICT,
    xp_operation_id UUID UNIQUE,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION reject_resource_harvest_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'resource_harvests is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER resource_harvests_append_only
BEFORE UPDATE OR DELETE
ON resource_harvests
FOR EACH ROW
EXECUTE FUNCTION reject_resource_harvest_mutation();

CREATE OR REPLACE FUNCTION reject_resource_harvest_fulfillment_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'resource_harvest_fulfillments is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER resource_harvest_fulfillments_append_only
BEFORE UPDATE OR DELETE
ON resource_harvest_fulfillments
FOR EACH ROW
EXECUTE FUNCTION reject_resource_harvest_fulfillment_mutation();
