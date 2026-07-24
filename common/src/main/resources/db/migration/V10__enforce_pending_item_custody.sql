CREATE OR REPLACE FUNCTION validate_item_pending_delivery_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    delivery_recipient UUID;
    delivery_status TEXT;
    delivery_item UUID;
BEGIN
    IF NEW.location_kind = 'PENDING_DELIVERY' THEN
        SELECT recipient_player_id, status, item_instance_id
        INTO delivery_recipient, delivery_status, delivery_item
        FROM pending_unique_deliveries
        WHERE delivery_id = NEW.location_id;

        IF delivery_item IS NULL
           OR delivery_item <> NEW.item_instance_id
           OR delivery_status <> 'PENDING' THEN
            RAISE EXCEPTION 'item % cannot enter pending delivery % without matching PENDING custody',
                NEW.item_instance_id,
                NEW.location_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.location_kind = 'PENDING_DELIVERY'
       AND (
           NEW.location_kind IS DISTINCT FROM OLD.location_kind
           OR NEW.location_id IS DISTINCT FROM OLD.location_id
       ) THEN
        SELECT recipient_player_id, status, item_instance_id
        INTO delivery_recipient, delivery_status, delivery_item
        FROM pending_unique_deliveries
        WHERE delivery_id = OLD.location_id;

        IF delivery_item IS NULL
           OR delivery_item <> NEW.item_instance_id
           OR delivery_status <> 'CLAIMED'
           OR NEW.location_kind <> 'PLAYER_INVENTORY'
           OR NEW.location_id IS DISTINCT FROM delivery_recipient THEN
            RAISE EXCEPTION 'item % cannot leave pending delivery % except through matching CLAIMED recipient custody',
                NEW.item_instance_id,
                OLD.location_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER item_instances_enforce_pending_delivery_transition
AFTER INSERT OR UPDATE OF location_kind, location_id
ON item_instances
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_item_pending_delivery_transition();
