CREATE OR REPLACE FUNCTION validate_artifact_definition_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
       OR NEW.definition_operation_id IS DISTINCT FROM OLD.definition_operation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'artifact identity/creation evidence is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.point_value IS DISTINCT FROM OLD.point_value
       OR NEW.point_policy_version IS DISTINCT FROM OLD.point_policy_version THEN
        IF NEW.point_policy_version <> OLD.point_policy_version + 1 THEN
            RAISE EXCEPTION 'artifact point-policy changes must increment point_policy_version exactly once'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER artifact_definitions_validate_update
BEFORE UPDATE
ON artifact_definitions
FOR EACH ROW
EXECUTE FUNCTION validate_artifact_definition_update();

CREATE OR REPLACE FUNCTION validate_artifact_location_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    latest_revision BIGINT;
BEGIN
    SELECT MAX(location_revision)
    INTO latest_revision
    FROM artifact_locations
    WHERE artifact_id = NEW.artifact_id;

    IF latest_revision IS NULL THEN
        IF NEW.location_revision <> 1 THEN
            RAISE EXCEPTION 'first artifact location revision must be 1'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.location_revision <> latest_revision + 1 THEN
        RAISE EXCEPTION 'artifact location revision must advance exactly once from %', latest_revision
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER artifact_locations_validate_insert
BEFORE INSERT
ON artifact_locations
FOR EACH ROW
EXECUTE FUNCTION validate_artifact_location_insert();
