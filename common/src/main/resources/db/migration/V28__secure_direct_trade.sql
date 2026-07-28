CREATE TABLE secure_trades (
    trade_id UUID PRIMARY KEY,
    player_a_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    player_b_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'OPEN',
    revision BIGINT NOT NULL DEFAULT 0,
    player_a_confirmed_revision BIGINT,
    player_b_confirmed_revision BIGINT,
    create_operation_id UUID NOT NULL UNIQUE,
    settle_operation_id UUID UNIQUE,
    cancel_operation_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMPTZ,
    CONSTRAINT secure_trades_players_distinct CHECK (player_a_id <> player_b_id),
    CONSTRAINT secure_trades_status_check CHECK (status IN ('OPEN', 'LOCKED', 'SETTLED', 'CANCELLED')),
    CONSTRAINT secure_trades_revision_check CHECK (revision >= 0),
    CONSTRAINT secure_trades_confirmation_revision_check CHECK (
        (player_a_confirmed_revision IS NULL OR player_a_confirmed_revision BETWEEN 0 AND revision)
        AND (player_b_confirmed_revision IS NULL OR player_b_confirmed_revision BETWEEN 0 AND revision)
    ),
    CONSTRAINT secure_trades_status_shape_check CHECK (
        (
            status = 'OPEN'
            AND settle_operation_id IS NULL
            AND cancel_operation_id IS NULL
            AND settled_at IS NULL
        )
        OR
        (
            status = 'LOCKED'
            AND player_a_confirmed_revision = revision
            AND player_b_confirmed_revision = revision
            AND settle_operation_id IS NULL
            AND cancel_operation_id IS NULL
            AND settled_at IS NULL
        )
        OR
        (
            status = 'SETTLED'
            AND player_a_confirmed_revision = revision
            AND player_b_confirmed_revision = revision
            AND settle_operation_id IS NOT NULL
            AND cancel_operation_id IS NULL
            AND settled_at IS NOT NULL
        )
        OR
        (
            status = 'CANCELLED'
            AND settle_operation_id IS NULL
            AND cancel_operation_id IS NOT NULL
            AND settled_at IS NOT NULL
        )
    )
);

CREATE INDEX secure_trades_player_a_idx ON secure_trades(player_a_id, created_at DESC);
CREATE INDEX secure_trades_player_b_idx ON secure_trades(player_b_id, created_at DESC);

CREATE TABLE secure_trade_coin_escrow (
    trade_id UUID NOT NULL REFERENCES secure_trades(trade_id) ON DELETE RESTRICT,
    owner_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    amount_minor BIGINT NOT NULL,
    PRIMARY KEY (trade_id, owner_player_id),
    CONSTRAINT secure_trade_coin_amount_check CHECK (amount_minor > 0)
);

CREATE TABLE secure_trade_commodity_escrow (
    trade_id UUID NOT NULL REFERENCES secure_trades(trade_id) ON DELETE RESTRICT,
    owner_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    commodity_definition_id TEXT NOT NULL,
    quantity BIGINT NOT NULL,
    PRIMARY KEY (trade_id, owner_player_id, commodity_definition_id),
    CONSTRAINT secure_trade_commodity_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT secure_trade_commodity_quantity_check CHECK (quantity > 0)
);

CREATE TABLE secure_trade_unique_items (
    trade_id UUID NOT NULL REFERENCES secure_trades(trade_id) ON DELETE RESTRICT,
    owner_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    item_instance_id UUID NOT NULL UNIQUE REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    escrow_item_version BIGINT NOT NULL,
    PRIMARY KEY (trade_id, item_instance_id),
    CONSTRAINT secure_trade_unique_item_version_check CHECK (escrow_item_version >= 0)
);

CREATE TABLE secure_trade_deliveries (
    trade_id UUID NOT NULL REFERENCES secure_trades(trade_id) ON DELETE RESTRICT,
    delivery_id UUID NOT NULL,
    delivery_kind TEXT NOT NULL,
    source_owner_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    recipient_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    item_instance_id UUID REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    commodity_definition_id TEXT,
    quantity BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (trade_id, delivery_id),
    CONSTRAINT secure_trade_deliveries_kind_check CHECK (delivery_kind IN ('UNIQUE_ITEM', 'COMMODITY')),
    CONSTRAINT secure_trade_deliveries_asset_shape CHECK (
        (
            delivery_kind = 'UNIQUE_ITEM'
            AND item_instance_id IS NOT NULL
            AND commodity_definition_id IS NULL
            AND quantity IS NULL
        )
        OR
        (
            delivery_kind = 'COMMODITY'
            AND item_instance_id IS NULL
            AND commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
            AND quantity > 0
        )
    )
);

ALTER TABLE item_instances
    DROP CONSTRAINT item_instances_location_kind_check,
    DROP CONSTRAINT item_instances_location_shape_check;

ALTER TABLE item_instances
    ADD CONSTRAINT item_instances_location_kind_check CHECK (
        location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'TRADE_ESCROW',
            'CLAN_STORAGE',
            'WAR_CUSTODY',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_instances_location_shape_check CHECK (
        (
            location_kind IN (
                'PLAYER_INVENTORY',
                'PENDING_DELIVERY',
                'AUCTION_ESCROW',
                'TRADE_ESCROW',
                'CLAN_STORAGE',
                'WAR_CUSTODY'
            )
            AND location_id IS NOT NULL
        )
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
            'TRADE_ESCROW',
            'CLAN_STORAGE',
            'WAR_CUSTODY',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_provenance_to_location_shape_check CHECK (
        (
            to_location_kind IN (
                'PLAYER_INVENTORY',
                'PENDING_DELIVERY',
                'AUCTION_ESCROW',
                'TRADE_ESCROW',
                'CLAN_STORAGE',
                'WAR_CUSTODY'
            )
            AND to_location_id IS NOT NULL
        )
        OR
        (to_location_kind IN ('QUARANTINE', 'DESTROYED') AND to_location_id IS NULL)
    ),
    ADD CONSTRAINT item_provenance_from_location_kind_check CHECK (
        from_location_kind IS NULL
        OR from_location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'TRADE_ESCROW',
            'CLAN_STORAGE',
            'WAR_CUSTODY',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_provenance_from_location_shape_check CHECK (
        (from_location_kind IS NULL AND from_location_id IS NULL)
        OR
        (
            from_location_kind IN (
                'PLAYER_INVENTORY',
                'PENDING_DELIVERY',
                'AUCTION_ESCROW',
                'TRADE_ESCROW',
                'CLAN_STORAGE',
                'WAR_CUSTODY'
            )
            AND from_location_id IS NOT NULL
        )
        OR
        (from_location_kind IN ('QUARANTINE', 'DESTROYED') AND from_location_id IS NULL)
    );

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

    IF NEW.location_kind = 'TRADE_ESCROW'
       AND NOT EXISTS (SELECT 1 FROM secure_trades WHERE trade_id = NEW.location_id) THEN
        RAISE EXCEPTION 'TRADE_ESCROW location references unknown trade_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.location_kind = 'CLAN_STORAGE'
       AND NOT EXISTS (SELECT 1 FROM clans WHERE clan_id = NEW.location_id) THEN
        RAISE EXCEPTION 'CLAN_STORAGE location references unknown clan_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.location_kind = 'WAR_CUSTODY'
       AND NOT EXISTS (SELECT 1 FROM clan_wars WHERE war_id = NEW.location_id) THEN
        RAISE EXCEPTION 'WAR_CUSTODY location references unknown war_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_secure_trade_participant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    participant_a UUID;
    participant_b UUID;
    trade_status TEXT;
    participant UUID;
BEGIN
    SELECT player_a_id, player_b_id, status
    INTO participant_a, participant_b, trade_status
    FROM secure_trades
    WHERE trade_id = NEW.trade_id;

    participant := NEW.owner_player_id;
    IF participant IS DISTINCT FROM participant_a AND participant IS DISTINCT FROM participant_b THEN
        RAISE EXCEPTION 'secure trade asset owner is not a participant %', participant
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF trade_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'secure trade offers may change only while OPEN %', NEW.trade_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER secure_trade_coin_validate
BEFORE INSERT OR UPDATE
ON secure_trade_coin_escrow
FOR EACH ROW
EXECUTE FUNCTION validate_secure_trade_participant();

CREATE TRIGGER secure_trade_commodity_validate
BEFORE INSERT OR UPDATE
ON secure_trade_commodity_escrow
FOR EACH ROW
EXECUTE FUNCTION validate_secure_trade_participant();

CREATE TRIGGER secure_trade_unique_validate
BEFORE INSERT OR UPDATE
ON secure_trade_unique_items
FOR EACH ROW
EXECUTE FUNCTION validate_secure_trade_participant();

CREATE OR REPLACE FUNCTION validate_secure_trade_asset_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    trade_status TEXT;
BEGIN
    SELECT status INTO trade_status FROM secure_trades WHERE trade_id = OLD.trade_id;
    IF trade_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'secure trade offers may be removed only while OPEN %', OLD.trade_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER secure_trade_coin_delete_validate
BEFORE DELETE ON secure_trade_coin_escrow
FOR EACH ROW EXECUTE FUNCTION validate_secure_trade_asset_delete();

CREATE TRIGGER secure_trade_commodity_delete_validate
BEFORE DELETE ON secure_trade_commodity_escrow
FOR EACH ROW EXECUTE FUNCTION validate_secure_trade_asset_delete();

CREATE TRIGGER secure_trade_unique_delete_validate
BEFORE DELETE ON secure_trade_unique_items
FOR EACH ROW EXECUTE FUNCTION validate_secure_trade_asset_delete();

CREATE OR REPLACE FUNCTION validate_secure_trade_item_custody()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM item_instances i
        WHERE i.item_instance_id = NEW.item_instance_id
          AND i.location_kind = 'TRADE_ESCROW'
          AND i.location_id = NEW.trade_id
          AND i.state_version = NEW.escrow_item_version
    ) THEN
        RAISE EXCEPTION 'secure trade item escrow does not match unique-item authority %', NEW.item_instance_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER secure_trade_unique_require_custody
AFTER INSERT OR UPDATE
ON secure_trade_unique_items
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_secure_trade_item_custody();

CREATE OR REPLACE FUNCTION reject_secure_trade_delivery_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'secure_trade_deliveries is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER secure_trade_deliveries_append_only
BEFORE UPDATE OR DELETE
ON secure_trade_deliveries
FOR EACH ROW
EXECUTE FUNCTION reject_secure_trade_delivery_mutation();
