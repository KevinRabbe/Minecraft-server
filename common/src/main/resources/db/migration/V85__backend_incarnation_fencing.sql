-- Stable backend IDs are reused across restarts. A per-registration incarnation token prevents an older process
-- from heartbeating, draining, or marking offline after a replacement process has claimed the same backend ID.
ALTER TABLE backends
    ADD COLUMN incarnation_id UUID NOT NULL DEFAULT gen_random_uuid();
