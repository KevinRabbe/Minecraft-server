CREATE TABLE bounty_summons (
    summon_id UUID PRIMARY KEY,
    contract_id UUID NOT NULL UNIQUE REFERENCES bounty_contracts(contract_id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'READY',
    owner_backend_id TEXT,
    lease_expires_at TIMESTAMPTZ,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT bounty_summons_status_check CHECK (status IN ('READY', 'ACTIVE', 'DEFEATED', 'FAILED')),
    CONSTRAINT bounty_summons_version_check CHECK (state_version >= 0),
    CONSTRAINT bounty_summons_state_shape_check CHECK (
        (
            status = 'READY'
            AND owner_backend_id IS NULL
            AND lease_expires_at IS NULL
            AND activated_at IS NULL
            AND resolved_at IS NULL
        )
        OR
        (
            status = 'ACTIVE'
            AND owner_backend_id IS NOT NULL
            AND BTRIM(owner_backend_id) <> ''
            AND lease_expires_at IS NOT NULL
            AND activated_at IS NOT NULL
            AND resolved_at IS NULL
        )
        OR
        (
            status IN ('DEFEATED', 'FAILED')
            AND owner_backend_id IS NOT NULL
            AND BTRIM(owner_backend_id) <> ''
            AND lease_expires_at IS NULL
            AND activated_at IS NOT NULL
            AND resolved_at IS NOT NULL
        )
    )
);

CREATE INDEX bounty_summons_active_lease_idx
    ON bounty_summons(lease_expires_at)
    WHERE status = 'ACTIVE';
