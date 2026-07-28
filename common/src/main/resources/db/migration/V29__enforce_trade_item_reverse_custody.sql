CREATE OR REPLACE FUNCTION validate_trade_item_authority_head()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    trade_status TEXT;
BEGIN
    IF NEW.location_kind = 'TRADE_ESCROW' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM secure_trade_unique_items s
            WHERE s.trade_id = NEW.location_id
              AND s.item_instance_id = NEW.item_instance_id
              AND s.escrow_item_version = NEW.state_version
        ) THEN
            RAISE EXCEPTION 'TRADE_ESCROW item has no matching secure-trade escrow row %', NEW.item_instance_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    IF OLD.location_kind = 'TRADE_ESCROW'
       AND NEW.location_kind <> 'TRADE_ESCROW' THEN
        SELECT status INTO trade_status
        FROM secure_trades
        WHERE trade_id = OLD.location_id;

        IF trade_status NOT IN ('SETTLED', 'CANCELLED')
           AND EXISTS (
               SELECT 1
               FROM secure_trade_unique_items s
               WHERE s.trade_id = OLD.location_id
                 AND s.item_instance_id = OLD.item_instance_id
           ) THEN
            RAISE EXCEPTION 'active secure-trade custody cannot release item %', OLD.item_instance_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER item_instances_require_trade_custody_head
AFTER UPDATE OF location_kind, location_id, state_version
ON item_instances
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_trade_item_authority_head();
