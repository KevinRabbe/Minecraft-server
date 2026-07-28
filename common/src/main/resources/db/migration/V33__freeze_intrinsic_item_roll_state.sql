CREATE OR REPLACE FUNCTION validate_item_roll_state()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    property_key TEXT;
    property_value JSONB;
    numeric_value BIGINT;
BEGIN
    IF jsonb_typeof(NEW.roll_state) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'item roll_state must be a JSON object'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    FOR property_key, property_value IN
        SELECT key, value FROM jsonb_each(NEW.roll_state)
    LOOP
        IF property_key !~ '^[a-z0-9][a-z0-9._-]{0,63}$' THEN
            RAISE EXCEPTION 'invalid roll property id %', property_key
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF jsonb_typeof(property_value) IS DISTINCT FROM 'number'
           OR property_value::TEXT !~ '^[0-9]+$' THEN
            RAISE EXCEPTION 'roll property % must be an integer basis-point value', property_key
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        numeric_value := property_value::TEXT::BIGINT;
        IF numeric_value < 0 OR numeric_value > 10000 THEN
            RAISE EXCEPTION 'roll property % is outside 0..10000 basis points', property_key
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS item_instances_validate_roll_state ON item_instances;
CREATE TRIGGER item_instances_validate_roll_state
BEFORE INSERT OR UPDATE OF roll_state
ON item_instances
FOR EACH ROW
EXECUTE FUNCTION validate_item_roll_state();

CREATE OR REPLACE FUNCTION reject_intrinsic_item_roll_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.roll_state IS DISTINCT FROM OLD.roll_state THEN
        RAISE EXCEPTION 'intrinsic item roll_state is immutable after creation %', OLD.item_instance_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER item_instances_freeze_roll_state
BEFORE UPDATE OF roll_state
ON item_instances
FOR EACH ROW
EXECUTE FUNCTION reject_intrinsic_item_roll_mutation();
