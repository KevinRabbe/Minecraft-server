-- V71 freezes Clan-War equipment into an execution-scoped identity-free snapshot.
-- Expose that snapshot to the isolated 1.8.9 runtime only through a principal-bound,
-- keyset-paginated SECURITY DEFINER API. Persistent item_instance_id never crosses this boundary.

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
BEGIN
    IF target_execution_id IS NULL THEN
        RAISE EXCEPTION 'target_execution_id must not be null'
            USING ERRCODE = 'check_violation';
    END IF;

    IF item_limit < 1 OR item_limit > 500 THEN
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

    RETURN QUERY
    SELECT loadout.participant_index,
           loadout.loadout_item_index,
           loadout.definition_id,
           loadout.roll_state::TEXT,
           loadout.upgrade_level
    FROM competitive_executions execution
    JOIN competitive_execution_loadout_items loadout
      ON loadout.execution_id = execution.execution_id
    WHERE execution.execution_id = target_execution_id
      AND execution.backend_id = mapped_backend
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
