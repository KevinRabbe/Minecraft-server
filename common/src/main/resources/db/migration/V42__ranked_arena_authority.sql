-- Ranked Arena is the isolated 1.8.9 competitive category.
-- Match runtime is disposable; PostgreSQL owns participant exclusivity, lifecycle, rating state, and immutable results.

ALTER TABLE ranked_matches
    ADD COLUMN ruleset_id TEXT NOT NULL DEFAULT 'arena.legacy_1_8_9',
    ADD COLUMN ruleset_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ranked_matches_ruleset_id_check CHECK (
        ruleset_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    ADD CONSTRAINT ranked_matches_ruleset_version_check CHECK (ruleset_version >= 1),
    ADD CONSTRAINT ranked_matches_state_version_check CHECK (state_version >= 0),
    ADD CONSTRAINT ranked_matches_lifecycle_shape_check CHECK (
        (
            status = 'CREATED'
            AND started_at IS NULL
            AND finished_at IS NULL
            AND winner_player_id IS NULL
            AND result_operation_id IS NULL
        )
        OR
        (
            status = 'ACTIVE'
            AND started_at IS NOT NULL
            AND finished_at IS NULL
            AND winner_player_id IS NULL
            AND result_operation_id IS NULL
        )
        OR
        (
            status = 'COMPLETED'
            AND started_at IS NOT NULL
            AND finished_at IS NOT NULL
            AND winner_player_id IS NOT NULL
            AND result_operation_id IS NOT NULL
        )
        OR
        (
            status = 'CANCELLED'
            AND finished_at IS NOT NULL
            AND winner_player_id IS NULL
            AND result_operation_id IS NULL
        )
    );

CREATE INDEX ranked_ratings_ladder_idx
    ON ranked_ratings(rating DESC, player_id ASC);

CREATE INDEX ranked_matches_player_a_history_idx
    ON ranked_matches(player_a_id, finished_at DESC, match_id)
    WHERE status = 'COMPLETED';

CREATE INDEX ranked_matches_player_b_history_idx
    ON ranked_matches(player_b_id, finished_at DESC, match_id)
    WHERE status = 'COMPLETED';

CREATE TABLE ranked_match_participants (
    match_id UUID NOT NULL REFERENCES ranked_matches(match_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    released_at TIMESTAMPTZ,
    PRIMARY KEY (match_id, player_id)
);

CREATE UNIQUE INDEX ranked_match_participants_one_live_match_idx
    ON ranked_match_participants(player_id)
    WHERE released_at IS NULL;

INSERT INTO ranked_match_participants(match_id, player_id, released_at)
SELECT match_id,
       player_a_id,
       CASE WHEN status IN ('COMPLETED', 'CANCELLED') THEN COALESCE(finished_at, NOW()) ELSE NULL END
FROM ranked_matches
ON CONFLICT DO NOTHING;

INSERT INTO ranked_match_participants(match_id, player_id, released_at)
SELECT match_id,
       player_b_id,
       CASE WHEN status IN ('COMPLETED', 'CANCELLED') THEN COALESCE(finished_at, NOW()) ELSE NULL END
FROM ranked_matches
ON CONFLICT DO NOTHING;

CREATE TABLE ranked_match_results (
    match_id UUID PRIMARY KEY REFERENCES ranked_matches(match_id) ON DELETE RESTRICT,
    operation_id UUID NOT NULL UNIQUE,
    winner_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    loser_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    player_a_rating_before INTEGER NOT NULL,
    player_a_rating_after INTEGER NOT NULL,
    player_b_rating_before INTEGER NOT NULL,
    player_b_rating_after INTEGER NOT NULL,
    ruleset_id TEXT NOT NULL,
    ruleset_version INTEGER NOT NULL,
    rating_policy_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ranked_match_results_players_distinct CHECK (winner_player_id <> loser_player_id),
    CONSTRAINT ranked_match_results_ratings_nonnegative CHECK (
        player_a_rating_before >= 0
        AND player_a_rating_after >= 0
        AND player_b_rating_before >= 0
        AND player_b_rating_after >= 0
    ),
    CONSTRAINT ranked_match_results_ruleset_id_check CHECK (
        ruleset_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT ranked_match_results_ruleset_version_check CHECK (ruleset_version >= 1),
    CONSTRAINT ranked_match_results_rating_policy_version_check CHECK (rating_policy_version >= 1)
);

CREATE OR REPLACE FUNCTION validate_ranked_match_participant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    match_row ranked_matches%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.match_id IS DISTINCT FROM OLD.match_id
           OR NEW.player_id IS DISTINCT FROM OLD.player_id THEN
            RAISE EXCEPTION 'ranked match participant identity is immutable'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF OLD.released_at IS NOT NULL AND NEW.released_at IS DISTINCT FROM OLD.released_at THEN
            RAISE EXCEPTION 'released ranked match participant is immutable'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF OLD.released_at IS NULL AND NEW.released_at IS NULL THEN
            RAISE EXCEPTION 'ranked match participant update must release the participant'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO match_row
    FROM ranked_matches
    WHERE match_id = NEW.match_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown ranked match %', NEW.match_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.player_id NOT IN (match_row.player_a_id, match_row.player_b_id) THEN
        RAISE EXCEPTION 'ranked participant % does not belong to match %', NEW.player_id, NEW.match_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF match_row.status IN ('COMPLETED', 'CANCELLED') AND NEW.released_at IS NULL THEN
        RAISE EXCEPTION 'terminal ranked match participant must already be released'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER ranked_match_participants_validate
BEFORE INSERT OR UPDATE
ON ranked_match_participants
FOR EACH ROW
EXECUTE FUNCTION validate_ranked_match_participant();

CREATE OR REPLACE FUNCTION validate_ranked_match_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.match_id IS DISTINCT FROM OLD.match_id
       OR NEW.player_a_id IS DISTINCT FROM OLD.player_a_id
       OR NEW.player_b_id IS DISTINCT FROM OLD.player_b_id
       OR NEW.ruleset_id IS DISTINCT FROM OLD.ruleset_id
       OR NEW.ruleset_version IS DISTINCT FROM OLD.ruleset_version
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'ranked match identity/ruleset is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('COMPLETED', 'CANCELLED') THEN
        RAISE EXCEPTION 'terminal ranked match is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.state_version <> OLD.state_version + 1 THEN
        RAISE EXCEPTION 'ranked match state_version must advance exactly once'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT (
        (OLD.status = 'CREATED' AND NEW.status IN ('ACTIVE', 'CANCELLED'))
        OR
        (OLD.status = 'ACTIVE' AND NEW.status IN ('COMPLETED', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'invalid ranked match transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER ranked_matches_validate_transition
BEFORE UPDATE
ON ranked_matches
FOR EACH ROW
EXECUTE FUNCTION validate_ranked_match_transition();

CREATE OR REPLACE FUNCTION release_ranked_match_participants()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IN ('COMPLETED', 'CANCELLED') THEN
        UPDATE ranked_match_participants
        SET released_at = COALESCE(NEW.finished_at, NOW())
        WHERE match_id = NEW.match_id
          AND released_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ranked_matches_release_participants
AFTER UPDATE OF status
ON ranked_matches
FOR EACH ROW
EXECUTE FUNCTION release_ranked_match_participants();

CREATE OR REPLACE FUNCTION validate_ranked_match_result_row()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    match_row ranked_matches%ROWTYPE;
BEGIN
    SELECT * INTO match_row
    FROM ranked_matches
    WHERE match_id = NEW.match_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown ranked match result target %', NEW.match_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF match_row.status <> 'COMPLETED'
       OR match_row.result_operation_id IS DISTINCT FROM NEW.operation_id
       OR match_row.winner_player_id IS DISTINCT FROM NEW.winner_player_id
       OR match_row.ruleset_id IS DISTINCT FROM NEW.ruleset_id
       OR match_row.ruleset_version IS DISTINCT FROM NEW.ruleset_version THEN
        RAISE EXCEPTION 'ranked result does not match authoritative completed match %', NEW.match_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT (
        NEW.winner_player_id IN (match_row.player_a_id, match_row.player_b_id)
        AND NEW.loser_player_id IN (match_row.player_a_id, match_row.player_b_id)
        AND NEW.winner_player_id <> NEW.loser_player_id
    ) THEN
        RAISE EXCEPTION 'ranked result participants do not match match %', NEW.match_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER ranked_match_results_validate
BEFORE INSERT
ON ranked_match_results
FOR EACH ROW
EXECUTE FUNCTION validate_ranked_match_result_row();

CREATE OR REPLACE FUNCTION reject_ranked_match_result_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ranked_match_results is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER ranked_match_results_append_only
BEFORE UPDATE OR DELETE
ON ranked_match_results
FOR EACH ROW
EXECUTE FUNCTION reject_ranked_match_result_mutation();

CREATE OR REPLACE FUNCTION validate_ranked_match_completion_result()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_match_id UUID;
    match_status TEXT;
    match_operation UUID;
BEGIN
    target_match_id := COALESCE(NEW.match_id, OLD.match_id);

    SELECT status, result_operation_id
    INTO match_status, match_operation
    FROM ranked_matches
    WHERE match_id = target_match_id;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF match_status = 'COMPLETED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM ranked_match_results
            WHERE match_id = target_match_id
              AND operation_id = match_operation
        ) THEN
            RAISE EXCEPTION 'completed ranked match % has no matching immutable result', target_match_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        IF EXISTS (
            SELECT 1
            FROM ranked_match_results
            WHERE match_id = target_match_id
        ) THEN
            RAISE EXCEPTION 'non-completed ranked match % cannot have a result', target_match_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ranked_matches_require_result
AFTER INSERT OR UPDATE
ON ranked_matches
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_ranked_match_completion_result();

CREATE CONSTRAINT TRIGGER ranked_results_require_completed_match
AFTER INSERT
ON ranked_match_results
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_ranked_match_completion_result();
