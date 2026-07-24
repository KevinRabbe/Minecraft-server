ALTER TABLE auction_listings
    DROP CONSTRAINT auction_listings_item_instance_id_key;

CREATE UNIQUE INDEX auction_listings_one_active_per_item_idx
    ON auction_listings(item_instance_id)
    WHERE status = 'ACTIVE';
