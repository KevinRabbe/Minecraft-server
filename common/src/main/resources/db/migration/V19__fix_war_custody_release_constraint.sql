CREATE OR REPLACE FUNCTION validate_clan_war_item_custody()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    item_kind TEXT;
    item_location_id UUID;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM clan_war_rosters
        WHERE war_id = NEW.war_id
          AND player_id = NEW.player_id
    ) THEN
        RAISE EXCEPTION 'war item player % is not locked into war roster %', NEW.player_id, NEW.war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT location_kind, location_id
    INTO item_kind, item_location_id
    FROM item_instances
    WHERE item_instance_id = NEW.item_instance_id;

    IF NEW.released_at IS NULL THEN
        IF item_kind IS DISTINCT FROM 'WAR_CUSTODY' OR item_location_id IS DISTINCT FROM NEW.war_id THEN
            RAISE EXCEPTION 'active war item % is not in authoritative WAR_CUSTODY for war %',
                NEW.item_instance_id,
                NEW.war_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        IF item_kind = 'WAR_CUSTODY' AND item_location_id = NEW.war_id THEN
            RAISE EXCEPTION 'released war item % still remains in WAR_CUSTODY for war %',
                NEW.item_instance_id,
                NEW.war_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;
