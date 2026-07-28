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
    IF NEW.open_operation_id IS NULL
       OR NEW.opened_by_player_id IS NULL
       OR NEW.source_item_expected_state_version IS NULL
       OR NEW.open_reason IS NULL THEN
        RAISE EXCEPTION 'new Map runs require authoritative open-operation evidence %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT location_kind, state_version
    INTO item_location_kind, item_state_version
    FROM item_instances
    WHERE item_instance_id = NEW.source_map_item_id;

    IF item_location_kind IS DISTINCT FROM 'DESTROYED' THEN
        RAISE EXCEPTION 'Map run source item must be consumed in the authoritative open transaction %', NEW.source_map_item_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF item_state_version <> NEW.source_item_expected_state_version + 1 THEN
        RAISE EXCEPTION 'Map run source item version does not match consumed predecessor %', NEW.source_map_item_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM item_provenance p
        WHERE p.item_instance_id = NEW.source_map_item_id
          AND p.sequence_no = item_state_version
          AND p.operation_id = NEW.open_operation_id
          AND p.event_type = 'DESTROYED'
          AND p.from_location_kind = 'PLAYER_INVENTORY'
          AND p.from_location_id = NEW.opened_by_player_id
          AND p.to_location_kind = 'DESTROYED'
          AND p.to_location_id IS NULL
          AND p.reason = NEW.open_reason
          AND p.actor_player_id = NEW.opened_by_player_id
    ) THEN
        RAISE EXCEPTION 'Map run open operation has no matching item-destruction provenance %', NEW.source_map_item_id
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
