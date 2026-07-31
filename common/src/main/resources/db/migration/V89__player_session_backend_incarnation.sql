-- A live player session belongs to one concrete backend process incarnation. Reusing a stable backend ID must not
-- let an older JVM renew, checkpoint, transfer, or disconnect sessions after a replacement process takes ownership.
ALTER TABLE player_sessions
    ADD COLUMN owner_backend_incarnation_id UUID;

UPDATE player_sessions session
SET owner_backend_incarnation_id = backend.incarnation_id
FROM backends backend
WHERE backend.backend_id = session.owner_backend_id
  AND session.owner_backend_id IS NOT NULL;

CREATE FUNCTION fill_player_session_backend_incarnation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner_backend_id IS NULL THEN
        NEW.owner_backend_incarnation_id := NULL;
        RETURN NEW;
    END IF;

    IF NEW.owner_backend_incarnation_id IS NULL THEN
        SELECT backend.incarnation_id
        INTO NEW.owner_backend_incarnation_id
        FROM backends backend
        WHERE backend.backend_id = NEW.owner_backend_id;
    END IF;

    IF NEW.owner_backend_incarnation_id IS NULL THEN
        RAISE EXCEPTION 'player session backend incarnation is unavailable for backend %', NEW.owner_backend_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER player_sessions_fill_backend_incarnation
BEFORE INSERT OR UPDATE OF owner_backend_id, owner_backend_incarnation_id ON player_sessions
FOR EACH ROW
EXECUTE FUNCTION fill_player_session_backend_incarnation();

ALTER TABLE player_sessions
    ADD CONSTRAINT player_sessions_backend_incarnation_shape_check
    CHECK ((owner_backend_id IS NULL) = (owner_backend_incarnation_id IS NULL));

CREATE FUNCTION expire_previous_backend_player_sessions()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.incarnation_id IS DISTINCT FROM NEW.incarnation_id THEN
        UPDATE player_sessions
        SET lease_expires_at = NOW(),
            last_heartbeat_at = NOW()
        WHERE owner_backend_id = NEW.backend_id
          AND owner_backend_incarnation_id = OLD.incarnation_id
          AND status IN ('ACTIVE', 'RECOVERING')
          AND lease_expires_at > NOW();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER backends_expire_previous_player_sessions
AFTER UPDATE OF incarnation_id ON backends
FOR EACH ROW
EXECUTE FUNCTION expire_previous_backend_player_sessions();

CREATE INDEX player_sessions_backend_incarnation_idx
    ON player_sessions(owner_backend_id, owner_backend_incarnation_id, status, lease_expires_at)
    WHERE owner_backend_id IS NOT NULL;
