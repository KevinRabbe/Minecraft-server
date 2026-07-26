-- Explicit opt-in FIFO matchmaking for the isolated 1.8.9 Ranked Arena category.
-- Queue presence is ephemeral intent, not match authority. Match creation remains durable in ranked_matches.

CREATE TABLE ranked_matchmaking_queue (
    player_id UUID PRIMARY KEY REFERENCES players(player_id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ranked_matchmaking_queue_order_idx
    ON ranked_matchmaking_queue(joined_at ASC, player_id ASC);

-- Any trusted path that creates a live Ranked participant consumes stale queue intent automatically.
CREATE OR REPLACE FUNCTION clear_ranked_queue_for_match_participant()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.released_at IS NULL THEN
        DELETE FROM ranked_matchmaking_queue
        WHERE player_id = NEW.player_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ranked_match_participants_clear_queue
AFTER INSERT
ON ranked_match_participants
FOR EACH ROW
EXECUTE FUNCTION clear_ranked_queue_for_match_participant();

-- A player assigned to any competitive execution (Ranked or Clan War) must no longer remain matchable in the queue.
CREATE OR REPLACE FUNCTION clear_ranked_queue_for_competitive_reservation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM ranked_matchmaking_queue
    WHERE player_id = NEW.player_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_reservations_clear_ranked_queue
AFTER INSERT
ON competitive_player_execution_reservations
FOR EACH ROW
EXECUTE FUNCTION clear_ranked_queue_for_competitive_reservation();
