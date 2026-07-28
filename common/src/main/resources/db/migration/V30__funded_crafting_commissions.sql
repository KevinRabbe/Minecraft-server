CREATE TABLE crafting_commissions (
    commission_id UUID PRIMARY KEY,
    requester_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    worker_player_id UUID REFERENCES players(player_id) ON DELETE RESTRICT,
    recipe_id TEXT NOT NULL,
    recipe_version INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    payment_minor BIGINT NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    create_operation_id UUID NOT NULL UNIQUE,
    accept_operation_id UUID UNIQUE,
    cancel_operation_id UUID UNIQUE,
    settle_operation_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    accepted_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    CONSTRAINT crafting_commissions_recipe_id_check CHECK (
        recipe_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT crafting_commissions_recipe_version_check CHECK (recipe_version >= 0),
    CONSTRAINT crafting_commissions_payment_check CHECK (payment_minor >= 0),
    CONSTRAINT crafting_commissions_state_version_check CHECK (state_version >= 0),
    CONSTRAINT crafting_commissions_status_check CHECK (
        status IN ('OPEN', 'ACCEPTED', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT crafting_commissions_shape_check CHECK (
        (
            status = 'OPEN'
            AND worker_player_id IS NULL
            AND accept_operation_id IS NULL
            AND cancel_operation_id IS NULL
            AND settle_operation_id IS NULL
            AND accepted_at IS NULL
            AND settled_at IS NULL
        )
        OR
        (
            status = 'ACCEPTED'
            AND worker_player_id IS NOT NULL
            AND worker_player_id <> requester_player_id
            AND accept_operation_id IS NOT NULL
            AND cancel_operation_id IS NULL
            AND settle_operation_id IS NULL
            AND accepted_at IS NOT NULL
            AND settled_at IS NULL
        )
        OR
        (
            status = 'COMPLETED'
            AND worker_player_id IS NOT NULL
            AND worker_player_id <> requester_player_id
            AND accept_operation_id IS NOT NULL
            AND cancel_operation_id IS NULL
            AND settle_operation_id IS NOT NULL
            AND accepted_at IS NOT NULL
            AND settled_at IS NOT NULL
        )
        OR
        (
            status = 'CANCELLED'
            AND cancel_operation_id IS NOT NULL
            AND settle_operation_id IS NULL
            AND settled_at IS NOT NULL
        )
    )
);

CREATE INDEX crafting_commissions_status_created_idx
    ON crafting_commissions(status, created_at, commission_id);

CREATE INDEX crafting_commissions_requester_idx
    ON crafting_commissions(requester_player_id, created_at DESC);

CREATE INDEX crafting_commissions_worker_idx
    ON crafting_commissions(worker_player_id, created_at DESC)
    WHERE worker_player_id IS NOT NULL;

CREATE TABLE crafting_commission_materials (
    commission_id UUID NOT NULL REFERENCES crafting_commissions(commission_id) ON DELETE RESTRICT,
    commodity_definition_id TEXT NOT NULL,
    quantity BIGINT NOT NULL,
    PRIMARY KEY (commission_id, commodity_definition_id),
    CONSTRAINT crafting_commission_material_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT crafting_commission_material_quantity_check CHECK (quantity > 0)
);

CREATE TABLE crafting_commission_returns (
    commission_id UUID NOT NULL REFERENCES crafting_commissions(commission_id) ON DELETE RESTRICT,
    delivery_id UUID NOT NULL UNIQUE REFERENCES pending_commodity_deliveries(delivery_id) ON DELETE RESTRICT,
    commodity_definition_id TEXT NOT NULL,
    quantity BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (commission_id, commodity_definition_id),
    CONSTRAINT crafting_commission_return_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT crafting_commission_return_quantity_check CHECK (quantity > 0)
);

CREATE OR REPLACE FUNCTION reject_crafting_commission_material_mutation_after_open()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_status TEXT;
BEGIN
    SELECT status INTO current_status
    FROM crafting_commissions
    WHERE commission_id = COALESCE(NEW.commission_id, OLD.commission_id);

    IF current_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'crafting commission materials are immutable after acceptance %',
            COALESCE(NEW.commission_id, OLD.commission_id)
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER crafting_commission_materials_open_only
BEFORE INSERT OR UPDATE OR DELETE
ON crafting_commission_materials
FOR EACH ROW
EXECUTE FUNCTION reject_crafting_commission_material_mutation_after_open();

CREATE OR REPLACE FUNCTION reject_crafting_commission_return_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'crafting_commission_returns is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER crafting_commission_returns_append_only
BEFORE UPDATE OR DELETE
ON crafting_commission_returns
FOR EACH ROW
EXECUTE FUNCTION reject_crafting_commission_return_mutation();
