CREATE OR REPLACE FUNCTION validate_expansion_candidate_version()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_vote_id UUID;
    target_candidate_set_version INTEGER;
    vote_version INTEGER;
    vote_status TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_vote_id := OLD.vote_id;
        target_candidate_set_version := OLD.candidate_set_version;
    ELSE
        target_vote_id := NEW.vote_id;
        target_candidate_set_version := NEW.candidate_set_version;
    END IF;

    SELECT candidate_set_version, status
    INTO vote_version, vote_status
    FROM expansion_votes
    WHERE vote_id = target_vote_id;

    IF vote_version IS DISTINCT FROM target_candidate_set_version THEN
        RAISE EXCEPTION 'candidate set version does not match vote %', target_vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF vote_status IS DISTINCT FROM 'SCHEDULED' THEN
        RAISE EXCEPTION 'candidate set is immutable once vote leaves SCHEDULED state %', target_vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

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

    IF OLD.status = 'SCHEDULED' AND NEW.status NOT IN ('SCHEDULED', 'OPEN', 'CANCELLED') THEN
        RAISE EXCEPTION 'invalid expansion vote transition SCHEDULED -> % for %', NEW.status, NEW.vote_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'OPEN' AND NEW.status NOT IN ('OPEN', 'RESOLVED', 'CANCELLED') THEN
        RAISE EXCEPTION 'invalid expansion vote transition OPEN -> % for %', NEW.status, NEW.vote_id
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
