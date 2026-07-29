ALTER TABLE bounty_contracts
    ADD COLUMN content_version INTEGER NOT NULL DEFAULT 1,
    ADD CONSTRAINT bounty_contracts_content_version_check CHECK (content_version > 0);
