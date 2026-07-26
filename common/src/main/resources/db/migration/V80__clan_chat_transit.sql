CREATE TABLE clan_chat_messages (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id UUID NOT NULL UNIQUE,
    clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE CASCADE,
    sender_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    sender_name TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT clan_chat_messages_sender_name_check CHECK (
        BTRIM(sender_name) <> '' AND CHAR_LENGTH(sender_name) <= 64
    ),
    CONSTRAINT clan_chat_messages_body_check CHECK (
        BTRIM(body) <> '' AND CHAR_LENGTH(body) <= 256
    )
);

CREATE INDEX clan_chat_messages_clan_sequence_idx
    ON clan_chat_messages(clan_id, sequence);

CREATE INDEX clan_chat_messages_created_at_idx
    ON clan_chat_messages(created_at, sequence);

CREATE OR REPLACE FUNCTION validate_clan_chat_message_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM clan_members
        WHERE clan_id = NEW.clan_id
          AND player_id = NEW.sender_player_id
    ) THEN
        RAISE EXCEPTION 'clan chat sender % is not a current member of clan %',
            NEW.sender_player_id, NEW.clan_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER clan_chat_messages_validate_insert
BEFORE INSERT
ON clan_chat_messages
FOR EACH ROW
EXECUTE FUNCTION validate_clan_chat_message_insert();

CREATE OR REPLACE FUNCTION reject_clan_chat_message_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'clan chat message rows are immutable %', OLD.message_id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER clan_chat_messages_reject_update
BEFORE UPDATE
ON clan_chat_messages
FOR EACH ROW
EXECUTE FUNCTION reject_clan_chat_message_update();
