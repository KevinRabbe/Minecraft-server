CREATE TABLE clan_invitations (
    invite_id UUID PRIMARY KEY,
    clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE RESTRICT,
    invited_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    invited_by_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    CONSTRAINT clan_invitations_status_check CHECK (
        status IN ('PENDING', 'ACCEPTED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT clan_invitations_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT clan_invitations_shape_check CHECK (
        (status = 'PENDING' AND accepted_at IS NULL AND closed_at IS NULL)
        OR
        (status = 'ACCEPTED' AND accepted_at IS NOT NULL AND closed_at IS NOT NULL)
        OR
        (status IN ('CANCELLED', 'EXPIRED') AND accepted_at IS NULL AND closed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX clan_invitations_one_pending_idx
    ON clan_invitations(clan_id, invited_player_id)
    WHERE status = 'PENDING';

CREATE INDEX clan_invitations_player_pending_idx
    ON clan_invitations(invited_player_id, expires_at)
    WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION validate_clan_invitation_actor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    inviter_role TEXT;
BEGIN
    SELECT role INTO inviter_role
    FROM clan_members
    WHERE clan_id = NEW.clan_id
      AND player_id = NEW.invited_by_player_id;

    IF inviter_role NOT IN ('LEADER', 'OFFICER') THEN
        RAISE EXCEPTION 'clan invitation requires LEADER or OFFICER membership %', NEW.invited_by_player_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF EXISTS (
        SELECT 1 FROM clan_members WHERE player_id = NEW.invited_player_id
    ) THEN
        RAISE EXCEPTION 'invited player already belongs to a clan %', NEW.invited_player_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_invitations_validate_actor
BEFORE INSERT
ON clan_invitations
FOR EACH ROW
EXECUTE FUNCTION validate_clan_invitation_actor();

CREATE OR REPLACE FUNCTION validate_clan_invitation_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.clan_id IS DISTINCT FROM OLD.clan_id
       OR NEW.invited_player_id IS DISTINCT FROM OLD.invited_player_id
       OR NEW.invited_by_player_id IS DISTINCT FROM OLD.invited_by_player_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'clan invitation identity is immutable %', OLD.invite_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status <> 'PENDING' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal clan invitation is immutable %', OLD.invite_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;

    IF OLD.status = 'PENDING' AND NEW.status IN ('ACCEPTED', 'CANCELLED', 'EXPIRED') THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid clan invitation transition % -> % for %',
        OLD.status, NEW.status, OLD.invite_id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER clan_invitations_validate_transition
BEFORE UPDATE
ON clan_invitations
FOR EACH ROW
EXECUTE FUNCTION validate_clan_invitation_transition();

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
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE CONSTRAINT TRIGGER clan_members_preserve_leader
AFTER UPDATE OF role OR DELETE
ON clan_members
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION reject_clan_member_orphan_leader();
