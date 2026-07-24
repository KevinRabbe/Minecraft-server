CREATE TABLE progression_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
    active_skill_cap INTEGER NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    source_operation_id UUID,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT progression_state_singleton CHECK (singleton),
    CONSTRAINT progression_state_cap_check CHECK (active_skill_cap IN (50, 75, 100)),
    CONSTRAINT progression_state_version_check CHECK (state_version >= 0),
    CONSTRAINT progression_state_source_check CHECK (
        active_skill_cap = 50 OR source_operation_id IS NOT NULL
    )
);

INSERT INTO progression_state(singleton, active_skill_cap)
VALUES (TRUE, 50)
ON CONFLICT (singleton) DO NOTHING;

CREATE TABLE skill_xp_awards (
    operation_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    skill_id TEXT NOT NULL,
    requested_experience BIGINT NOT NULL,
    granted_experience BIGINT NOT NULL,
    previous_experience BIGINT NOT NULL,
    new_experience BIGINT NOT NULL,
    active_skill_cap INTEGER NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT skill_xp_awards_skill_id_check CHECK (skill_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT skill_xp_awards_requested_check CHECK (requested_experience > 0),
    CONSTRAINT skill_xp_awards_granted_check CHECK (
        granted_experience >= 0 AND granted_experience <= requested_experience
    ),
    CONSTRAINT skill_xp_awards_previous_check CHECK (previous_experience >= 0),
    CONSTRAINT skill_xp_awards_new_check CHECK (
        new_experience = previous_experience + granted_experience
    ),
    CONSTRAINT skill_xp_awards_cap_check CHECK (active_skill_cap IN (50, 75, 100)),
    CONSTRAINT skill_xp_awards_reason_check CHECK (reason ~ '^[a-z0-9][a-z0-9._-]{0,95}$')
);

CREATE OR REPLACE FUNCTION reject_skill_xp_award_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'skill_xp_awards is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER skill_xp_awards_append_only
BEFORE UPDATE OR DELETE
ON skill_xp_awards
FOR EACH ROW
EXECUTE FUNCTION reject_skill_xp_award_mutation();
