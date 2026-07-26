-- Ranked queue intent is valid only while the player still owns a live persistent Paper session.
-- Explicit session closure removes waiting intent immediately; crash-expired leases are purged by matchmaking reads.

CREATE OR REPLACE FUNCTION clear_ranked_queue_for_closed_persistent_session()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'DISCONNECTED'
       OR NEW.owner_backend_id IS NULL
       OR NEW.lease_expires_at IS NULL THEN
        DELETE FROM ranked_matchmaking_queue
        WHERE player_id = NEW.player_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER player_sessions_clear_ranked_queue
AFTER UPDATE OF status, owner_backend_id, lease_expires_at
ON player_sessions
FOR EACH ROW
EXECUTE FUNCTION clear_ranked_queue_for_closed_persistent_session();
