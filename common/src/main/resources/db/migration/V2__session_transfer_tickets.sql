CREATE TABLE transfer_tickets (
    transfer_id UUID PRIMARY KEY,
    network_session_id UUID NOT NULL REFERENCES player_sessions(network_session_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE CASCADE,
    source_backend_id TEXT NOT NULL,
    target_zone_id TEXT NOT NULL,
    expected_state_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT transfer_tickets_version_check CHECK (expected_state_version >= 0)
);

CREATE UNIQUE INDEX transfer_tickets_one_open_per_session_idx
    ON transfer_tickets(network_session_id)
    WHERE consumed_at IS NULL;

CREATE INDEX transfer_tickets_expiry_idx
    ON transfer_tickets(expires_at)
    WHERE consumed_at IS NULL;
