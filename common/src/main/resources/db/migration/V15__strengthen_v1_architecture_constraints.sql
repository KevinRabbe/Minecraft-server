CREATE OR REPLACE FUNCTION validate_map_clear_source()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    run_status TEXT;
    run_difficulty INTEGER;
    run_world_era_id TEXT;
    run_balance_version INTEGER;
    participant_count BIGINT;
BEGIN
    SELECT status, difficulty, world_era_id, balance_version
    INTO run_status, run_difficulty, run_world_era_id, run_balance_version
    FROM map_runs
    WHERE run_id = NEW.run_id;

    IF run_status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION 'map clear requires COMPLETED run %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.difficulty <> run_difficulty
       OR NEW.world_era_id IS DISTINCT FROM run_world_era_id
       OR NEW.balance_version <> run_balance_version THEN
        RAISE EXCEPTION 'map clear does not match authoritative run definition %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT COUNT(*)
    INTO participant_count
    FROM map_run_participants
    WHERE run_id = NEW.run_id;

    IF participant_count = 0 THEN
        RAISE EXCEPTION 'map clear requires at least one participant for run %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.solo IS DISTINCT FROM (participant_count = 1) THEN
        RAISE EXCEPTION 'map clear solo flag does not match participant count for run %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_clears_validate_source
BEFORE INSERT
ON map_clears
FOR EACH ROW
EXECUTE FUNCTION validate_map_clear_source();

CREATE OR REPLACE FUNCTION reject_world_era_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'world_eras is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER world_eras_append_only
BEFORE UPDATE OR DELETE
ON world_eras
FOR EACH ROW
EXECUTE FUNCTION reject_world_era_mutation();

CREATE OR REPLACE FUNCTION validate_expansion_candidate_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    vote_version INTEGER;
    vote_status TEXT;
BEGIN
    SELECT candidate_set_version, status
    INTO vote_version, vote_status
    FROM expansion_votes
    WHERE vote_id = NEW.vote_id;

    IF vote_version IS DISTINCT FROM NEW.candidate_set_version THEN
        RAISE EXCEPTION 'candidate set version does not match vote %', NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF vote_status NOT IN ('SCHEDULED') THEN
        RAISE EXCEPTION 'candidate set is immutable once vote leaves SCHEDULED state %', NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER expansion_vote_candidates_validate_version
BEFORE INSERT OR UPDATE OR DELETE
ON expansion_vote_candidates
FOR EACH ROW
EXECUTE FUNCTION validate_expansion_candidate_version();

CREATE OR REPLACE FUNCTION validate_expansion_vote_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    candidate_count BIGINT;
BEGIN
    IF NEW.candidate_set_version <> OLD.candidate_set_version THEN
        RAISE EXCEPTION 'candidate_set_version is immutable after vote creation %', NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('RESOLVED', 'CANCELLED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'terminal expansion vote cannot transition %', NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status IN ('OPEN', 'RESOLVED') THEN
        SELECT COUNT(*)
        INTO candidate_count
        FROM expansion_vote_candidates
        WHERE vote_id = NEW.vote_id
          AND candidate_set_version = NEW.candidate_set_version;

        IF candidate_count < 2 THEN
            RAISE EXCEPTION 'expansion vote requires at least two candidates before opening/resolution %', NEW.vote_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER expansion_votes_validate_transition
BEFORE UPDATE
ON expansion_votes
FOR EACH ROW
EXECUTE FUNCTION validate_expansion_vote_transition();

CREATE OR REPLACE FUNCTION validate_expansion_ballot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    vote_status TEXT;
    vote_version INTEGER;
    vote_opens_at TIMESTAMPTZ;
    vote_closes_at TIMESTAMPTZ;
BEGIN
    SELECT status, candidate_set_version, opens_at, closes_at
    INTO vote_status, vote_version, vote_opens_at, vote_closes_at
    FROM expansion_votes
    WHERE vote_id = NEW.vote_id;

    IF vote_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'ballot requires OPEN vote %', NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF vote_version IS DISTINCT FROM NEW.candidate_set_version THEN
        RAISE EXCEPTION 'ballot candidate set version does not match vote %', NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.cast_at < vote_opens_at OR NEW.cast_at >= vote_closes_at THEN
        RAISE EXCEPTION 'ballot timestamp is outside vote window %', NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER expansion_ballots_validate_vote
BEFORE INSERT OR UPDATE
ON expansion_ballots
FOR EACH ROW
EXECUTE FUNCTION validate_expansion_ballot();
