-- A Paper process must be present before bootstrap zone rows can reference it, but it must not become routeable until
-- deployment-time catalogs, compatibility gates, and runtime composition have completed successfully.
ALTER TABLE backends
    DROP CONSTRAINT backends_status_check;

ALTER TABLE backends
    ADD CONSTRAINT backends_status_check
    CHECK (status IN ('STARTING', 'ONLINE', 'DRAINING', 'OFFLINE'));
