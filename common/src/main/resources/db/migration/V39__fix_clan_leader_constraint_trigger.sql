CREATE OR REPLACE FUNCTION reject_clan_member_orphan_leader()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.role = 'LEADER' THEN
        IF TG_OP = 'DELETE' THEN
            RAISE EXCEPTION 'clan leader cannot leave/remove without leadership transfer %', OLD.clan_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.role <> 'LEADER' AND NOT EXISTS (
            SELECT 1
            FROM clan_members
            WHERE clan_id = OLD.clan_id
              AND player_id <> OLD.player_id
              AND role = 'LEADER'
        ) THEN
            RAISE EXCEPTION 'clan cannot lose its only leader %', OLD.clan_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
