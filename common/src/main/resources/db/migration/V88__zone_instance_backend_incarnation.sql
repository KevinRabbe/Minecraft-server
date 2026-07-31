-- A zone instance belongs to one concrete backend process incarnation, not merely to a reusable backend name.
-- The insert trigger preserves trusted direct SQL fixtures while the Java registry supplies the captured token explicitly.
ALTER TABLE zone_instances
    ADD COLUMN backend_incarnation_id UUID;

UPDATE zone_instances zone_instance
SET backend_incarnation_id = backend.incarnation_id
FROM backends backend
WHERE backend.backend_id = zone_instance.backend_id;

CREATE FUNCTION fill_zone_instance_backend_incarnation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.backend_incarnation_id IS NULL THEN
        SELECT backend.incarnation_id
        INTO NEW.backend_incarnation_id
        FROM backends backend
        WHERE backend.backend_id = NEW.backend_id;
    END IF;

    IF NEW.backend_incarnation_id IS NULL THEN
        RAISE EXCEPTION 'zone instance backend incarnation is unavailable for backend %', NEW.backend_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER zone_instances_fill_backend_incarnation
BEFORE INSERT ON zone_instances
FOR EACH ROW
EXECUTE FUNCTION fill_zone_instance_backend_incarnation();

ALTER TABLE zone_instances
    ALTER COLUMN backend_incarnation_id SET NOT NULL;

CREATE FUNCTION retire_previous_backend_zone_instances()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.incarnation_id IS DISTINCT FROM NEW.incarnation_id THEN
        UPDATE zone_instances
        SET status = 'STOPPED',
            player_count = 0,
            last_heartbeat_at = NOW()
        WHERE backend_id = NEW.backend_id
          AND backend_incarnation_id = OLD.incarnation_id
          AND status <> 'STOPPED';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER backends_retire_previous_zone_instances
AFTER UPDATE OF incarnation_id ON backends
FOR EACH ROW
EXECUTE FUNCTION retire_previous_backend_zone_instances();

CREATE INDEX zone_instances_backend_incarnation_idx
    ON zone_instances(backend_id, backend_incarnation_id, status, last_heartbeat_at);
