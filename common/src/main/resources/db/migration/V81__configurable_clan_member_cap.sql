-- Clan size is balance/tuning data, not an architecture constant. The bundled 100-member value is provisional V1 tuning.
CREATE TABLE clan_policy (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
    member_cap INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT clan_policy_singleton_check CHECK (singleton),
    CONSTRAINT clan_policy_member_cap_check CHECK (member_cap BETWEEN 1 AND 10000)
);

INSERT INTO clan_policy(singleton, member_cap)
VALUES (TRUE, 100);

CREATE OR REPLACE FUNCTION touch_clan_policy_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_policy_touch_update
BEFORE UPDATE
ON clan_policy
FOR EACH ROW
EXECUTE FUNCTION touch_clan_policy_update();

CREATE OR REPLACE FUNCTION enforce_clan_member_cap()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    configured_cap INTEGER;
    current_members INTEGER;
BEGIN
    -- Serialize membership insertion for one clan even when multiple Paper backends accept invites concurrently.
    PERFORM 1
    FROM clans
    WHERE clan_id = NEW.clan_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'cannot enforce member cap for missing clan %', NEW.clan_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    -- Share-lock the policy row so a trusted tuning update cannot change the cap halfway through this insertion.
    SELECT member_cap
    INTO configured_cap
    FROM clan_policy
    WHERE singleton = TRUE
    FOR SHARE;

    IF configured_cap IS NULL THEN
        RAISE EXCEPTION 'clan member policy is missing'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT COUNT(*)::INTEGER
    INTO current_members
    FROM clan_members
    WHERE clan_id = NEW.clan_id;

    IF current_members >= configured_cap THEN
        RAISE EXCEPTION 'clan member cap % reached for clan %', configured_cap, NEW.clan_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_members_enforce_member_cap
BEFORE INSERT
ON clan_members
FOR EACH ROW
EXECUTE FUNCTION enforce_clan_member_cap();
