-- Clan Wars are isolated 1.8.9 competitive matches. Persistent MMO value may enter only through WAR_CUSTODY.

ALTER TABLE clan_wars
    ADD COLUMN ruleset_id TEXT NOT NULL DEFAULT 'war.legacy_1_8_9',
    ADD COLUMN ruleset_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN rating_policy_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN rating_k_factor INTEGER NOT NULL DEFAULT 32,
    ADD COLUMN team_size INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN resolution_operation_id UUID UNIQUE,
    ADD COLUMN challenged_by_player_id UUID REFERENCES players(player_id) ON DELETE RESTRICT,
    ADD COLUMN accepted_by_player_id UUID REFERENCES players(player_id) ON DELETE RESTRICT,
    ADD CONSTRAINT clan_wars_ruleset_id_check CHECK (ruleset_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    ADD CONSTRAINT clan_wars_ruleset_version_check CHECK (ruleset_version >= 1),
    ADD CONSTRAINT clan_wars_rating_policy_version_check CHECK (rating_policy_version >= 1),
    ADD CONSTRAINT clan_wars_rating_k_factor_check CHECK (rating_k_factor BETWEEN 1 AND 10000),
    ADD CONSTRAINT clan_wars_team_size_check CHECK (team_size BETWEEN 1 AND 100),
    ADD CONSTRAINT clan_wars_resolution_shape_check CHECK (
        (status IN ('COMPLETED', 'CANCELLED', 'FAILED') AND resolution_operation_id IS NOT NULL AND finished_at IS NOT NULL)
        OR
        (status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED') AND resolution_operation_id IS NULL AND finished_at IS NULL)
    ),
    ADD CONSTRAINT clan_wars_completion_resolution_match_check CHECK (
        status <> 'COMPLETED' OR settlement_operation_id = resolution_operation_id
    );

ALTER TABLE clan_war_rosters
    ADD COLUMN released_at TIMESTAMPTZ;

UPDATE clan_war_rosters r
SET released_at = COALESCE(w.finished_at, NOW())
FROM clan_wars w
WHERE w.war_id = r.war_id
  AND w.status IN ('COMPLETED', 'CANCELLED', 'FAILED')
  AND r.released_at IS NULL;

CREATE UNIQUE INDEX clan_war_rosters_one_live_war_per_player_idx
    ON clan_war_rosters(player_id)
    WHERE released_at IS NULL;

CREATE TABLE clan_war_ratings (
    clan_id UUID PRIMARY KEY REFERENCES clans(clan_id) ON DELETE CASCADE,
    rating INTEGER NOT NULL DEFAULT 1000,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT clan_war_ratings_rating_check CHECK (rating >= 0),
    CONSTRAINT clan_war_ratings_version_check CHECK (state_version >= 0)
);

CREATE INDEX clan_war_ratings_ladder_idx
    ON clan_war_ratings(rating DESC, clan_id ASC);

CREATE TABLE clan_war_results (
    war_id UUID PRIMARY KEY REFERENCES clan_wars(war_id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL UNIQUE,
    winning_clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE RESTRICT,
    losing_clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE RESTRICT,
    challenger_rating_before INTEGER NOT NULL,
    challenger_rating_after INTEGER NOT NULL,
    defender_rating_before INTEGER NOT NULL,
    defender_rating_after INTEGER NOT NULL,
    ruleset_id TEXT NOT NULL,
    ruleset_version INTEGER NOT NULL,
    rating_policy_version INTEGER NOT NULL,
    rating_k_factor INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT clan_war_results_clans_distinct CHECK (winning_clan_id <> losing_clan_id),
    CONSTRAINT clan_war_results_ratings_nonnegative CHECK (
        challenger_rating_before >= 0
        AND challenger_rating_after >= 0
        AND defender_rating_before >= 0
        AND defender_rating_after >= 0
    ),
    CONSTRAINT clan_war_results_ruleset_id_check CHECK (ruleset_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT clan_war_results_ruleset_version_check CHECK (ruleset_version >= 1),
    CONSTRAINT clan_war_results_rating_policy_version_check CHECK (rating_policy_version >= 1),
    CONSTRAINT clan_war_results_rating_k_factor_check CHECK (rating_k_factor BETWEEN 1 AND 10000)
);

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

    IF NEW.status = 'ACCEPTED' AND NEW.accepted_by_player_id IS NULL THEN
        RAISE EXCEPTION 'accepted clan war requires accepting player'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.accepted_by_player_id IS NOT NULL
       AND NEW.accepted_by_player_id IS DISTINCT FROM OLD.accepted_by_player_id THEN
        RAISE EXCEPTION 'clan war accepting player is immutable once set'
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

DROP TRIGGER IF EXISTS clan_wars_validate_transition ON clan_wars;
CREATE TRIGGER clan_wars_validate_transition
BEFORE UPDATE
ON clan_wars
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_transition();

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
    target_war_id := COALESCE(NEW.war_id, OLD.war_id);
    target_clan_id := COALESCE(NEW.clan_id, OLD.clan_id);
    target_player_id := COALESCE(NEW.player_id, OLD.player_id);

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

DROP TRIGGER IF EXISTS clan_war_rosters_validate ON clan_war_rosters;
CREATE TRIGGER clan_war_rosters_validate
BEFORE INSERT OR UPDATE OR DELETE
ON clan_war_rosters
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_roster();

CREATE OR REPLACE FUNCTION release_clan_war_rosters()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IN ('COMPLETED', 'CANCELLED', 'FAILED') THEN
        UPDATE clan_war_rosters
        SET released_at = COALESCE(NEW.finished_at, NOW())
        WHERE war_id = NEW.war_id AND released_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_wars_release_rosters
AFTER UPDATE OF status
ON clan_wars
FOR EACH ROW
EXECUTE FUNCTION release_clan_war_rosters();

CREATE OR REPLACE FUNCTION require_clan_war_item_entry_window()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    war_status TEXT;
BEGIN
    SELECT status INTO war_status FROM clan_wars WHERE war_id = NEW.war_id;
    IF war_status IS DISTINCT FROM 'ROSTER_LOCKED' THEN
        RAISE EXCEPTION 'war items may enter custody only while roster is locked'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.released_at IS NOT NULL THEN
        RAISE EXCEPTION 'new war custody row cannot already be released'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_war_items_entry_window
BEFORE INSERT
ON clan_war_items
FOR EACH ROW
EXECUTE FUNCTION require_clan_war_item_entry_window();

CREATE OR REPLACE FUNCTION validate_clan_war_item_custody()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    item_kind TEXT;
    item_location_id UUID;
    item_version BIGINT;
    war_status TEXT;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM clan_war_rosters
        WHERE war_id = NEW.war_id
          AND player_id = NEW.player_id
    ) THEN
        RAISE EXCEPTION 'war item player % is not locked into war roster %', NEW.player_id, NEW.war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT status INTO war_status FROM clan_wars WHERE war_id = NEW.war_id;
    SELECT location_kind, location_id, state_version
    INTO item_kind, item_location_id, item_version
    FROM item_instances
    WHERE item_instance_id = NEW.item_instance_id;

    IF NEW.released_at IS NULL THEN
        IF war_status NOT IN ('ROSTER_LOCKED', 'ACTIVE') THEN
            RAISE EXCEPTION 'active war item cannot exist for war status %', war_status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF item_kind IS DISTINCT FROM 'WAR_CUSTODY'
           OR item_location_id IS DISTINCT FROM NEW.war_id
           OR item_version IS DISTINCT FROM NEW.entry_item_version THEN
            RAISE EXCEPTION 'active war item % does not match authoritative WAR_CUSTODY/version for war %',
                NEW.item_instance_id, NEW.war_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        IF war_status NOT IN ('COMPLETED', 'CANCELLED', 'FAILED') THEN
            RAISE EXCEPTION 'released war item requires terminal war status'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF item_kind = 'WAR_CUSTODY' AND item_location_id = NEW.war_id THEN
            RAISE EXCEPTION 'released war item % still remains in WAR_CUSTODY for war %',
                NEW.item_instance_id, NEW.war_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION validate_clan_war_terminal_custody()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IN ('COMPLETED', 'CANCELLED', 'FAILED')
       AND EXISTS (
            SELECT 1 FROM clan_war_items
            WHERE war_id = NEW.war_id AND released_at IS NULL
       ) THEN
        RAISE EXCEPTION 'terminal clan war % still has unreleased WAR_CUSTODY items', NEW.war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER clan_wars_require_released_custody
AFTER UPDATE
ON clan_wars
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_terminal_custody();

CREATE OR REPLACE FUNCTION validate_clan_war_result_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    war_row clan_wars%ROWTYPE;
BEGIN
    SELECT * INTO war_row FROM clan_wars WHERE war_id = NEW.war_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown clan war result target %', NEW.war_id USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF war_row.status <> 'COMPLETED'
       OR war_row.settlement_operation_id IS DISTINCT FROM NEW.operation_id
       OR war_row.resolution_operation_id IS DISTINCT FROM NEW.operation_id
       OR war_row.winning_clan_id IS DISTINCT FROM NEW.winning_clan_id
       OR war_row.ruleset_id IS DISTINCT FROM NEW.ruleset_id
       OR war_row.ruleset_version IS DISTINCT FROM NEW.ruleset_version
       OR war_row.rating_policy_version IS DISTINCT FROM NEW.rating_policy_version
       OR war_row.rating_k_factor IS DISTINCT FROM NEW.rating_k_factor THEN
        RAISE EXCEPTION 'clan war result does not match authoritative completed war %', NEW.war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT (
        NEW.winning_clan_id IN (war_row.challenger_clan_id, war_row.defender_clan_id)
        AND NEW.losing_clan_id IN (war_row.challenger_clan_id, war_row.defender_clan_id)
        AND NEW.winning_clan_id <> NEW.losing_clan_id
    ) THEN
        RAISE EXCEPTION 'clan war result clans do not match war %', NEW.war_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_war_results_validate
BEFORE INSERT
ON clan_war_results
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_result_row();

CREATE OR REPLACE FUNCTION reject_clan_war_result_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'clan_war_results is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER clan_war_results_append_only
BEFORE UPDATE OR DELETE
ON clan_war_results
FOR EACH ROW
EXECUTE FUNCTION reject_clan_war_result_mutation();

CREATE OR REPLACE FUNCTION validate_clan_war_completion_result()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_war_id UUID;
    war_status TEXT;
    settlement_operation UUID;
BEGIN
    target_war_id := COALESCE(NEW.war_id, OLD.war_id);
    SELECT status, settlement_operation_id
    INTO war_status, settlement_operation
    FROM clan_wars
    WHERE war_id = target_war_id;

    IF NOT FOUND THEN RETURN NULL; END IF;

    IF war_status = 'COMPLETED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM clan_war_results
            WHERE war_id = target_war_id AND operation_id = settlement_operation
        ) THEN
            RAISE EXCEPTION 'completed clan war % has no matching immutable result', target_war_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM clan_war_results WHERE war_id = target_war_id) THEN
            RAISE EXCEPTION 'non-completed clan war % cannot have a result', target_war_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER clan_wars_require_result
AFTER INSERT OR UPDATE
ON clan_wars
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_completion_result();

CREATE CONSTRAINT TRIGGER clan_war_results_require_completed_war
AFTER INSERT
ON clan_war_results
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_clan_war_completion_result();
