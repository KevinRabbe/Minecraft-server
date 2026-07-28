ALTER TABLE crafting_commissions
    ADD COLUMN completion_craft_id UUID UNIQUE REFERENCES craft_records(craft_id) ON DELETE RESTRICT;

ALTER TABLE crafting_commissions
    DROP CONSTRAINT crafting_commissions_shape_check;

ALTER TABLE crafting_commissions
    ADD CONSTRAINT crafting_commissions_shape_check CHECK (
        (
            status = 'OPEN'
            AND worker_player_id IS NULL
            AND accept_operation_id IS NULL
            AND cancel_operation_id IS NULL
            AND settle_operation_id IS NULL
            AND completion_craft_id IS NULL
            AND accepted_at IS NULL
            AND settled_at IS NULL
        )
        OR
        (
            status = 'ACCEPTED'
            AND worker_player_id IS NOT NULL
            AND worker_player_id <> requester_player_id
            AND accept_operation_id IS NOT NULL
            AND cancel_operation_id IS NULL
            AND settle_operation_id IS NULL
            AND completion_craft_id IS NULL
            AND accepted_at IS NOT NULL
            AND settled_at IS NULL
        )
        OR
        (
            status = 'COMPLETED'
            AND worker_player_id IS NOT NULL
            AND worker_player_id <> requester_player_id
            AND accept_operation_id IS NOT NULL
            AND cancel_operation_id IS NULL
            AND settle_operation_id IS NOT NULL
            AND completion_craft_id IS NOT NULL
            AND accepted_at IS NOT NULL
            AND settled_at IS NOT NULL
        )
        OR
        (
            status = 'CANCELLED'
            AND cancel_operation_id IS NOT NULL
            AND settle_operation_id IS NULL
            AND completion_craft_id IS NULL
            AND settled_at IS NOT NULL
        )
    );

CREATE OR REPLACE FUNCTION validate_commission_completion_craft()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    craft_player UUID;
    craft_recipe_id TEXT;
    craft_recipe_version INTEGER;
BEGIN
    IF NEW.status <> 'COMPLETED' THEN
        RETURN NEW;
    END IF;

    SELECT player_id, recipe_id, recipe_version
    INTO craft_player, craft_recipe_id, craft_recipe_version
    FROM craft_records
    WHERE craft_id = NEW.completion_craft_id;

    IF craft_player IS DISTINCT FROM NEW.worker_player_id
       OR craft_recipe_id IS DISTINCT FROM NEW.recipe_id
       OR craft_recipe_version IS DISTINCT FROM NEW.recipe_version THEN
        RAISE EXCEPTION 'commission completion craft does not match worker/recipe %', NEW.commission_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER crafting_commission_completion_craft_validate
AFTER INSERT OR UPDATE OF status, completion_craft_id
ON crafting_commissions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_commission_completion_craft();
