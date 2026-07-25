CREATE OR REPLACE FUNCTION reject_crafting_commission_material_mutation_after_open()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_commission_id UUID;
    current_status TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_commission_id := OLD.commission_id;
    ELSE
        target_commission_id := NEW.commission_id;
    END IF;

    SELECT status INTO current_status
    FROM crafting_commissions
    WHERE commission_id = target_commission_id;

    IF current_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'crafting commission materials are immutable after acceptance %', target_commission_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;
