-- Durable bridge from an already-authorized ordinary-PvE entity harvest to bounty-family progress.
-- One resource kill operation is classified exactly once, including a permanent no-op when no ACTIVE_HUNT exists.

CREATE TABLE bounty_managed_kill_progress (
    resource_kill_operation_id UUID PRIMARY KEY
        REFERENCES resource_entity_kill_claims(operation_id) ON DELETE RESTRICT,
    progress_operation_id UUID NOT NULL UNIQUE,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    source_id UUID NOT NULL REFERENCES resource_sources(source_id) ON DELETE RESTRICT,
    source_definition_id TEXT NOT NULL,
    family_id TEXT NOT NULL,
    contract_id UUID REFERENCES bounty_contracts(contract_id) ON DELETE RESTRICT,
    eligible_kills INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT bounty_managed_kill_source_definition_check CHECK (
        source_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT bounty_managed_kill_family_check CHECK (
        family_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT bounty_managed_kill_count_check CHECK (eligible_kills > 0)
);

CREATE INDEX bounty_managed_kill_player_family_idx
    ON bounty_managed_kill_progress(player_id, family_id, created_at DESC);

CREATE OR REPLACE FUNCTION validate_bounty_managed_kill_progress()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    harvest_player UUID;
    harvest_source UUID;
    harvest_definition TEXT;
    contract_player UUID;
    contract_family TEXT;
BEGIN
    SELECT h.player_id, h.source_id, s.definition_id
    INTO harvest_player, harvest_source, harvest_definition
    FROM resource_harvests h
    JOIN resource_sources s ON s.source_id = h.source_id
    WHERE h.operation_id = NEW.resource_kill_operation_id;

    IF harvest_player IS NULL THEN
        RAISE EXCEPTION 'bounty managed kill requires an authoritative resource harvest %', NEW.resource_kill_operation_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF harvest_player IS DISTINCT FROM NEW.player_id
       OR harvest_source IS DISTINCT FROM NEW.source_id
       OR harvest_definition IS DISTINCT FROM NEW.source_definition_id THEN
        RAISE EXCEPTION 'bounty managed kill evidence does not match authoritative resource harvest %', NEW.resource_kill_operation_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.contract_id IS NOT NULL THEN
        SELECT player_id, family_id
        INTO contract_player, contract_family
        FROM bounty_contracts
        WHERE contract_id = NEW.contract_id;

        IF contract_player IS DISTINCT FROM NEW.player_id
           OR contract_family IS DISTINCT FROM NEW.family_id THEN
            RAISE EXCEPTION 'bounty managed kill contract does not match player/family %', NEW.contract_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER bounty_managed_kill_progress_validate
BEFORE INSERT
ON bounty_managed_kill_progress
FOR EACH ROW
EXECUTE FUNCTION validate_bounty_managed_kill_progress();

CREATE OR REPLACE FUNCTION reject_bounty_managed_kill_progress_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'bounty_managed_kill_progress is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER bounty_managed_kill_progress_append_only
BEFORE UPDATE OR DELETE
ON bounty_managed_kill_progress
FOR EACH ROW
EXECUTE FUNCTION reject_bounty_managed_kill_progress_mutation();
