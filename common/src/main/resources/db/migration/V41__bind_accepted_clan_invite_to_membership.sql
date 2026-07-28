CREATE OR REPLACE FUNCTION validate_accepted_clan_invitation_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACCEPTED' AND NOT EXISTS (
        SELECT 1
        FROM clan_members
        WHERE clan_id = NEW.clan_id
          AND player_id = NEW.invited_player_id
    ) THEN
        RAISE EXCEPTION 'accepted clan invitation lacks matching membership %', NEW.invite_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER clan_invitations_require_membership_on_accept
AFTER INSERT OR UPDATE OF status
ON clan_invitations
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_accepted_clan_invitation_membership();
