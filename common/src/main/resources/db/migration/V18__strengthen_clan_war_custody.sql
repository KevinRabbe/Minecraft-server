ALTER TABLE ranked_matches
    ADD CONSTRAINT ranked_matches_winner_status_check CHECK (
        status = 'COMPLETED' OR winner_player_id IS NULL
    );

ALTER TABLE clan_wars
    ADD CONSTRAINT clan_wars_winner_status_check CHECK (
        status = 'COMPLETED' OR winning_clan_id IS NULL
    );

CREATE OR REPLACE FUNCTION validate_clan_war_roster()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    challenger UUID;
    defender UUID;
BEGIN
    SELECT challenger_clan_id, defender_clan_id
    INTO challenger, defender
    FROM clan_wars
    WHERE war_id = NEW.war_id;

    IF NEW.clan_id NOT IN (challenger, defender) THEN
        RAISE EXCEPTION 'war roster clan is not a participant in war %', NEW.war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM clan_members
        WHERE clan_id = NEW.clan_id
          AND player_id = NEW.player_id
    ) THEN
        RAISE EXCEPTION 'war roster player % is not a member of clan %', NEW.player_id, NEW.clan_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_war_rosters_validate
BEFORE INSERT OR UPDATE
ON clan_war_rosters
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_roster();

CREATE OR REPLACE FUNCTION validate_clan_war_item_custody()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
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

    IF NOT EXISTS (
        SELECT 1
        FROM item_instances
        WHERE item_instance_id = NEW.item_instance_id
          AND location_kind = 'WAR_CUSTODY'
          AND location_id = NEW.war_id
    ) THEN
        RAISE EXCEPTION 'war item % is not in authoritative WAR_CUSTODY for war %', NEW.item_instance_id, NEW.war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER clan_war_items_require_custody
AFTER INSERT OR UPDATE
ON clan_war_items
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_item_custody();

CREATE OR REPLACE FUNCTION validate_item_war_custody_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.location_kind = 'WAR_CUSTODY'
       AND NOT EXISTS (
           SELECT 1
           FROM clan_war_items
           WHERE item_instance_id = NEW.item_instance_id
             AND war_id = NEW.location_id
             AND released_at IS NULL
       ) THEN
        RAISE EXCEPTION 'WAR_CUSTODY item % has no active clan_war_items row', NEW.item_instance_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER item_instances_require_war_custody_row
AFTER INSERT OR UPDATE OF location_kind, location_id
ON item_instances
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_item_war_custody_row();
