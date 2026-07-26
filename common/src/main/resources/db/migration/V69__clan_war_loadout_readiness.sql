-- Roster lock freezes who may participate, but does not mean those players have finished selecting WAR_CUSTODY state.
-- Each live roster player must explicitly finalize their selected loadout before the war becomes dispatchable.

CREATE TABLE clan_war_loadout_confirmations (
    war_id UUID NOT NULL,
    player_id UUID NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (war_id, player_id),
    CONSTRAINT clan_war_loadout_confirmation_roster_fk
        FOREIGN KEY (war_id, player_id)
        REFERENCES clan_war_rosters(war_id, player_id)
        ON DELETE CASCADE
);

-- Any later custody mutation invalidates the player's previous confirmation. This keeps retries/late deposits from
-- silently changing the frozen combat snapshot after a player declared their selection final.
CREATE OR REPLACE FUNCTION invalidate_clan_war_loadout_confirmation_on_item_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_war_id UUID;
    target_player_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_war_id := OLD.war_id;
        target_player_id := OLD.player_id;
    ELSE
        target_war_id := NEW.war_id;
        target_player_id := NEW.player_id;
    END IF;

    DELETE FROM clan_war_loadout_confirmations
    WHERE war_id = target_war_id
      AND player_id = target_player_id;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_war_items_invalidate_loadout_confirmation
AFTER INSERT OR UPDATE OR DELETE
ON clan_war_items
FOR EACH ROW
EXECUTE FUNCTION invalidate_clan_war_loadout_confirmation_on_item_change();

CREATE OR REPLACE FUNCTION clear_clan_war_loadout_confirmation_on_roster_release()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.released_at IS NOT NULL AND OLD.released_at IS NULL THEN
        DELETE FROM clan_war_loadout_confirmations
        WHERE war_id = NEW.war_id
          AND player_id = NEW.player_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_war_roster_release_clears_loadout_confirmation
AFTER UPDATE OF released_at
ON clan_war_rosters
FOR EACH ROW
EXECUTE FUNCTION clear_clan_war_loadout_confirmation_on_roster_release();
