CREATE TABLE map_item_profiles (
    item_instance_id UUID PRIMARY KEY REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    difficulty INTEGER NOT NULL,
    environment_id TEXT NOT NULL,
    enemy_family_id TEXT NOT NULL,
    objective_id TEXT NOT NULL,
    modifier_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    generation_seed BIGINT NOT NULL,
    generation_version INTEGER NOT NULL,
    balance_version INTEGER NOT NULL,
    world_era_id TEXT NOT NULL REFERENCES world_eras(era_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT map_item_profiles_difficulty_check CHECK (difficulty BETWEEN 1 AND 1000000),
    CONSTRAINT map_item_profiles_environment_id_check CHECK (environment_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_item_profiles_enemy_family_id_check CHECK (enemy_family_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_item_profiles_objective_id_check CHECK (objective_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_item_profiles_modifier_ids_array CHECK (jsonb_typeof(modifier_ids) = 'array'),
    CONSTRAINT map_item_profiles_generation_version_check CHECK (generation_version >= 0),
    CONSTRAINT map_item_profiles_balance_version_check CHECK (balance_version >= 0),
    CONSTRAINT map_item_profiles_world_era_id_check CHECK (world_era_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$')
);

CREATE OR REPLACE FUNCTION reject_map_item_profile_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'map_item_profiles is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER map_item_profiles_append_only
BEFORE UPDATE OR DELETE
ON map_item_profiles
FOR EACH ROW
EXECUTE FUNCTION reject_map_item_profile_mutation();

ALTER TABLE map_runs
    ADD COLUMN open_operation_id UUID UNIQUE,
    ADD COLUMN opened_by_player_id UUID REFERENCES players(player_id) ON DELETE RESTRICT,
    ADD COLUMN source_item_expected_state_version BIGINT,
    ADD COLUMN open_reason TEXT,
    ADD COLUMN start_operation_id UUID UNIQUE,
    ADD COLUMN start_expected_state_version BIGINT,
    ADD COLUMN start_reason TEXT,
    ADD COLUMN terminal_operation_id UUID UNIQUE,
    ADD COLUMN terminal_expected_state_version BIGINT,
    ADD COLUMN terminal_reason TEXT,
    ADD CONSTRAINT map_runs_world_era_fk
        FOREIGN KEY (world_era_id)
        REFERENCES world_eras(era_id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT map_runs_open_metadata_shape CHECK (
        (open_operation_id IS NULL AND opened_by_player_id IS NULL
            AND source_item_expected_state_version IS NULL AND open_reason IS NULL)
        OR
        (open_operation_id IS NOT NULL AND opened_by_player_id IS NOT NULL
            AND source_item_expected_state_version >= 0 AND BTRIM(open_reason) <> '')
    ),
    ADD CONSTRAINT map_runs_start_metadata_shape CHECK (
        (start_operation_id IS NULL AND start_expected_state_version IS NULL AND start_reason IS NULL)
        OR
        (start_operation_id IS NOT NULL AND start_expected_state_version >= 0 AND BTRIM(start_reason) <> '')
    ),
    ADD CONSTRAINT map_runs_terminal_metadata_shape CHECK (
        (terminal_operation_id IS NULL AND terminal_expected_state_version IS NULL AND terminal_reason IS NULL)
        OR
        (terminal_operation_id IS NOT NULL AND terminal_expected_state_version >= 0 AND BTRIM(terminal_reason) <> '')
    );

CREATE OR REPLACE FUNCTION validate_map_run_source_profile()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    item_location_kind TEXT;
    item_state_version BIGINT;
    profile_difficulty INTEGER;
    profile_environment_id TEXT;
    profile_enemy_family_id TEXT;
    profile_objective_id TEXT;
    profile_modifier_ids JSONB;
    profile_generation_seed BIGINT;
    profile_generation_version INTEGER;
    profile_balance_version INTEGER;
    profile_world_era_id TEXT;
BEGIN
    SELECT location_kind, state_version
    INTO item_location_kind, item_state_version
    FROM item_instances
    WHERE item_instance_id = NEW.source_map_item_id;

    IF item_location_kind IS DISTINCT FROM 'DESTROYED' THEN
        RAISE EXCEPTION 'Map run source item must already be consumed in the same authoritative transaction %', NEW.source_map_item_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT difficulty,
           environment_id,
           enemy_family_id,
           objective_id,
           modifier_ids,
           generation_seed,
           generation_version,
           balance_version,
           world_era_id
    INTO profile_difficulty,
         profile_environment_id,
         profile_enemy_family_id,
         profile_objective_id,
         profile_modifier_ids,
         profile_generation_seed,
         profile_generation_version,
         profile_balance_version,
         profile_world_era_id
    FROM map_item_profiles
    WHERE item_instance_id = NEW.source_map_item_id;

    IF profile_difficulty IS NULL THEN
        RAISE EXCEPTION 'Map run source item has no immutable Map profile %', NEW.source_map_item_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.difficulty <> profile_difficulty
       OR NEW.environment_id IS DISTINCT FROM profile_environment_id
       OR NEW.enemy_family_id IS DISTINCT FROM profile_enemy_family_id
       OR NEW.objective_id IS DISTINCT FROM profile_objective_id
       OR NEW.modifier_ids IS DISTINCT FROM profile_modifier_ids
       OR NEW.generation_seed <> profile_generation_seed
       OR NEW.generation_version <> profile_generation_version
       OR NEW.balance_version <> profile_balance_version
       OR NEW.world_era_id IS DISTINCT FROM profile_world_era_id THEN
        RAISE EXCEPTION 'Map run definition does not match immutable source Map profile %', NEW.source_map_item_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_runs_validate_source_profile
BEFORE INSERT
ON map_runs
FOR EACH ROW
EXECUTE FUNCTION validate_map_run_source_profile();

CREATE OR REPLACE FUNCTION validate_map_run_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source_map_item_id <> OLD.source_map_item_id
       OR NEW.difficulty <> OLD.difficulty
       OR NEW.environment_id IS DISTINCT FROM OLD.environment_id
       OR NEW.enemy_family_id IS DISTINCT FROM OLD.enemy_family_id
       OR NEW.objective_id IS DISTINCT FROM OLD.objective_id
       OR NEW.modifier_ids IS DISTINCT FROM OLD.modifier_ids
       OR NEW.generation_seed <> OLD.generation_seed
       OR NEW.generation_version <> OLD.generation_version
       OR NEW.balance_version <> OLD.balance_version
       OR NEW.world_era_id IS DISTINCT FROM OLD.world_era_id
       OR NEW.open_operation_id IS DISTINCT FROM OLD.open_operation_id
       OR NEW.opened_by_player_id IS DISTINCT FROM OLD.opened_by_player_id
       OR NEW.source_item_expected_state_version IS DISTINCT FROM OLD.source_item_expected_state_version
       OR NEW.open_reason IS DISTINCT FROM OLD.open_reason THEN
        RAISE EXCEPTION 'Map run immutable source/open definition cannot change %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'CREATED' AND NEW.status NOT IN ('CREATED', 'ACTIVE', 'FAILED', 'CLOSED') THEN
        RAISE EXCEPTION 'invalid Map run transition CREATED -> % for %', NEW.status, NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('ACTIVE', 'COMPLETED', 'FAILED', 'CLOSED') THEN
        RAISE EXCEPTION 'invalid Map run transition ACTIVE -> % for %', NEW.status, NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('COMPLETED', 'FAILED', 'CLOSED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'terminal Map run cannot transition %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.start_operation_id IS NOT NULL AND (
        NEW.start_operation_id IS DISTINCT FROM OLD.start_operation_id
        OR NEW.start_expected_state_version IS DISTINCT FROM OLD.start_expected_state_version
        OR NEW.start_reason IS DISTINCT FROM OLD.start_reason
        OR NEW.started_at IS DISTINCT FROM OLD.started_at
    ) THEN
        RAISE EXCEPTION 'Map run start evidence is immutable %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.terminal_operation_id IS NOT NULL AND (
        NEW.terminal_operation_id IS DISTINCT FROM OLD.terminal_operation_id
        OR NEW.terminal_expected_state_version IS DISTINCT FROM OLD.terminal_expected_state_version
        OR NEW.terminal_reason IS DISTINCT FROM OLD.terminal_reason
        OR NEW.finished_at IS DISTINCT FROM OLD.finished_at
    ) THEN
        RAISE EXCEPTION 'Map run terminal evidence is immutable %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_runs_validate_transition
BEFORE UPDATE
ON map_runs
FOR EACH ROW
EXECUTE FUNCTION validate_map_run_transition();

CREATE OR REPLACE FUNCTION validate_map_participant_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_run_id UUID;
    run_status TEXT;
BEGIN
    target_run_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.run_id ELSE NEW.run_id END;

    SELECT status INTO run_status
    FROM map_runs
    WHERE run_id = target_run_id;

    IF run_status IS DISTINCT FROM 'CREATED' THEN
        RAISE EXCEPTION 'Map participants are immutable after run start %', target_run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Map participant rows are immutable; replace only before start %', target_run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER map_run_participants_validate_mutation
BEFORE INSERT OR UPDATE OR DELETE
ON map_run_participants
FOR EACH ROW
EXECUTE FUNCTION validate_map_participant_mutation();
