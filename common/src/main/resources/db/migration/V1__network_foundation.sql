CREATE TABLE players (
    player_id UUID PRIMARY KEY,
    minecraft_uuid UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE player_names (
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE CASCADE,
    name VARCHAR(16) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_id, name)
);

CREATE TABLE backends (
    backend_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    player_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT backends_status_check CHECK (status IN ('ONLINE', 'DRAINING', 'OFFLINE')),
    CONSTRAINT backends_player_count_check CHECK (player_count >= 0)
);

CREATE TABLE zone_instances (
    instance_id UUID PRIMARY KEY,
    zone_id TEXT NOT NULL,
    template_version TEXT NOT NULL,
    backend_id TEXT REFERENCES backends(backend_id),
    status TEXT NOT NULL,
    player_count INTEGER NOT NULL DEFAULT 0,
    soft_capacity INTEGER NOT NULL,
    hard_capacity INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT zone_instances_status_check CHECK (
        status IN ('STARTING', 'ACTIVE', 'DRAINING', 'STOPPED', 'FAILED')
    ),
    CONSTRAINT zone_instances_player_count_check CHECK (player_count >= 0),
    CONSTRAINT zone_instances_soft_capacity_check CHECK (soft_capacity >= 1),
    CONSTRAINT zone_instances_hard_capacity_check CHECK (hard_capacity >= soft_capacity)
);

CREATE INDEX zone_instances_routing_idx
    ON zone_instances(zone_id, status, player_count);

CREATE TABLE player_sessions (
    network_session_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE CASCADE,
    owner_backend_id TEXT,
    owner_instance_id UUID,
    state_version BIGINT NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    lease_expires_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    disconnected_at TIMESTAMPTZ,
    CONSTRAINT player_sessions_version_check CHECK (state_version >= 0),
    CONSTRAINT player_sessions_status_check CHECK (
        status IN ('ACTIVE', 'TRANSFERRING', 'DISCONNECTED', 'RECOVERING')
    )
);

CREATE UNIQUE INDEX player_sessions_one_live_session_idx
    ON player_sessions(player_id)
    WHERE status IN ('ACTIVE', 'TRANSFERRING', 'RECOVERING');

CREATE INDEX player_sessions_owner_backend_idx
    ON player_sessions(owner_backend_id)
    WHERE owner_backend_id IS NOT NULL;

CREATE TABLE player_state (
    player_id UUID PRIMARY KEY REFERENCES players(player_id) ON DELETE CASCADE,
    state_version BIGINT NOT NULL DEFAULT 0,
    logical_zone_id TEXT,
    entry_point TEXT,
    state_payload BYTEA,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT player_state_version_check CHECK (state_version >= 0)
);

CREATE TABLE processed_operations (
    operation_id UUID PRIMARY KEY,
    operation_type TEXT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    result JSONB
);

CREATE TABLE economic_ledger (
    ledger_entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id UUID NOT NULL,
    line_no SMALLINT NOT NULL,
    player_id UUID REFERENCES players(player_id),
    asset_type TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    amount BIGINT NOT NULL,
    direction TEXT NOT NULL,
    reason TEXT NOT NULL,
    related_entity_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT economic_ledger_amount_check CHECK (amount >= 0),
    CONSTRAINT economic_ledger_direction_check CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT economic_ledger_operation_line_unique UNIQUE (operation_id, line_no)
);

CREATE INDEX economic_ledger_player_time_idx
    ON economic_ledger(player_id, created_at DESC)
    WHERE player_id IS NOT NULL;
