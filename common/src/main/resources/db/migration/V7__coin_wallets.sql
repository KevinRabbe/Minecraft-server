CREATE TABLE wallets (
    player_id UUID PRIMARY KEY REFERENCES players(player_id) ON DELETE CASCADE,
    balance_minor BIGINT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT wallets_balance_nonnegative CHECK (balance_minor >= 0),
    CONSTRAINT wallets_version_nonnegative CHECK (state_version >= 0)
);

INSERT INTO wallets(player_id)
SELECT player_id
FROM players
ON CONFLICT (player_id) DO NOTHING;

CREATE OR REPLACE FUNCTION create_player_wallet()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO wallets(player_id)
    VALUES (NEW.player_id)
    ON CONFLICT (player_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER players_create_wallet
AFTER INSERT
ON players
FOR EACH ROW
EXECUTE FUNCTION create_player_wallet();
