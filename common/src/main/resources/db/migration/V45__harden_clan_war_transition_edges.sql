CREATE OR REPLACE FUNCTION validate_clan_war_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    challenger_count INTEGER;
    defender_count INTEGER;
BEGIN
    IF NEW.war_id IS DISTINCT FROM OLD.war_id
       OR NEW.challenger_clan_id IS DISTINCT FROM OLD.challenger_clan_id
       OR NEW.defender_clan_id IS DISTINCT FROM OLD.defender_clan_id
       OR NEW.ruleset_id IS DISTINCT FROM OLD.ruleset_id
       OR NEW.ruleset_version IS DISTINCT FROM OLD.ruleset_version
       OR NEW.rating_policy_version IS DISTINCT FROM OLD.rating_policy_version
       OR NEW.rating_k_factor IS DISTINCT FROM OLD.rating_k_factor
       OR NEW.team_size IS DISTINCT FROM OLD.team_size
       OR NEW.challenged_by_player_id IS DISTINCT FROM OLD.challenged_by_player_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'clan war identity/ruleset is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('COMPLETED', 'CANCELLED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal clan war is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.state_version <> OLD.state_version + 1 THEN
        RAISE EXCEPTION 'clan war state_version must advance exactly once'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT (
        (OLD.status = 'CHALLENGED' AND NEW.status IN ('ACCEPTED', 'CANCELLED'))
        OR (OLD.status = 'ACCEPTED' AND NEW.status IN ('ROSTER_LOCKED', 'CANCELLED'))
        OR (OLD.status = 'ROSTER_LOCKED' AND NEW.status IN ('ACTIVE', 'CANCELLED', 'FAILED'))
        OR (OLD.status = 'ACTIVE' AND NEW.status IN ('COMPLETED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'invalid clan war transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.accepted_by_player_id IS DISTINCT FROM OLD.accepted_by_player_id THEN
        IF NOT (
            OLD.status = 'CHALLENGED'
            AND NEW.status = 'ACCEPTED'
            AND OLD.accepted_by_player_id IS NULL
            AND NEW.accepted_by_player_id IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'accepting player may only be set by CHALLENGED -> ACCEPTED'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    IF NEW.status = 'ACCEPTED' AND NEW.accepted_by_player_id IS NULL THEN
        RAISE EXCEPTION 'accepted clan war requires accepting player'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status = 'ROSTER_LOCKED' THEN
        SELECT COUNT(*) INTO challenger_count
        FROM clan_war_rosters
        WHERE war_id = NEW.war_id
          AND clan_id = NEW.challenger_clan_id
          AND released_at IS NULL;

        SELECT COUNT(*) INTO defender_count
        FROM clan_war_rosters
        WHERE war_id = NEW.war_id
          AND clan_id = NEW.defender_clan_id
          AND released_at IS NULL;

        IF challenger_count <> NEW.team_size OR defender_count <> NEW.team_size THEN
            RAISE EXCEPTION 'clan war % requires exactly % live roster players per clan', NEW.war_id, NEW.team_size
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM clan_war_rosters r
            LEFT JOIN clan_members m
              ON m.clan_id = r.clan_id AND m.player_id = r.player_id
            WHERE r.war_id = NEW.war_id
              AND r.released_at IS NULL
              AND m.player_id IS NULL
        ) THEN
            RAISE EXCEPTION 'clan war % roster contains player who left the clan before lock', NEW.war_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_clan_war_roster()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_war_id UUID;
    target_clan_id UUID;
    target_player_id UUID;
    war_status TEXT;
    challenger UUID;
    defender UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_war_id := OLD.war_id;
        target_clan_id := OLD.clan_id;
        target_player_id := OLD.player_id;
    ELSE
        target_war_id := NEW.war_id;
        target_clan_id := NEW.clan_id;
        target_player_id := NEW.player_id;
    END IF;

    SELECT status, challenger_clan_id, defender_clan_id
    INTO war_status, challenger, defender
    FROM clan_wars
    WHERE war_id = target_war_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown clan war %', target_war_id USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.war_id IS DISTINCT FROM OLD.war_id
           OR NEW.clan_id IS DISTINCT FROM OLD.clan_id
           OR NEW.player_id IS DISTINCT FROM OLD.player_id
           OR NEW.locked_at IS DISTINCT FROM OLD.locked_at THEN
            RAISE EXCEPTION 'clan war roster identity is immutable'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF OLD.released_at IS NOT NULL AND NEW.released_at IS DISTINCT FROM OLD.released_at THEN
            RAISE EXCEPTION 'released clan war roster row is immutable'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF OLD.released_at IS NULL AND NEW.released_at IS NULL THEN
            RAISE EXCEPTION 'clan war roster update must release the player'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF war_status <> 'ACCEPTED' OR OLD.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'clan war roster may be removed only while ACCEPTED'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN OLD;
    END IF;

    IF war_status <> 'ACCEPTED' THEN
        RAISE EXCEPTION 'clan war roster may be edited only while ACCEPTED'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF target_clan_id NOT IN (challenger, defender) THEN
        RAISE EXCEPTION 'war roster clan is not a participant in war %', target_war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM clan_members
        WHERE clan_id = target_clan_id AND player_id = target_player_id
    ) THEN
        RAISE EXCEPTION 'war roster player % is not a member of clan %', target_player_id, target_clan_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;
