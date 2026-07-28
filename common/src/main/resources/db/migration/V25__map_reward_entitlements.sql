CREATE TABLE map_reward_settlements (
    run_id UUID PRIMARY KEY REFERENCES map_runs(run_id) ON DELETE RESTRICT,
    settlement_operation_id UUID NOT NULL UNIQUE,
    resolver_version INTEGER NOT NULL,
    settled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT map_reward_settlements_resolver_version_check CHECK (resolver_version >= 0)
);

CREATE TABLE map_reward_grants (
    grant_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES map_reward_settlements(run_id) ON DELETE RESTRICT,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL,
    reward_kind TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    quantity BIGINT NOT NULL,
    map_profile JSONB,
    status TEXT NOT NULL DEFAULT 'PENDING',
    fulfillment_operation_id UUID UNIQUE,
    fulfilled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT map_reward_grants_ordinal_check CHECK (ordinal >= 0),
    CONSTRAINT map_reward_grants_kind_check CHECK (
        reward_kind IN ('COMMODITY', 'UNIQUE_ITEM', 'MAP')
    ),
    CONSTRAINT map_reward_grants_definition_check CHECK (
        definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT map_reward_grants_quantity_check CHECK (quantity > 0),
    CONSTRAINT map_reward_grants_map_shape_check CHECK (
        (reward_kind = 'MAP' AND quantity = 1 AND map_profile IS NOT NULL AND jsonb_typeof(map_profile) = 'object')
        OR
        (reward_kind <> 'MAP' AND map_profile IS NULL)
    ),
    CONSTRAINT map_reward_grants_unique_quantity_check CHECK (
        reward_kind = 'COMMODITY' OR quantity = 1
    ),
    CONSTRAINT map_reward_grants_status_check CHECK (status IN ('PENDING', 'FULFILLED')),
    CONSTRAINT map_reward_grants_fulfillment_shape_check CHECK (
        (status = 'PENDING' AND fulfillment_operation_id IS NULL AND fulfilled_at IS NULL)
        OR
        (status = 'FULFILLED' AND fulfillment_operation_id IS NOT NULL AND fulfilled_at IS NOT NULL)
    ),
    CONSTRAINT map_reward_grants_run_ordinal_unique UNIQUE (run_id, ordinal)
);

CREATE INDEX map_reward_grants_player_pending_idx
    ON map_reward_grants(player_id, created_at, grant_id)
    WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION validate_map_reward_settlement()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    run_status TEXT;
    run_reward_operation_id UUID;
BEGIN
    SELECT status, reward_operation_id
    INTO run_status, run_reward_operation_id
    FROM map_runs
    WHERE run_id = NEW.run_id;

    IF run_status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION 'Map rewards require COMPLETED run %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF run_reward_operation_id IS DISTINCT FROM NEW.settlement_operation_id THEN
        RAISE EXCEPTION 'Map reward settlement operation does not match run reward authority %', NEW.run_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_reward_settlements_validate
BEFORE INSERT
ON map_reward_settlements
FOR EACH ROW
EXECUTE FUNCTION validate_map_reward_settlement();

CREATE OR REPLACE FUNCTION reject_map_reward_settlement_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'map_reward_settlements is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER map_reward_settlements_append_only
BEFORE UPDATE OR DELETE
ON map_reward_settlements
FOR EACH ROW
EXECUTE FUNCTION reject_map_reward_settlement_mutation();

CREATE OR REPLACE FUNCTION validate_map_reward_grant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM map_run_participants p
            WHERE p.run_id = NEW.run_id
              AND p.player_id = NEW.player_id
        ) THEN
            RAISE EXCEPTION 'Map reward recipient is not an authoritative run participant % / %', NEW.run_id, NEW.player_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.grant_id IS DISTINCT FROM OLD.grant_id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.player_id IS DISTINCT FROM OLD.player_id
       OR NEW.ordinal IS DISTINCT FROM OLD.ordinal
       OR NEW.reward_kind IS DISTINCT FROM OLD.reward_kind
       OR NEW.definition_id IS DISTINCT FROM OLD.definition_id
       OR NEW.quantity IS DISTINCT FROM OLD.quantity
       OR NEW.map_profile IS DISTINCT FROM OLD.map_profile
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Map reward grant definition is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status <> 'PENDING'
       OR NEW.status <> 'FULFILLED'
       OR NEW.fulfillment_operation_id IS NULL
       OR NEW.fulfilled_at IS NULL THEN
        RAISE EXCEPTION 'Map reward grant permits only PENDING -> FULFILLED transition'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER map_reward_grants_validate
BEFORE INSERT OR UPDATE
ON map_reward_grants
FOR EACH ROW
EXECUTE FUNCTION validate_map_reward_grant();
