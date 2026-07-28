CREATE OR REPLACE FUNCTION validate_crafting_commission_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'OPEN' AND NEW.status IN ('ACCEPTED', 'CANCELLED') THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'ACCEPTED' AND NEW.status = 'COMPLETED' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid crafting commission transition % -> % for %',
        OLD.status,
        NEW.status,
        OLD.commission_id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER crafting_commissions_validate_transition
BEFORE UPDATE OF status
ON crafting_commissions
FOR EACH ROW
EXECUTE FUNCTION validate_crafting_commission_transition();
