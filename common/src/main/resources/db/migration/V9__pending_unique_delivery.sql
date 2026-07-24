ALTER TABLE item_instances
    DROP CONSTRAINT item_instances_location_kind_check,
    DROP CONSTRAINT item_instances_location_shape_check;

ALTER TABLE item_instances
    ADD CONSTRAINT item_instances_location_kind_check CHECK (
        location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY', 'QUARANTINE', 'DESTROYED')
    ),
    ADD CONSTRAINT item_instances_location_shape_check CHECK (
        (location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY') AND location_id IS NOT NULL)
        OR
        (location_kind IN ('QUARANTINE', 'DESTROYED') AND location_id IS NULL)
    );

ALTER TABLE item_provenance
    DROP CONSTRAINT item_provenance_event_type_check,
    DROP CONSTRAINT item_provenance_to_location_kind_check,
    DROP CONSTRAINT item_provenance_to_location_shape_check,
    DROP CONSTRAINT item_provenance_from_location_kind_check,
    DROP CONSTRAINT item_provenance_from_location_shape_check;

ALTER TABLE item_provenance
    ADD CONSTRAINT item_provenance_event_type_check CHECK (
        event_type IN ('CREATED', 'MOVED', 'DELIVERED', 'QUARANTINED', 'DESTROYED', 'RECOVERED')
    ),
    ADD CONSTRAINT item_provenance_to_location_kind_check CHECK (
        to_location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY', 'QUARANTINE', 'DESTROYED')
    ),
    ADD CONSTRAINT item_provenance_to_location_shape_check CHECK (
        (to_location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY') AND to_location_id IS NOT NULL)
        OR
        (to_location_kind IN ('QUARANTINE', 'DESTROYED') AND to_location_id IS NULL)
    ),
    ADD CONSTRAINT item_provenance_from_location_kind_check CHECK (
        from_location_kind IS NULL
        OR from_location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY', 'QUARANTINE', 'DESTROYED')
    ),
    ADD CONSTRAINT item_provenance_from_location_shape_check CHECK (
        (from_location_kind IS NULL AND from_location_id IS NULL)
        OR
        (from_location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY') AND from_location_id IS NOT NULL)
        OR
        (from_location_kind IN ('QUARANTINE', 'DESTROYED') AND from_location_id IS NULL)
    );

CREATE TABLE pending_unique_deliveries (
    delivery_id UUID PRIMARY KEY,
    recipient_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    item_instance_id UUID NOT NULL UNIQUE
        REFERENCES item_instances(item_instance_id) DEFERRABLE INITIALLY DEFERRED,
    status TEXT NOT NULL DEFAULT 'PENDING',
    issue_operation_id UUID NOT NULL UNIQUE,
    claim_operation_id UUID UNIQUE,
    issue_reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    CONSTRAINT pending_unique_deliveries_status_check CHECK (status IN ('PENDING', 'CLAIMED')),
    CONSTRAINT pending_unique_deliveries_issue_reason_not_blank CHECK (BTRIM(issue_reason) <> ''),
    CONSTRAINT pending_unique_deliveries_claim_shape_check CHECK (
        (status = 'PENDING' AND claim_operation_id IS NULL AND claimed_at IS NULL)
        OR
        (status = 'CLAIMED' AND claim_operation_id IS NOT NULL AND claimed_at IS NOT NULL)
    )
);

CREATE INDEX pending_unique_deliveries_recipient_pending_idx
    ON pending_unique_deliveries(recipient_player_id, created_at, delivery_id)
    WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION validate_item_instance_player_location()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.location_id) THEN
        RAISE EXCEPTION 'PLAYER_INVENTORY location references unknown player_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.location_kind = 'PENDING_DELIVERY'
       AND NOT EXISTS (SELECT 1 FROM pending_unique_deliveries WHERE delivery_id = NEW.location_id) THEN
        RAISE EXCEPTION 'PENDING_DELIVERY location references unknown delivery_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_item_provenance_player_locations()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.from_location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.from_location_id) THEN
        RAISE EXCEPTION 'item provenance from-location references unknown player_id %', NEW.from_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.to_location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.to_location_id) THEN
        RAISE EXCEPTION 'item provenance to-location references unknown player_id %', NEW.to_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.from_location_kind = 'PENDING_DELIVERY'
       AND NOT EXISTS (SELECT 1 FROM pending_unique_deliveries WHERE delivery_id = NEW.from_location_id) THEN
        RAISE EXCEPTION 'item provenance from-location references unknown delivery_id %', NEW.from_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.to_location_kind = 'PENDING_DELIVERY'
       AND NOT EXISTS (SELECT 1 FROM pending_unique_deliveries WHERE delivery_id = NEW.to_location_id) THEN
        RAISE EXCEPTION 'item provenance to-location references unknown delivery_id %', NEW.to_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_pending_unique_delivery_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.claim_operation_id IS NOT NULL OR NEW.claimed_at IS NOT NULL THEN
            RAISE EXCEPTION 'new pending delivery must start PENDING and unclaimed'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.recipient_player_id IS DISTINCT FROM OLD.recipient_player_id
       OR NEW.item_instance_id IS DISTINCT FROM OLD.item_instance_id
       OR NEW.issue_operation_id IS DISTINCT FROM OLD.issue_operation_id
       OR NEW.issue_reason IS DISTINCT FROM OLD.issue_reason
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'pending delivery issuance fields are immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status <> 'PENDING'
       OR NEW.status <> 'CLAIMED'
       OR NEW.claim_operation_id IS NULL
       OR NEW.claimed_at IS NULL THEN
        RAISE EXCEPTION 'pending delivery permits only PENDING -> CLAIMED transition'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER pending_unique_deliveries_validate_transition
BEFORE INSERT OR UPDATE
ON pending_unique_deliveries
FOR EACH ROW
EXECUTE FUNCTION validate_pending_unique_delivery_transition();

CREATE OR REPLACE FUNCTION validate_pending_unique_delivery_authority()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'PENDING' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM item_instances i
            WHERE i.item_instance_id = NEW.item_instance_id
              AND i.location_kind = 'PENDING_DELIVERY'
              AND i.location_id = NEW.delivery_id
        ) THEN
            RAISE EXCEPTION 'pending delivery % is not authoritative custody for item %',
                NEW.delivery_id,
                NEW.item_instance_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF NEW.status = 'CLAIMED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM item_instances i
            WHERE i.item_instance_id = NEW.item_instance_id
              AND i.location_kind = 'PLAYER_INVENTORY'
              AND i.location_id = NEW.recipient_player_id
        ) THEN
            RAISE EXCEPTION 'claimed delivery % item % is not in recipient inventory authority',
                NEW.delivery_id,
                NEW.item_instance_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER pending_unique_deliveries_require_authority
AFTER INSERT OR UPDATE
ON pending_unique_deliveries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_pending_unique_delivery_authority();
