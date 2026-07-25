-- Ordinary PvE reuses resource_sources/resource_harvests. This layer binds an entity representation to one exact
-- source cycle so duplicated/stale mobs cannot consume a later cycle or mint vanilla-authority rewards.

CREATE TABLE resource_entity_sources (
    source_id UUID PRIMARY KEY REFERENCES resource_sources(source_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE resource_entity_spawns (
    spawn_id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES resource_entity_sources(source_id) ON DELETE RESTRICT,
    source_cycle_no BIGINT NOT NULL,
    status TEXT NOT NULL,
    entity_uuid UUID UNIQUE,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    killer_player_id UUID REFERENCES players(player_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    confirmed_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    UNIQUE (source_id, source_cycle_no),
    CONSTRAINT resource_entity_spawns_cycle_check CHECK (source_cycle_no >= 0),
    CONSTRAINT resource_entity_spawns_status_check CHECK (
        status IN ('PENDING', 'ACTIVE', 'KILLED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT resource_entity_spawns_shape_check CHECK (
        (status = 'PENDING'
            AND entity_uuid IS NULL
            AND killer_player_id IS NULL
            AND confirmed_at IS NULL
            AND resolved_at IS NULL)
        OR
        (status = 'ACTIVE'
            AND entity_uuid IS NOT NULL
            AND killer_player_id IS NULL
            AND confirmed_at IS NOT NULL
            AND resolved_at IS NULL)
        OR
        (status = 'KILLED'
            AND entity_uuid IS NOT NULL
            AND killer_player_id IS NOT NULL
            AND confirmed_at IS NOT NULL
            AND resolved_at IS NOT NULL)
        OR
        (status IN ('CANCELLED', 'EXPIRED')
            AND killer_player_id IS NULL
            AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX resource_entity_spawns_unresolved_idx
    ON resource_entity_spawns(source_id, lease_expires_at)
    WHERE status IN ('PENDING', 'ACTIVE');

CREATE TABLE resource_entity_kill_claims (
    operation_id UUID PRIMARY KEY,
    spawn_id UUID NOT NULL UNIQUE REFERENCES resource_entity_spawns(spawn_id) ON DELETE RESTRICT,
    entity_uuid UUID NOT NULL,
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION reject_resource_entity_source_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'resource_entity_sources is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER resource_entity_sources_append_only
BEFORE UPDATE OR DELETE
ON resource_entity_sources
FOR EACH ROW
EXECUTE FUNCTION reject_resource_entity_source_mutation();

CREATE OR REPLACE FUNCTION reject_resource_entity_kill_claim_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'resource_entity_kill_claims is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER resource_entity_kill_claims_append_only
BEFORE UPDATE OR DELETE
ON resource_entity_kill_claims
FOR EACH ROW
EXECUTE FUNCTION reject_resource_entity_kill_claim_mutation();

CREATE OR REPLACE FUNCTION validate_resource_entity_spawn_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_cycle BIGINT;
BEGIN
    SELECT cycle_no INTO current_cycle
    FROM resource_sources
    WHERE source_id = NEW.source_id;

    IF current_cycle IS NULL OR current_cycle IS DISTINCT FROM NEW.source_cycle_no THEN
        RAISE EXCEPTION 'entity spawn cycle does not match current source cycle for %', NEW.source_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> 'PENDING' THEN
        RAISE EXCEPTION 'new resource entity spawn must begin PENDING'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER resource_entity_spawns_validate_insert
BEFORE INSERT
ON resource_entity_spawns
FOR EACH ROW
EXECUTE FUNCTION validate_resource_entity_spawn_insert();

CREATE OR REPLACE FUNCTION validate_resource_entity_spawn_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.spawn_id IS DISTINCT FROM OLD.spawn_id
       OR NEW.source_id IS DISTINCT FROM OLD.source_id
       OR NEW.source_cycle_no IS DISTINCT FROM OLD.source_cycle_no
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'resource entity spawn identity is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status IN ('KILLED', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'terminal resource entity spawn is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'PENDING' AND NEW.status NOT IN ('ACTIVE', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'invalid resource entity PENDING transition to %', NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('KILLED', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'invalid resource entity ACTIVE transition to %', NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER resource_entity_spawns_validate_transition
BEFORE UPDATE
ON resource_entity_spawns
FOR EACH ROW
EXECUTE FUNCTION validate_resource_entity_spawn_transition();

CREATE OR REPLACE FUNCTION reject_resource_entity_spawn_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'resource_entity_spawns history cannot be deleted'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER resource_entity_spawns_no_delete
BEFORE DELETE
ON resource_entity_spawns
FOR EACH ROW
EXECUTE FUNCTION reject_resource_entity_spawn_delete();

CREATE OR REPLACE FUNCTION validate_resource_entity_spawn_source_cycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_cycle BIGINT;
BEGIN
    SELECT cycle_no INTO current_cycle
    FROM resource_sources
    WHERE source_id = NEW.source_id;

    IF NEW.status IN ('PENDING', 'ACTIVE') AND current_cycle IS DISTINCT FROM NEW.source_cycle_no THEN
        RAISE EXCEPTION 'unresolved entity spawn no longer matches current source cycle'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status IN ('KILLED', 'CANCELLED', 'EXPIRED') AND current_cycle <= NEW.source_cycle_no THEN
        RAISE EXCEPTION 'resolved entity spawn requires source cycle to advance'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER resource_entity_spawns_source_cycle_consistent
AFTER INSERT OR UPDATE
ON resource_entity_spawns
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_resource_entity_spawn_source_cycle();

CREATE OR REPLACE FUNCTION validate_resource_entity_harvest_claim()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    claim_spawn_id UUID;
    claim_entity_uuid UUID;
    bound_source_id UUID;
    bound_cycle BIGINT;
    bound_status TEXT;
    bound_entity_uuid UUID;
    bound_lease TIMESTAMPTZ;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM resource_entity_sources WHERE source_id = NEW.source_id
    ) THEN
        RETURN NEW;
    END IF;

    SELECT spawn_id, entity_uuid
    INTO claim_spawn_id, claim_entity_uuid
    FROM resource_entity_kill_claims
    WHERE operation_id = NEW.operation_id;

    IF claim_spawn_id IS NULL THEN
        RAISE EXCEPTION 'entity-bound source harvest requires an authorized kill claim'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT source_id, source_cycle_no, status, entity_uuid, lease_expires_at
    INTO bound_source_id, bound_cycle, bound_status, bound_entity_uuid, bound_lease
    FROM resource_entity_spawns
    WHERE spawn_id = claim_spawn_id
    FOR UPDATE;

    IF bound_source_id IS DISTINCT FROM NEW.source_id
       OR bound_cycle IS DISTINCT FROM NEW.source_cycle_no
       OR bound_status IS DISTINCT FROM 'ACTIVE'
       OR bound_entity_uuid IS DISTINCT FROM claim_entity_uuid
       OR bound_lease <= NOW() THEN
        RAISE EXCEPTION 'kill claim does not match the active authorized entity/source cycle'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    UPDATE resource_entity_spawns
    SET status = 'KILLED',
        killer_player_id = NEW.player_id,
        resolved_at = NOW()
    WHERE spawn_id = claim_spawn_id
      AND status = 'ACTIVE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'authorized entity spawn changed concurrently during kill settlement'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER resource_harvests_require_entity_kill_claim
BEFORE INSERT
ON resource_harvests
FOR EACH ROW
EXECUTE FUNCTION validate_resource_entity_harvest_claim();
