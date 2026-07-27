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

-- Keep an explicit per-clan reservation counter so the member-cap invariant does not depend on statement-snapshot
-- visibility of concurrent clan_members inserts. The counter is authority-supporting state, not a player-facing total.
CREATE TABLE clan_member_counts (
    clan_id UUID PRIMARY KEY REFERENCES clans(clan_id) ON DELETE CASCADE,
    member_count INTEGER NOT NULL,
    CONSTRAINT clan_member_counts_nonnegative_check CHECK (member_count >= 0)
);

INSERT INTO clan_member_counts(clan_id, member_count)
SELECT clan_id, COUNT(*)::INTEGER
FROM clan_members
GROUP BY clan_id;

CREATE OR REPLACE FUNCTION reserve_clan_member_slot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    configured_cap INTEGER;
    reserved_count INTEGER;
BEGIN
    -- Stabilize the shared tuning value for this reservation. Trusted policy updates wait until this transaction ends.
    SELECT member_cap
    INTO configured_cap
    FROM clan_policy
    WHERE singleton = TRUE
    FOR SHARE;

    IF configured_cap IS NULL THEN
        RAISE EXCEPTION 'clan member policy is missing'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Lazily create the counter for clans created after this migration. The clans FK still validates the clan identity.
    INSERT INTO clan_member_counts(clan_id, member_count)
    VALUES (NEW.clan_id, 0)
    ON CONFLICT (clan_id) DO NOTHING;

    -- PostgreSQL row locking makes this conditional increment an atomic slot reservation even for concurrent raw INSERTs.
    UPDATE clan_member_counts
    SET member_count = member_count + 1
    WHERE clan_id = NEW.clan_id
      AND member_count < configured_cap
    RETURNING member_count INTO reserved_count;

    IF reserved_count IS NULL THEN
        RAISE EXCEPTION 'clan member cap % reached for clan %', configured_cap, NEW.clan_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_members_reserve_member_slot
BEFORE INSERT
ON clan_members
FOR EACH ROW
EXECUTE FUNCTION reserve_clan_member_slot();

CREATE OR REPLACE FUNCTION release_clan_member_slot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    remaining_count INTEGER;
BEGIN
    UPDATE clan_member_counts
    SET member_count = member_count - 1
    WHERE clan_id = OLD.clan_id
      AND member_count > 0
    RETURNING member_count INTO remaining_count;

    IF remaining_count IS NULL THEN
        -- During a parent-clan DELETE, PostgreSQL may cascade-delete the counter row before the membership row. That is
        -- a valid terminal path. A missing/zero counter while the clan still exists is an invariant failure.
        IF EXISTS (SELECT 1 FROM clans WHERE clan_id = OLD.clan_id) THEN
            RAISE EXCEPTION 'clan member counter underflow for clan %', OLD.clan_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN OLD;
END;
$$;

CREATE TRIGGER clan_members_release_member_slot
AFTER DELETE
ON clan_members
FOR EACH ROW
EXECUTE FUNCTION release_clan_member_slot();

CREATE OR REPLACE FUNCTION reject_clan_member_identity_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.clan_id IS DISTINCT FROM OLD.clan_id
       OR NEW.player_id IS DISTINCT FROM OLD.player_id THEN
        RAISE EXCEPTION 'clan member identity is immutable for player % in clan %', OLD.player_id, OLD.clan_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_members_reject_identity_update
BEFORE UPDATE OF clan_id, player_id
ON clan_members
FOR EACH ROW
EXECUTE FUNCTION reject_clan_member_identity_update();
