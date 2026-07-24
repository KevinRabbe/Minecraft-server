ALTER TABLE transfer_tickets
    ADD COLUMN target_backend_id TEXT REFERENCES backends(backend_id),
    ADD COLUMN target_instance_id UUID REFERENCES zone_instances(instance_id),
    ADD COLUMN routed_at TIMESTAMPTZ,
    ADD CONSTRAINT transfer_tickets_route_pair_check CHECK (
        (target_backend_id IS NULL AND target_instance_id IS NULL AND routed_at IS NULL)
        OR
        (target_backend_id IS NOT NULL AND target_instance_id IS NOT NULL AND routed_at IS NOT NULL)
    );

CREATE INDEX transfer_tickets_player_open_idx
    ON transfer_tickets(player_id, expires_at)
    WHERE consumed_at IS NULL;
