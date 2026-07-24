ALTER TABLE item_instances
    DROP CONSTRAINT item_instances_location_kind_check,
    DROP CONSTRAINT item_instances_location_shape_check;

ALTER TABLE item_instances
    ADD CONSTRAINT item_instances_location_kind_check CHECK (
        location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_instances_location_shape_check CHECK (
        (location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY', 'AUCTION_ESCROW') AND location_id IS NOT NULL)
        OR
        (location_kind IN ('QUARANTINE', 'DESTROYED') AND location_id IS NULL)
    );

ALTER TABLE item_provenance
    DROP CONSTRAINT item_provenance_to_location_kind_check,
    DROP CONSTRAINT item_provenance_to_location_shape_check,
    DROP CONSTRAINT item_provenance_from_location_kind_check,
    DROP CONSTRAINT item_provenance_from_location_shape_check;

ALTER TABLE item_provenance
    ADD CONSTRAINT item_provenance_to_location_kind_check CHECK (
        to_location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_provenance_to_location_shape_check CHECK (
        (to_location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY', 'AUCTION_ESCROW') AND to_location_id IS NOT NULL)
        OR
        (to_location_kind IN ('QUARANTINE', 'DESTROYED') AND to_location_id IS NULL)
    ),
    ADD CONSTRAINT item_provenance_from_location_kind_check CHECK (
        from_location_kind IS NULL
        OR from_location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_provenance_from_location_shape_check CHECK (
        (from_location_kind IS NULL AND from_location_id IS NULL)
        OR
        (from_location_kind IN ('PLAYER_INVENTORY', 'PENDING_DELIVERY', 'AUCTION_ESCROW') AND from_location_id IS NOT NULL)
        OR
        (from_location_kind IN ('QUARANTINE', 'DESTROYED') AND from_location_id IS NULL)
    );

CREATE TABLE auction_listings (
    listing_id UUID PRIMARY KEY,
    seller_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    item_instance_id UUID NOT NULL UNIQUE REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    escrow_item_version BIGINT NOT NULL,
    price_minor BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    create_operation_id UUID NOT NULL UNIQUE,
    settle_operation_id UUID UNIQUE,
    buyer_player_id UUID REFERENCES players(player_id) ON DELETE RESTRICT,
    settlement_delivery_id UUID UNIQUE
        REFERENCES pending_unique_deliveries(delivery_id) DEFERRABLE INITIALLY DEFERRED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ,
    CONSTRAINT auction_listings_item_version_check CHECK (escrow_item_version >= 0),
    CONSTRAINT auction_listings_price_check CHECK (price_minor > 0),
    CONSTRAINT auction_listings_status_check CHECK (status IN ('ACTIVE', 'SOLD', 'CANCELLED')),
    CONSTRAINT auction_listings_settlement_shape_check CHECK (
        (
            status = 'ACTIVE'
            AND settle_operation_id IS NULL
            AND buyer_player_id IS NULL
            AND settlement_delivery_id IS NULL
            AND settled_at IS NULL
        )
        OR
        (
            status = 'SOLD'
            AND settle_operation_id IS NOT NULL
            AND buyer_player_id IS NOT NULL
            AND buyer_player_id <> seller_player_id
            AND settlement_delivery_id IS NOT NULL
            AND settled_at IS NOT NULL
        )
        OR
        (
            status = 'CANCELLED'
            AND settle_operation_id IS NOT NULL
            AND buyer_player_id IS NULL
            AND settlement_delivery_id IS NOT NULL
            AND settled_at IS NOT NULL
        )
    )
);

CREATE INDEX auction_listings_active_created_idx
    ON auction_listings(created_at, listing_id)
    WHERE status = 'ACTIVE';

CREATE INDEX auction_listings_seller_idx
    ON auction_listings(seller_player_id, created_at DESC, listing_id);

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

    IF NEW.location_kind = 'AUCTION_ESCROW'
       AND NOT EXISTS (SELECT 1 FROM auction_listings WHERE listing_id = NEW.location_id) THEN
        RAISE EXCEPTION 'AUCTION_ESCROW location references unknown listing_id %', NEW.location_id
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

    IF NEW.from_location_kind = 'AUCTION_ESCROW'
       AND NOT EXISTS (SELECT 1 FROM auction_listings WHERE listing_id = NEW.from_location_id) THEN
        RAISE EXCEPTION 'item provenance from-location references unknown listing_id %', NEW.from_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.to_location_kind = 'AUCTION_ESCROW'
       AND NOT EXISTS (SELECT 1 FROM auction_listings WHERE listing_id = NEW.to_location_id) THEN
        RAISE EXCEPTION 'item provenance to-location references unknown listing_id %', NEW.to_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_auction_listing_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE'
           OR NEW.settle_operation_id IS NOT NULL
           OR NEW.buyer_player_id IS NOT NULL
           OR NEW.settlement_delivery_id IS NOT NULL
           OR NEW.settled_at IS NOT NULL THEN
            RAISE EXCEPTION 'new auction listing must start ACTIVE and unsettled'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.listing_id IS DISTINCT FROM OLD.listing_id
       OR NEW.seller_player_id IS DISTINCT FROM OLD.seller_player_id
       OR NEW.item_instance_id IS DISTINCT FROM OLD.item_instance_id
       OR NEW.escrow_item_version IS DISTINCT FROM OLD.escrow_item_version
       OR NEW.price_minor IS DISTINCT FROM OLD.price_minor
       OR NEW.create_operation_id IS DISTINCT FROM OLD.create_operation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'auction listing creation fields are immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status <> 'ACTIVE' OR NEW.status NOT IN ('SOLD', 'CANCELLED') THEN
        RAISE EXCEPTION 'auction listing permits only ACTIVE -> SOLD/CANCELLED transition'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER auction_listings_validate_transition
BEFORE INSERT OR UPDATE
ON auction_listings
FOR EACH ROW
EXECUTE FUNCTION validate_auction_listing_transition();

CREATE OR REPLACE FUNCTION validate_auction_listing_authority()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    delivery_recipient UUID;
    delivery_item UUID;
    delivery_issue_operation UUID;
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM item_instances i
            WHERE i.item_instance_id = NEW.item_instance_id
              AND i.location_kind = 'AUCTION_ESCROW'
              AND i.location_id = NEW.listing_id
              AND i.state_version = NEW.escrow_item_version
        ) THEN
            RAISE EXCEPTION 'active auction listing % does not own matching item escrow', NEW.listing_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NULL;
    END IF;

    SELECT recipient_player_id, item_instance_id, issue_operation_id
    INTO delivery_recipient, delivery_item, delivery_issue_operation
    FROM pending_unique_deliveries
    WHERE delivery_id = NEW.settlement_delivery_id;

    IF delivery_item IS NULL
       OR delivery_item <> NEW.item_instance_id
       OR delivery_issue_operation <> NEW.settle_operation_id THEN
        RAISE EXCEPTION 'settled auction listing % does not match settlement delivery', NEW.listing_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status = 'SOLD' AND delivery_recipient <> NEW.buyer_player_id THEN
        RAISE EXCEPTION 'sold auction listing % delivery recipient is not buyer', NEW.listing_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status = 'CANCELLED' AND delivery_recipient <> NEW.seller_player_id THEN
        RAISE EXCEPTION 'cancelled auction listing % delivery recipient is not seller', NEW.listing_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER auction_listings_require_authority
AFTER INSERT OR UPDATE
ON auction_listings
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_auction_listing_authority();

CREATE OR REPLACE FUNCTION validate_item_auction_escrow_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    listing_status TEXT;
    listing_item UUID;
    settlement_delivery UUID;
BEGIN
    IF NEW.location_kind = 'AUCTION_ESCROW' THEN
        SELECT status, item_instance_id
        INTO listing_status, listing_item
        FROM auction_listings
        WHERE listing_id = NEW.location_id;

        IF listing_item IS NULL
           OR listing_item <> NEW.item_instance_id
           OR listing_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'item % cannot enter auction escrow % without matching ACTIVE listing',
                NEW.item_instance_id,
                NEW.location_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.location_kind = 'AUCTION_ESCROW'
       AND (
           NEW.location_kind IS DISTINCT FROM OLD.location_kind
           OR NEW.location_id IS DISTINCT FROM OLD.location_id
       ) THEN
        SELECT status, item_instance_id, settlement_delivery_id
        INTO listing_status, listing_item, settlement_delivery
        FROM auction_listings
        WHERE listing_id = OLD.location_id;

        IF listing_item IS NULL
           OR listing_item <> NEW.item_instance_id
           OR listing_status NOT IN ('SOLD', 'CANCELLED')
           OR settlement_delivery IS NULL
           OR NEW.location_kind <> 'PENDING_DELIVERY'
           OR NEW.location_id IS DISTINCT FROM settlement_delivery THEN
            RAISE EXCEPTION 'item % cannot leave auction escrow % except through settled pending delivery',
                NEW.item_instance_id,
                OLD.location_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER item_instances_enforce_auction_escrow_transition
AFTER INSERT OR UPDATE OF location_kind, location_id
ON item_instances
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_item_auction_escrow_transition();
