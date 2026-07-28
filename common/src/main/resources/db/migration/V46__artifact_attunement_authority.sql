-- Hidden Artifacts are permanent per-player discoveries, not inventory/economy assets.
-- Artifact identity survives location changes; Attunement Points derive from immutable discovery evidence.

CREATE TABLE artifact_definitions (
    artifact_id UUID PRIMARY KEY,
    definition_operation_id UUID NOT NULL UNIQUE,
    point_value INTEGER NOT NULL,
    point_policy_version INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT artifact_definitions_point_value_check CHECK (point_value BETWEEN 1 AND 1000),
    CONSTRAINT artifact_definitions_policy_version_check CHECK (point_policy_version >= 1)
);

CREATE TABLE artifact_locations (
    artifact_id UUID NOT NULL REFERENCES artifact_definitions(artifact_id) ON DELETE RESTRICT,
    location_revision BIGINT NOT NULL,
    operation_id UUID NOT NULL UNIQUE,
    world_key TEXT NOT NULL,
    logical_zone_id TEXT,
    block_x INTEGER NOT NULL,
    block_y INTEGER NOT NULL,
    block_z INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (artifact_id, location_revision),
    CONSTRAINT artifact_locations_revision_check CHECK (location_revision >= 1),
    CONSTRAINT artifact_locations_world_key_check CHECK (world_key ~ '^[a-z0-9][a-z0-9._:/-]{0,127}$'),
    CONSTRAINT artifact_locations_zone_check CHECK (
        logical_zone_id IS NULL OR logical_zone_id ~ '^[a-z0-9][a-z0-9._:/-]{0,127}$'
    )
);

CREATE TABLE player_artifact_discoveries (
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE CASCADE,
    artifact_id UUID NOT NULL REFERENCES artifact_definitions(artifact_id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL UNIQUE,
    location_revision BIGINT NOT NULL,
    points_awarded INTEGER NOT NULL,
    point_policy_version INTEGER NOT NULL,
    world_era_context TEXT,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_id, artifact_id),
    FOREIGN KEY (artifact_id, location_revision)
        REFERENCES artifact_locations(artifact_id, location_revision) ON DELETE RESTRICT,
    CONSTRAINT player_artifact_discoveries_points_check CHECK (points_awarded BETWEEN 1 AND 1000),
    CONSTRAINT player_artifact_discoveries_policy_version_check CHECK (point_policy_version >= 1),
    CONSTRAINT player_artifact_discoveries_world_era_context_check CHECK (
        world_era_context IS NULL OR length(world_era_context) BETWEEN 1 AND 128
    )
);

CREATE INDEX player_artifact_discoveries_history_idx
    ON player_artifact_discoveries(player_id, discovered_at, artifact_id);

CREATE TABLE player_attunement_state (
    player_id UUID PRIMARY KEY REFERENCES players(player_id) ON DELETE CASCADE,
    active_profile_id TEXT,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT player_attunement_state_profile_check CHECK (
        active_profile_id IS NULL OR active_profile_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT player_attunement_state_version_check CHECK (state_version >= 0)
);

CREATE OR REPLACE FUNCTION validate_artifact_location_append_only()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'artifact_locations is append-only; relocate by inserting a new revision'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER artifact_locations_append_only
BEFORE UPDATE OR DELETE
ON artifact_locations
FOR EACH ROW
EXECUTE FUNCTION validate_artifact_location_append_only();

CREATE OR REPLACE FUNCTION validate_artifact_discovery_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    artifact_enabled BOOLEAN;
    expected_points INTEGER;
    expected_policy INTEGER;
    current_revision BIGINT;
BEGIN
    SELECT enabled, point_value, point_policy_version
    INTO artifact_enabled, expected_points, expected_policy
    FROM artifact_definitions
    WHERE artifact_id = NEW.artifact_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown artifact %', NEW.artifact_id USING ERRCODE = 'foreign_key_violation';
    END IF;

    SELECT MAX(location_revision)
    INTO current_revision
    FROM artifact_locations
    WHERE artifact_id = NEW.artifact_id;

    IF artifact_enabled IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'artifact % is disabled', NEW.artifact_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF current_revision IS NULL
       OR NEW.location_revision IS DISTINCT FROM current_revision
       OR NEW.points_awarded IS DISTINCT FROM expected_points
       OR NEW.point_policy_version IS DISTINCT FROM expected_policy THEN
        RAISE EXCEPTION 'artifact discovery does not match current definition/location authority for %', NEW.artifact_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER player_artifact_discoveries_validate
BEFORE INSERT
ON player_artifact_discoveries
FOR EACH ROW
EXECUTE FUNCTION validate_artifact_discovery_insert();

CREATE OR REPLACE FUNCTION reject_artifact_discovery_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'player_artifact_discoveries is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER player_artifact_discoveries_append_only
BEFORE UPDATE OR DELETE
ON player_artifact_discoveries
FOR EACH ROW
EXECUTE FUNCTION reject_artifact_discovery_mutation();
