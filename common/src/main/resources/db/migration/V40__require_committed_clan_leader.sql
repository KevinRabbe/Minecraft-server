CREATE OR REPLACE FUNCTION validate_clan_has_leader()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_clan_id UUID;
BEGIN
    target_clan_id := NEW.clan_id;

    IF NOT EXISTS (
        SELECT 1
        FROM clan_members
        WHERE clan_id = target_clan_id
          AND role = 'LEADER'
    ) THEN
        RAISE EXCEPTION 'clan must have exactly one committed leader %', target_clan_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER clans_require_leader
AFTER INSERT
ON clans
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_clan_has_leader();

CREATE OR REPLACE FUNCTION freeze_clan_creator_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.created_by_player_id IS DISTINCT FROM OLD.created_by_player_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'clan creator identity is immutable %', OLD.clan_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clans_freeze_creator_identity
BEFORE UPDATE
ON clans
FOR EACH ROW
EXECUTE FUNCTION freeze_clan_creator_identity();
