-- Upgrade state is mutable investment on an existing unique item. Intrinsic roll quality remains immutable.
ALTER TABLE item_provenance
    DROP CONSTRAINT item_provenance_event_type_check;

ALTER TABLE item_provenance
    ADD CONSTRAINT item_provenance_event_type_check CHECK (
        event_type IN ('CREATED', 'MOVED', 'DELIVERED', 'QUARANTINED', 'DESTROYED', 'RECOVERED', 'UPGRADED')
    );

-- Deliberately no FK edges from this append-only evidence table back to item_instances/players. Existing integration
-- fixtures truncate those authority roots directly. Equivalent existence checks are enforced by the insert trigger,
-- while retaining historical UUID evidence independently of lifecycle/fixture cleanup.
CREATE TABLE item_upgrade_events (
    upgrade_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_instance_id UUID NOT NULL,
    operation_id UUID NOT NULL UNIQUE,
    from_state_version BIGINT NOT NULL,
    to_state_version BIGINT NOT NULL,
    from_upgrade_level INTEGER NOT NULL,
    to_upgrade_level INTEGER NOT NULL,
    reason TEXT NOT NULL,
    actor_player_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT item_upgrade_events_state_versions_check CHECK (
        from_state_version >= 0
        AND to_state_version = from_state_version + 1
    ),
    CONSTRAINT item_upgrade_events_levels_check CHECK (
        from_upgrade_level BETWEEN 0 AND 10000
        AND to_upgrade_level = from_upgrade_level + 1
    ),
    CONSTRAINT item_upgrade_events_reason_check CHECK (
        reason ~ '^[a-z0-9][a-z0-9._-]{0,95}$'
    ),
    CONSTRAINT item_upgrade_events_item_version_unique UNIQUE (item_instance_id, to_state_version)
);

CREATE INDEX item_upgrade_events_item_history_idx
    ON item_upgrade_events(item_instance_id, to_state_version, upgrade_event_id);

CREATE OR REPLACE FUNCTION reject_item_upgrade_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'item_upgrade_events is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER item_upgrade_events_append_only
BEFORE UPDATE OR DELETE
ON item_upgrade_events
FOR EACH ROW
EXECUTE FUNCTION reject_item_upgrade_event_mutation();

CREATE OR REPLACE FUNCTION validate_item_upgrade_state_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.upgrade_level IS DISTINCT FROM OLD.upgrade_level THEN
        IF NEW.upgrade_level <> OLD.upgrade_level + 1 THEN
            RAISE EXCEPTION 'item upgrade_level must advance exactly one level for %', OLD.item_instance_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.state_version <> OLD.state_version + 1 THEN
            RAISE EXCEPTION 'item upgrade must advance state_version exactly once for %', OLD.item_instance_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.location_kind IS DISTINCT FROM OLD.location_kind
           OR NEW.location_id IS DISTINCT FROM OLD.location_id THEN
            RAISE EXCEPTION 'item upgrade cannot change custody for %', OLD.item_instance_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER item_instances_validate_upgrade_state_transition
BEFORE UPDATE OF upgrade_level
ON item_instances
FOR EACH ROW
EXECUTE FUNCTION validate_item_upgrade_state_transition();

CREATE OR REPLACE FUNCTION validate_item_upgrade_event_head()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_state_version BIGINT;
    current_upgrade_level INTEGER;
BEGIN
    SELECT state_version, upgrade_level
    INTO current_state_version, current_upgrade_level
    FROM item_instances
    WHERE item_instance_id = NEW.item_instance_id;

    IF current_state_version IS NULL THEN
        RAISE EXCEPTION 'upgrade event references missing item %', NEW.item_instance_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.actor_player_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.actor_player_id) THEN
        RAISE EXCEPTION 'upgrade event references missing actor player %', NEW.actor_player_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF current_state_version <> NEW.to_state_version
       OR current_upgrade_level <> NEW.to_upgrade_level THEN
        RAISE EXCEPTION 'upgrade event does not match current authority head for %', NEW.item_instance_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER item_upgrade_events_validate_head
BEFORE INSERT
ON item_upgrade_events
FOR EACH ROW
EXECUTE FUNCTION validate_item_upgrade_event_head();

CREATE OR REPLACE FUNCTION require_item_upgrade_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.upgrade_level IS DISTINCT FROM OLD.upgrade_level
       AND NOT EXISTS (
            SELECT 1
            FROM item_upgrade_events event
            WHERE event.item_instance_id = NEW.item_instance_id
              AND event.from_state_version = OLD.state_version
              AND event.to_state_version = NEW.state_version
              AND event.from_upgrade_level = OLD.upgrade_level
              AND event.to_upgrade_level = NEW.upgrade_level
       ) THEN
        RAISE EXCEPTION 'item upgrade authority head % version % has no matching upgrade event',
            NEW.item_instance_id,
            NEW.state_version
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER item_instances_require_upgrade_event
AFTER UPDATE OF upgrade_level
ON item_instances
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION require_item_upgrade_event();
