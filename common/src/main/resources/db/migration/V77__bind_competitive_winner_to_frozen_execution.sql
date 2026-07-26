-- Winner reports must resolve to a side frozen into the execution manifest. For Clan War, a winner additionally
-- requires the V73/V74 loadout seal; FAILURE remains permitted so a runtime can abort safely if materialization breaks.

CREATE OR REPLACE FUNCTION validate_competitive_result_report_insert()
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

    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown competitive execution %', NEW.execution_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF execution_row.status <> 'ACTIVE'
       OR execution_row.backend_id IS DISTINCT FROM NEW.backend_id
       OR execution_row.lease_expires_at <= NOW() THEN
        RAISE EXCEPTION 'competitive execution is not reportable by backend %', NEW.backend_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.report_kind = 'WINNER' AND NOT EXISTS (
        SELECT 1
        FROM competitive_execution_participants participant
        WHERE participant.execution_id = NEW.execution_id
          AND participant.side_id = NEW.winner_id
    ) THEN
        RAISE EXCEPTION 'competitive report winner is not a frozen execution side'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF execution_row.activity_kind = 'RANKED_ARENA' THEN
        SELECT * INTO ranked_row FROM ranked_matches WHERE match_id = execution_row.activity_id;
        IF NOT FOUND OR ranked_row.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'ranked activity is not active for execution %', NEW.execution_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.report_kind = 'WINNER'
           AND NEW.winner_id NOT IN (ranked_row.player_a_id, ranked_row.player_b_id) THEN
            RAISE EXCEPTION 'ranked report winner is not a match participant'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF execution_row.activity_kind = 'CLAN_WAR' THEN
        SELECT * INTO war_row FROM clan_wars WHERE war_id = execution_row.activity_id;
        IF NOT FOUND OR war_row.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'clan-war activity is not active for execution %', NEW.execution_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.report_kind = 'WINNER'
           AND NEW.winner_id NOT IN (war_row.challenger_clan_id, war_row.defender_clan_id) THEN
            RAISE EXCEPTION 'clan-war report winner is not a war participant'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.report_kind = 'WINNER' AND NOT EXISTS (
            SELECT 1
            FROM competitive_execution_loadout_seals seal
            WHERE seal.execution_id = NEW.execution_id
        ) THEN
            RAISE EXCEPTION 'clan-war winner report requires a sealed frozen execution loadout'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown competitive activity kind %', execution_row.activity_kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;
