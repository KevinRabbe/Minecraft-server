-- Frozen runtime-readable manifest for the isolated 1.8.9 backend.
-- It contains only execution/ruleset/participant routing identity. Persistent MMO inventory/economy/custody is absent.

CREATE TABLE competitive_execution_specs (
    execution_id UUID PRIMARY KEY REFERENCES competitive_executions(execution_id) ON DELETE RESTRICT,
    ruleset_id TEXT NOT NULL,
    ruleset_version INTEGER NOT NULL,
    team_size INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT competitive_execution_specs_ruleset_id_check CHECK (
        ruleset_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT competitive_execution_specs_ruleset_version_check CHECK (ruleset_version >= 1),
    CONSTRAINT competitive_execution_specs_team_size_check CHECK (team_size BETWEEN 1 AND 100)
);

CREATE TABLE competitive_execution_participants (
    execution_id UUID NOT NULL REFERENCES competitive_executions(execution_id) ON DELETE RESTRICT,
    participant_index INTEGER NOT NULL,
    side_key TEXT NOT NULL,
    side_id UUID NOT NULL,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    minecraft_uuid UUID NOT NULL,
    player_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (execution_id, player_id),
    CONSTRAINT competitive_execution_participants_index_unique UNIQUE (execution_id, participant_index),
    CONSTRAINT competitive_execution_participants_index_check CHECK (participant_index >= 0),
    CONSTRAINT competitive_execution_participants_side_key_check CHECK (
        side_key IN ('A', 'B', 'CHALLENGER', 'DEFENDER')
    ),
    CONSTRAINT competitive_execution_participants_name_check CHECK (
        BTRIM(player_name) <> '' AND CHAR_LENGTH(player_name) <= 16
    )
);

CREATE INDEX competitive_execution_participants_minecraft_idx
    ON competitive_execution_participants(execution_id, minecraft_uuid);

CREATE OR REPLACE FUNCTION validate_competitive_execution_spec_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    execution_row competitive_executions%ROWTYPE;
    ranked_row ranked_matches%ROWTYPE;
    war_row clan_wars%ROWTYPE;
BEGIN
    SELECT * INTO execution_row
    FROM competitive_executions
    WHERE execution_id = NEW.execution_id;

    IF NOT FOUND OR execution_row.status <> 'ASSIGNED' THEN
        RAISE EXCEPTION 'competitive runtime spec requires an ASSIGNED execution'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF execution_row.activity_kind = 'RANKED_ARENA' THEN
        SELECT * INTO ranked_row FROM ranked_matches WHERE match_id = execution_row.activity_id;
        IF NOT FOUND
           OR ranked_row.ruleset_id IS DISTINCT FROM NEW.ruleset_id
           OR ranked_row.ruleset_version IS DISTINCT FROM NEW.ruleset_version
           OR NEW.team_size <> 1 THEN
            RAISE EXCEPTION 'ranked runtime spec does not match authoritative match ruleset'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF execution_row.activity_kind = 'CLAN_WAR' THEN
        SELECT * INTO war_row FROM clan_wars WHERE war_id = execution_row.activity_id;
        IF NOT FOUND
           OR war_row.ruleset_id IS DISTINCT FROM NEW.ruleset_id
           OR war_row.ruleset_version IS DISTINCT FROM NEW.ruleset_version
           OR war_row.team_size IS DISTINCT FROM NEW.team_size THEN
            RAISE EXCEPTION 'clan-war runtime spec does not match authoritative war ruleset'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown competitive activity kind %', execution_row.activity_kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_execution_specs_validate_insert
BEFORE INSERT
ON competitive_execution_specs
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_execution_spec_insert();

CREATE OR REPLACE FUNCTION validate_competitive_execution_participant_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    execution_row competitive_executions%ROWTYPE;
    ranked_row ranked_matches%ROWTYPE;
    war_row clan_wars%ROWTYPE;
    authoritative_minecraft_uuid UUID;
    roster_clan_id UUID;
BEGIN
    SELECT * INTO execution_row
    FROM competitive_executions
    WHERE execution_id = NEW.execution_id;

    IF NOT FOUND OR execution_row.status <> 'ASSIGNED' THEN
        RAISE EXCEPTION 'competitive runtime participant requires an ASSIGNED execution'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT minecraft_uuid INTO authoritative_minecraft_uuid
    FROM players
    WHERE player_id = NEW.player_id;

    IF NOT FOUND OR authoritative_minecraft_uuid IS DISTINCT FROM NEW.minecraft_uuid THEN
        RAISE EXCEPTION 'competitive runtime participant Minecraft identity mismatch'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF execution_row.activity_kind = 'RANKED_ARENA' THEN
        SELECT * INTO ranked_row FROM ranked_matches WHERE match_id = execution_row.activity_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'ranked runtime participant has no authoritative match'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF NEW.side_key = 'A' THEN
            IF NEW.player_id IS DISTINCT FROM ranked_row.player_a_id
               OR NEW.side_id IS DISTINCT FROM ranked_row.player_a_id THEN
                RAISE EXCEPTION 'ranked A runtime participant does not match authoritative player A'
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.side_key = 'B' THEN
            IF NEW.player_id IS DISTINCT FROM ranked_row.player_b_id
               OR NEW.side_id IS DISTINCT FROM ranked_row.player_b_id THEN
                RAISE EXCEPTION 'ranked B runtime participant does not match authoritative player B'
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'ranked runtime participant side must be A or B'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF execution_row.activity_kind = 'CLAN_WAR' THEN
        SELECT * INTO war_row FROM clan_wars WHERE war_id = execution_row.activity_id;
        SELECT clan_id INTO roster_clan_id
        FROM clan_war_rosters
        WHERE war_id = execution_row.activity_id
          AND player_id = NEW.player_id
          AND released_at IS NULL;

        IF NOT FOUND OR roster_clan_id IS DISTINCT FROM NEW.side_id THEN
            RAISE EXCEPTION 'clan-war runtime participant is not in authoritative live roster/side'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        IF NEW.side_key = 'CHALLENGER' THEN
            IF NEW.side_id IS DISTINCT FROM war_row.challenger_clan_id THEN
                RAISE EXCEPTION 'clan-war challenger runtime participant has wrong side'
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSIF NEW.side_key = 'DEFENDER' THEN
            IF NEW.side_id IS DISTINCT FROM war_row.defender_clan_id THEN
                RAISE EXCEPTION 'clan-war defender runtime participant has wrong side'
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'clan-war runtime participant side must be CHALLENGER or DEFENDER'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown competitive activity kind %', execution_row.activity_kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_execution_participants_validate_insert
BEFORE INSERT
ON competitive_execution_participants
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_execution_participant_insert();

CREATE OR REPLACE FUNCTION reject_competitive_runtime_manifest_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'competitive runtime manifest rows are immutable'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER competitive_execution_specs_immutable
BEFORE UPDATE OR DELETE
ON competitive_execution_specs
FOR EACH ROW
EXECUTE FUNCTION reject_competitive_runtime_manifest_mutation();

CREATE TRIGGER competitive_execution_participants_immutable
BEFORE UPDATE OR DELETE
ON competitive_execution_participants
FOR EACH ROW
EXECUTE FUNCTION reject_competitive_runtime_manifest_mutation();
