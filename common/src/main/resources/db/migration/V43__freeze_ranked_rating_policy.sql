ALTER TABLE ranked_matches
    ADD COLUMN rating_policy_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN rating_k_factor INTEGER NOT NULL DEFAULT 32,
    ADD CONSTRAINT ranked_matches_rating_policy_version_check CHECK (rating_policy_version >= 1),
    ADD CONSTRAINT ranked_matches_rating_k_factor_check CHECK (rating_k_factor BETWEEN 1 AND 10000);

ALTER TABLE ranked_match_results
    ADD COLUMN rating_k_factor INTEGER NOT NULL DEFAULT 32,
    ADD CONSTRAINT ranked_match_results_rating_k_factor_check CHECK (rating_k_factor BETWEEN 1 AND 10000);

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
       OR NEW.rating_policy_version IS DISTINCT FROM OLD.rating_policy_version
       OR NEW.rating_k_factor IS DISTINCT FROM OLD.rating_k_factor
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'ranked match identity/ruleset/rating policy is immutable'
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
       OR match_row.ruleset_version IS DISTINCT FROM NEW.ruleset_version
       OR match_row.rating_policy_version IS DISTINCT FROM NEW.rating_policy_version
       OR match_row.rating_k_factor IS DISTINCT FROM NEW.rating_k_factor THEN
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
