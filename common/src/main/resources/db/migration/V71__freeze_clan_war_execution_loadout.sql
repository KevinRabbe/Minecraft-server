-- A Clan-War execution must freeze the finalized WAR_CUSTODY selection in the same transaction as assignment.
-- Persistent item_instance_id never enters this execution-scoped projection; the disposable runtime needs only the
-- combat-facing item definition/roll/upgrade state associated with a frozen participant.

CREATE TABLE competitive_execution_loadout_items (
    execution_id UUID NOT NULL,
    participant_index INTEGER NOT NULL,
    loadout_item_index INTEGER NOT NULL,
    definition_id TEXT NOT NULL,
    roll_state JSONB NOT NULL,
    upgrade_level INTEGER NOT NULL,
    PRIMARY KEY (execution_id, participant_index, loadout_item_index),
    CONSTRAINT competitive_execution_loadout_participant_fk
        FOREIGN KEY (execution_id, participant_index)
        REFERENCES competitive_execution_participants(execution_id, participant_index)
        ON DELETE CASCADE,
    CONSTRAINT competitive_execution_loadout_item_index_nonnegative
        CHECK (loadout_item_index >= 0),
    CONSTRAINT competitive_execution_loadout_upgrade_nonnegative
        CHECK (upgrade_level >= 0),
    CONSTRAINT competitive_execution_loadout_definition_nonblank
        CHECK (BTRIM(definition_id) <> '')
);

CREATE INDEX competitive_execution_loadout_execution_idx
    ON competitive_execution_loadout_items(execution_id, participant_index, loadout_item_index);

CREATE OR REPLACE FUNCTION prevent_competitive_execution_loadout_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'competitive execution loadout snapshots are immutable'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER competitive_execution_loadout_immutable
BEFORE UPDATE OR DELETE
ON competitive_execution_loadout_items
FOR EACH ROW
EXECUTE FUNCTION prevent_competitive_execution_loadout_mutation();

CREATE OR REPLACE FUNCTION materialize_competitive_runtime_manifest()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_participants INTEGER;
    actual_participants INTEGER;
    expected_loadout_items INTEGER := 0;
    actual_loadout_items INTEGER := 0;
BEGIN
    IF NEW.activity_kind = 'RANKED_ARENA' THEN
        INSERT INTO competitive_execution_specs(execution_id, ruleset_id, ruleset_version, team_size)
        SELECT NEW.execution_id, m.ruleset_id, m.ruleset_version, 1
        FROM ranked_matches m
        WHERE m.match_id = NEW.activity_id;

        INSERT INTO competitive_execution_participants(
            execution_id,
            participant_index,
            side_key,
            side_id,
            player_id,
            minecraft_uuid,
            player_name
        )
        SELECT NEW.execution_id,
               participant.participant_index,
               participant.side_key,
               participant.player_id,
               participant.player_id,
               p.minecraft_uuid,
               current_name.name
        FROM (
            SELECT 0 AS participant_index, 'A'::TEXT AS side_key, m.player_a_id AS player_id
            FROM ranked_matches m WHERE m.match_id = NEW.activity_id
            UNION ALL
            SELECT 1, 'B'::TEXT, m.player_b_id
            FROM ranked_matches m WHERE m.match_id = NEW.activity_id
        ) participant
        JOIN players p ON p.player_id = participant.player_id
        JOIN LATERAL (
            SELECT pn.name
            FROM player_names pn
            WHERE pn.player_id = participant.player_id
            ORDER BY pn.last_seen_at DESC, pn.name ASC
            LIMIT 1
        ) current_name ON TRUE
        ORDER BY participant.participant_index;

        expected_participants := 2;
    ELSIF NEW.activity_kind = 'CLAN_WAR' THEN
        IF EXISTS (
            SELECT 1
            FROM clan_war_rosters roster
            WHERE roster.war_id = NEW.activity_id
              AND roster.released_at IS NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM clan_war_loadout_confirmations confirmation
                  WHERE confirmation.war_id = roster.war_id
                    AND confirmation.player_id = roster.player_id
              )
        ) THEN
            RAISE EXCEPTION 'Clan-War execution % cannot materialize before every live roster loadout is finalized',
                NEW.activity_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;

        INSERT INTO competitive_execution_specs(execution_id, ruleset_id, ruleset_version, team_size)
        SELECT NEW.execution_id, w.ruleset_id, w.ruleset_version, w.team_size
        FROM clan_wars w
        WHERE w.war_id = NEW.activity_id;

        INSERT INTO competitive_execution_participants(
            execution_id,
            participant_index,
            side_key,
            side_id,
            player_id,
            minecraft_uuid,
            player_name
        )
        SELECT NEW.execution_id,
               ROW_NUMBER() OVER (
                   ORDER BY CASE WHEN r.clan_id = w.challenger_clan_id THEN 0 ELSE 1 END,
                            r.player_id
               )::INTEGER - 1,
               CASE
                   WHEN r.clan_id = w.challenger_clan_id THEN 'CHALLENGER'
                   ELSE 'DEFENDER'
               END,
               r.clan_id,
               r.player_id,
               p.minecraft_uuid,
               current_name.name
        FROM clan_wars w
        JOIN clan_war_rosters r ON r.war_id = w.war_id AND r.released_at IS NULL
        JOIN players p ON p.player_id = r.player_id
        JOIN LATERAL (
            SELECT pn.name
            FROM player_names pn
            WHERE pn.player_id = r.player_id
            ORDER BY pn.last_seen_at DESC, pn.name ASC
            LIMIT 1
        ) current_name ON TRUE
        WHERE w.war_id = NEW.activity_id
        ORDER BY 2;

        SELECT team_size * 2 INTO expected_participants
        FROM clan_wars
        WHERE war_id = NEW.activity_id;

        SELECT COUNT(*) INTO expected_loadout_items
        FROM clan_war_items war_item
        WHERE war_item.war_id = NEW.activity_id
          AND war_item.released_at IS NULL;

        INSERT INTO competitive_execution_loadout_items(
            execution_id,
            participant_index,
            loadout_item_index,
            definition_id,
            roll_state,
            upgrade_level
        )
        SELECT NEW.execution_id,
               participant.participant_index,
               ROW_NUMBER() OVER (
                   PARTITION BY participant.participant_index
                   ORDER BY item.definition_id ASC,
                            item.roll_state::TEXT ASC,
                            item.upgrade_level ASC,
                            war_item.item_instance_id ASC
               )::INTEGER - 1,
               item.definition_id,
               item.roll_state,
               item.upgrade_level
        FROM clan_war_items war_item
        JOIN competitive_execution_participants participant
          ON participant.execution_id = NEW.execution_id
         AND participant.player_id = war_item.player_id
        JOIN item_instances item ON item.item_instance_id = war_item.item_instance_id
        WHERE war_item.war_id = NEW.activity_id
          AND war_item.released_at IS NULL
          AND item.location_kind = 'WAR_CUSTODY'
          AND item.location_id = NEW.activity_id
          AND item.state_version = war_item.entry_item_version
        ORDER BY participant.participant_index, 3;

        GET DIAGNOSTICS actual_loadout_items = ROW_COUNT;
        IF actual_loadout_items <> expected_loadout_items THEN
            RAISE EXCEPTION 'Clan-War execution % materialized % loadout items; expected % active custody rows',
                NEW.execution_id, actual_loadout_items, expected_loadout_items
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown competitive activity kind %', NEW.activity_kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM competitive_execution_specs WHERE execution_id = NEW.execution_id
    ) THEN
        RAISE EXCEPTION 'competitive execution % could not materialize runtime spec', NEW.execution_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT COUNT(*) INTO actual_participants
    FROM competitive_execution_participants
    WHERE execution_id = NEW.execution_id;

    IF actual_participants <> expected_participants THEN
        RAISE EXCEPTION 'competitive execution % materialized % participants; expected %',
            NEW.execution_id, actual_participants, expected_participants
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;
