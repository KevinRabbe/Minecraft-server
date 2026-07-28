-- V73 makes an intentionally empty Clan-War loadout distinguishable from an unfinished/corrupted snapshot.
-- Make that distinction part of the runtime trust boundary as well: a mapped legacy backend may receive an empty page
-- only from a sealed Clan-War snapshot. A missing seal on its own live execution fails closed and therefore prevents
-- the runtime from renewing that execution.

CREATE OR REPLACE FUNCTION validate_competitive_execution_loadout_seal_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    execution_kind TEXT;
    expected_participants INTEGER;
    actual_participants INTEGER;
BEGIN
    SELECT e.activity_kind, s.team_size * 2
    INTO execution_kind, expected_participants
    FROM competitive_executions e
    LEFT JOIN competitive_execution_specs s ON s.execution_id = e.execution_id
    WHERE e.execution_id = NEW.execution_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive execution loadout seal requires an existing execution %', NEW.execution_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF execution_kind <> 'CLAN_WAR' THEN
        RAISE EXCEPTION 'competitive execution loadout seals are valid only for Clan War executions'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF expected_participants IS NULL THEN
        RAISE EXCEPTION 'competitive execution loadout seal requires a frozen runtime spec %', NEW.execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT COUNT(*) INTO actual_participants
    FROM competitive_execution_participants participant
    WHERE participant.execution_id = NEW.execution_id;

    IF actual_participants <> expected_participants THEN
        RAISE EXCEPTION 'competitive execution loadout seal requires a complete frozen participant manifest %', NEW.execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_execution_loadout_seals_validate_insert
BEFORE INSERT
ON competitive_execution_loadout_seals
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_execution_loadout_seal_insert();

CREATE OR REPLACE FUNCTION competitive_runtime_page_loadout(
    target_execution_id UUID,
    after_participant_index INTEGER,
    after_loadout_item_index INTEGER,
    item_limit INTEGER
)
RETURNS TABLE (
    participant_index INTEGER,
    loadout_item_index INTEGER,
    definition_id TEXT,
    roll_state_json TEXT,
    upgrade_level INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    mapped_backend TEXT;
    execution_kind TEXT;
    snapshot_sealed BOOLEAN;
BEGIN
    IF target_execution_id IS NULL THEN
        RAISE EXCEPTION 'target_execution_id must not be null'
            USING ERRCODE = 'check_violation';
    END IF;

    IF item_limit IS NULL OR item_limit < 1 OR item_limit > 500 THEN
        RAISE EXCEPTION 'item_limit must be between 1 and 500'
            USING ERRCODE = 'check_violation';
    END IF;

    IF (after_participant_index IS NULL) <> (after_loadout_item_index IS NULL) THEN
        RAISE EXCEPTION 'loadout cursor fields must both be null or both be present'
            USING ERRCODE = 'check_violation';
    END IF;

    IF after_participant_index IS NOT NULL
       AND (after_participant_index < 0 OR after_loadout_item_index < 0) THEN
        RAISE EXCEPTION 'loadout cursor values must be nonnegative'
            USING ERRCODE = 'check_violation';
    END IF;

    mapped_backend := require_competitive_runtime_backend();

    SELECT execution.activity_kind,
           EXISTS (
               SELECT 1
               FROM competitive_execution_loadout_seals seal
               WHERE seal.execution_id = execution.execution_id
           )
    INTO execution_kind, snapshot_sealed
    FROM competitive_executions execution
    WHERE execution.execution_id = target_execution_id
      AND execution.backend_id = mapped_backend
      AND execution.status = 'ACTIVE'
      AND execution.lease_expires_at > NOW()
      AND NOT EXISTS (
          SELECT 1
          FROM competitive_result_reports report
          WHERE report.execution_id = execution.execution_id
      );

    -- Unknown, expired, terminal, reported, or another backend's execution remains indistinguishable from not found.
    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF execution_kind <> 'CLAN_WAR' THEN
        RAISE EXCEPTION 'competitive runtime loadout requested for non-Clan-War execution %', target_execution_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF NOT snapshot_sealed THEN
        RAISE EXCEPTION 'competitive execution loadout snapshot is not sealed %', target_execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN QUERY
    SELECT loadout.participant_index,
           loadout.loadout_item_index,
           loadout.definition_id,
           loadout.roll_state::TEXT,
           loadout.upgrade_level
    FROM competitive_executions execution
    JOIN competitive_execution_loadout_seals seal
      ON seal.execution_id = execution.execution_id
    JOIN competitive_execution_loadout_items loadout
      ON loadout.execution_id = execution.execution_id
    WHERE execution.execution_id = target_execution_id
      AND execution.backend_id = mapped_backend
      AND execution.activity_kind = 'CLAN_WAR'
      AND execution.status = 'ACTIVE'
      AND execution.lease_expires_at > NOW()
      AND NOT EXISTS (
          SELECT 1
          FROM competitive_result_reports report
          WHERE report.execution_id = execution.execution_id
      )
      AND (
          after_participant_index IS NULL
          OR loadout.participant_index > after_participant_index
          OR (
              loadout.participant_index = after_participant_index
              AND loadout.loadout_item_index > after_loadout_item_index
          )
      )
    ORDER BY loadout.participant_index ASC, loadout.loadout_item_index ASC
    LIMIT item_limit;
END;
$$;
