ALTER TABLE pending_unique_deliveries
    DROP CONSTRAINT pending_unique_deliveries_item_instance_id_key;

CREATE UNIQUE INDEX pending_unique_deliveries_one_pending_per_item_idx
    ON pending_unique_deliveries(item_instance_id)
    WHERE status = 'PENDING';
