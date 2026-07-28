CREATE OR REPLACE FUNCTION validate_crafting_commission_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.requester_player_id IS DISTINCT FROM OLD.requester_player_id
       OR NEW.recipe_id IS DISTINCT FROM OLD.recipe_id
       OR NEW.recipe_version IS DISTINCT FROM OLD.recipe_version
       OR NEW.payment_minor IS DISTINCT FROM OLD.payment_minor
       OR NEW.create_operation_id IS DISTINCT FROM OLD.create_operation_id THEN
        RAISE EXCEPTION 'crafting commission identity/economic terms are immutable %', OLD.commission_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('COMPLETED', 'CANCELLED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal crafting commission is immutable %', OLD.commission_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER crafting_commissions_immutability
BEFORE UPDATE
ON crafting_commissions
FOR EACH ROW
EXECUTE FUNCTION validate_crafting_commission_immutability();
