-- SECURITY DEFINER functions default to PUBLIC EXECUTE unless explicitly revoked.
-- The isolated 1.8 runtime contract is narrower: deployment grants EXECUTE only to the dedicated runtime login whose
-- SESSION_USER is mapped in competitive_runtime_principals. Persistent application/database roles retain owner/admin
-- authority as appropriate; arbitrary database logins must not be able to invoke the privileged runtime surface.

REVOKE EXECUTE ON FUNCTION require_competitive_runtime_backend() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_heartbeat(INTEGER) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_mark_offline() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_poll_active(INTEGER) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_heartbeat_execution(UUID, BIGINT, INTEGER) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_submit_report(UUID, UUID, TEXT, UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_find_player_execution(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION competitive_runtime_page_loadout(UUID, INTEGER, INTEGER, INTEGER) FROM PUBLIC;

-- Future functions created by the same migration owner must also start closed. This affects only subsequently created
-- functions owned by this role; an intended public function must opt back in explicitly.
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
