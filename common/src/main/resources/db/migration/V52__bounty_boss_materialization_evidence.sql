-- Durable evidence binding one ACTIVE leased bounty summon to one disposable Minecraft boss entity.
-- Runtime entities remain disposable; this mapping prevents a forged/duplicate entity from settling the summon.

CREATE TABLE bounty_boss_materializations (
    summon_id UUID PRIMARY KEY REFERENCES bounty_summons(summon_id) ON DELETE RESTRICT,
    entity_uuid UUID NOT NULL UNIQUE,
    backend_id TEXT NOT NULL REFERENCES backends(backend_id) ON DELETE RESTRICT,
    boss_definition_id TEXT NOT NULL,
    world_name TEXT NOT NULL,
    spawn_x DOUBLE PRECISION NOT NULL,
    spawn_y DOUBLE PRECISION NOT NULL,
    spawn_z DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT bounty_boss_materialization_backend_check CHECK (
        backend_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$'
    ),
    CONSTRAINT bounty_boss_materialization_definition_check CHECK (
        boss_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT bounty_boss_materialization_world_check CHECK (
        length(btrim(world_name)) BETWEEN 1 AND 128
    )
);

CREATE INDEX bounty_boss_materializations_backend_idx
    ON bounty_boss_materializations(backend_id, created_at DESC);

CREATE OR REPLACE FUNCTION validate_bounty_boss_materialization()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    summon_status TEXT;
    summon_backend TEXT;
BEGIN
    SELECT status, owner_backend_id
    INTO summon_status, summon_backend
    FROM bounty_summons
    WHERE summon_id = NEW.summon_id;

    IF summon_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'bounty boss materialization requires ACTIVE summon %', NEW.summon_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF summon_backend IS DISTINCT FROM NEW.backend_id THEN
        RAISE EXCEPTION 'bounty boss materialization backend does not own summon %', NEW.summon_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER bounty_boss_materialization_validate
BEFORE INSERT
ON bounty_boss_materializations
FOR EACH ROW
EXECUTE FUNCTION validate_bounty_boss_materialization();

CREATE OR REPLACE FUNCTION reject_bounty_boss_materialization_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'bounty_boss_materializations is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER bounty_boss_materialization_append_only
BEFORE UPDATE OR DELETE
ON bounty_boss_materializations
FOR EACH ROW
EXECUTE FUNCTION reject_bounty_boss_materialization_mutation();
