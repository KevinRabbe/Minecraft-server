-- V71 made execution-loadout rows immutable after creation, but the table still permitted later INSERTs.
-- Seal every Clan-War snapshot at the end of the competitive-execution INSERT statement so even an intentionally
-- empty finalized selection is durably distinguishable from an unfinished snapshot and cannot be appended later.

CREATE TABLE competitive_execution_loadout_seals (
    execution_id UUID PRIMARY KEY
        REFERENCES competitive_executions(execution_id)
        ON DELETE CASCADE,
    sealed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Existing V71 Clan-War executions were materialized atomically before this migration; seal them in place.
INSERT INTO competitive_execution_loadout_seals(execution_id)
SELECT execution_id
FROM competitive_executions
WHERE activity_kind = 'CLAN_WAR'
ORDER BY execution_id;

CREATE TRIGGER competitive_execution_loadout_seals_immutable
BEFORE UPDATE OR DELETE
ON competitive_execution_loadout_seals
FOR EACH ROW
EXECUTE FUNCTION prevent_competitive_execution_loadout_mutation();

CREATE OR REPLACE FUNCTION validate_competitive_execution_loadout_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    execution_kind TEXT;
BEGIN
    SELECT activity_kind INTO execution_kind
    FROM competitive_executions
    WHERE execution_id = NEW.execution_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'competitive execution loadout requires an existing execution %', NEW.execution_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF execution_kind <> 'CLAN_WAR' THEN
        RAISE EXCEPTION 'competitive execution loadout rows are valid only for Clan War executions'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM competitive_execution_loadout_seals seal
        WHERE seal.execution_id = NEW.execution_id
    ) THEN
        RAISE EXCEPTION 'competitive execution loadout snapshot is already sealed %', NEW.execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_execution_loadout_validate_insert
BEFORE INSERT
ON competitive_execution_loadout_items
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_execution_loadout_insert();

CREATE OR REPLACE FUNCTION seal_competitive_execution_loadout_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.activity_kind = 'CLAN_WAR' THEN
        INSERT INTO competitive_execution_loadout_seals(execution_id)
        VALUES (NEW.execution_id);
    END IF;
    RETURN NEW;
END;
$$;

-- PostgreSQL fires same-kind triggers in name order. Existing AFTER INSERT triggers first materialize the runtime
-- manifest/loadout (materialize...) and reserve players (reserve...), then this seal... trigger closes the snapshot.
CREATE TRIGGER competitive_executions_seal_loadout_snapshot
AFTER INSERT
ON competitive_executions
FOR EACH ROW
EXECUTE FUNCTION seal_competitive_execution_loadout_snapshot();
