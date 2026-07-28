ALTER TABLE historical_events
    ADD CONSTRAINT historical_events_source_id_length_check
        CHECK (CHAR_LENGTH(source_id) <= 256),
    ADD CONSTRAINT historical_events_world_era_fk
        FOREIGN KEY (world_era_id)
        REFERENCES world_eras(era_id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX historical_events_recent_idx
    ON historical_events(occurred_at DESC, event_id DESC);

CREATE INDEX historical_events_source_idx
    ON historical_events(source_kind, source_id, occurred_at DESC);
