CREATE TABLE map_open_player_state_evidence (
    open_operation_id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES map_runs(run_id) ON DELETE RESTRICT,
    session_id UUID NOT NULL,
    backend_id TEXT NOT NULL,
    expected_player_state_version BIGINT NOT NULL,
    player_state_version BIGINT NOT NULL,
    logical_zone_id TEXT,
    entry_point TEXT,
    payload_sha256 TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT map_open_player_state_expected_version_check CHECK (expected_player_state_version >= 0),
    CONSTRAINT map_open_player_state_result_version_check CHECK (
        player_state_version = expected_player_state_version + 1
    ),
    CONSTRAINT map_open_player_state_backend_check CHECK (BTRIM(backend_id) <> ''),
    CONSTRAINT map_open_player_state_payload_hash_check CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE OR REPLACE FUNCTION reject_map_open_player_state_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'map_open_player_state_evidence is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER map_open_player_state_evidence_append_only
BEFORE UPDATE OR DELETE
ON map_open_player_state_evidence
FOR EACH ROW
EXECUTE FUNCTION reject_map_open_player_state_evidence_mutation();
