CREATE OR REPLACE FUNCTION materialize_competitive_runtime_manifest()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    expected_participants INTEGER;
    actual_participants INTEGER;
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

CREATE TRIGGER competitive_executions_materialize_runtime_manifest
AFTER INSERT
ON competitive_executions
FOR EACH ROW
EXECUTE FUNCTION materialize_competitive_runtime_manifest();
