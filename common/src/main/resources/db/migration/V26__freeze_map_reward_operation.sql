CREATE OR REPLACE FUNCTION validate_map_reward_operation_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.reward_operation_id IS NOT NULL
       AND NEW.reward_operation_id IS DISTINCT FROM OLD.reward_operation_id THEN
        RAISE EXCEPTION 'Map reward operation is immutable once assigned %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.reward_operation_id IS NULL
       AND NEW.reward_operation_id IS NOT NULL
       AND OLD.status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION 'Map reward operation may be assigned only after completion %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_runs_validate_reward_operation
BEFORE UPDATE OF reward_operation_id
ON map_runs
FOR EACH ROW
EXECUTE FUNCTION validate_map_reward_operation_transition();
