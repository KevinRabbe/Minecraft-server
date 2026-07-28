-- Cross-system persistent state families for the reconciled V1 architecture.
-- This migration establishes authority/constraint boundaries; feature repositories are added incrementally.

CREATE TABLE bank_accounts (
    player_id UUID PRIMARY KEY REFERENCES players(player_id) ON DELETE CASCADE,
    balance_minor BIGINT NOT NULL DEFAULT 0,
    tier INTEGER NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    last_interest_period DATE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT bank_accounts_balance_nonnegative CHECK (balance_minor >= 0),
    CONSTRAINT bank_accounts_tier_nonnegative CHECK (tier >= 0),
    CONSTRAINT bank_accounts_version_nonnegative CHECK (state_version >= 0)
);

INSERT INTO bank_accounts(player_id)
SELECT player_id
FROM players
ON CONFLICT (player_id) DO NOTHING;

CREATE OR REPLACE FUNCTION create_player_bank_account()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO bank_accounts(player_id)
    VALUES (NEW.player_id)
    ON CONFLICT (player_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER players_create_bank_account
AFTER INSERT
ON players
FOR EACH ROW
EXECUTE FUNCTION create_player_bank_account();

CREATE TABLE player_skills (
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE CASCADE,
    skill_id TEXT NOT NULL,
    experience BIGINT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_id, skill_id),
    CONSTRAINT player_skills_id_check CHECK (skill_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT player_skills_experience_nonnegative CHECK (experience >= 0),
    CONSTRAINT player_skills_version_nonnegative CHECK (state_version >= 0)
);

ALTER TABLE item_instances
    ADD COLUMN roll_state JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN upgrade_level INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT item_instances_roll_state_object CHECK (jsonb_typeof(roll_state) = 'object'),
    ADD CONSTRAINT item_instances_upgrade_level_check CHECK (upgrade_level BETWEEN 0 AND 10000);

CREATE TABLE craft_records (
    craft_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    recipe_id TEXT NOT NULL,
    recipe_version INTEGER NOT NULL DEFAULT 0,
    result_data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT craft_records_recipe_id_check CHECK (recipe_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT craft_records_recipe_version_check CHECK (recipe_version >= 0),
    CONSTRAINT craft_records_result_shape_check CHECK (jsonb_typeof(result_data) IN ('object', 'array'))
);

CREATE OR REPLACE FUNCTION reject_craft_record_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'craft_records is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER craft_records_append_only
BEFORE UPDATE OR DELETE
ON craft_records
FOR EACH ROW
EXECUTE FUNCTION reject_craft_record_mutation();

CREATE TABLE pending_commodity_deliveries (
    delivery_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    commodity_definition_id TEXT NOT NULL,
    quantity BIGINT NOT NULL,
    source_operation_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    claim_operation_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    CONSTRAINT pending_commodity_deliveries_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT pending_commodity_deliveries_quantity_check CHECK (quantity > 0),
    CONSTRAINT pending_commodity_deliveries_status_check CHECK (status IN ('PENDING', 'CLAIMED')),
    CONSTRAINT pending_commodity_deliveries_status_shape_check CHECK (
        (
            status = 'PENDING'
            AND claim_operation_id IS NULL
            AND claimed_at IS NULL
        )
        OR
        (
            status = 'CLAIMED'
            AND claim_operation_id IS NOT NULL
            AND claimed_at IS NOT NULL
        )
    ),
    CONSTRAINT pending_commodity_deliveries_source_unique UNIQUE (source_operation_id, commodity_definition_id)
);

CREATE INDEX pending_commodity_deliveries_player_pending_idx
    ON pending_commodity_deliveries(player_id, created_at)
    WHERE status = 'PENDING';

CREATE TABLE bazaar_orders (
    order_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    commodity_definition_id TEXT NOT NULL,
    side TEXT NOT NULL,
    limit_price_minor BIGINT NOT NULL,
    original_quantity BIGINT NOT NULL,
    remaining_quantity BIGINT NOT NULL,
    reserved_money_minor BIGINT NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'OPEN',
    create_operation_id UUID NOT NULL UNIQUE,
    cancel_operation_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMPTZ,
    CONSTRAINT bazaar_orders_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT bazaar_orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT bazaar_orders_price_check CHECK (limit_price_minor > 0),
    CONSTRAINT bazaar_orders_original_quantity_check CHECK (original_quantity > 0),
    CONSTRAINT bazaar_orders_remaining_quantity_check CHECK (
        remaining_quantity >= 0 AND remaining_quantity <= original_quantity
    ),
    CONSTRAINT bazaar_orders_reserved_money_check CHECK (reserved_money_minor >= 0),
    CONSTRAINT bazaar_orders_sell_money_check CHECK (side <> 'SELL' OR reserved_money_minor = 0),
    CONSTRAINT bazaar_orders_status_check CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED')),
    CONSTRAINT bazaar_orders_status_shape_check CHECK (
        (
            status = 'OPEN'
            AND remaining_quantity > 0
            AND cancel_operation_id IS NULL
            AND closed_at IS NULL
            AND (side <> 'BUY' OR reserved_money_minor > 0)
        )
        OR
        (
            status = 'FILLED'
            AND remaining_quantity = 0
            AND reserved_money_minor = 0
            AND cancel_operation_id IS NULL
            AND closed_at IS NOT NULL
        )
        OR
        (
            status = 'CANCELLED'
            AND remaining_quantity = 0
            AND reserved_money_minor = 0
            AND cancel_operation_id IS NOT NULL
            AND closed_at IS NOT NULL
        )
    )
);

CREATE INDEX bazaar_orders_sell_book_idx
    ON bazaar_orders(commodity_definition_id, limit_price_minor ASC, created_at ASC, order_id ASC)
    WHERE side = 'SELL' AND status = 'OPEN';

CREATE INDEX bazaar_orders_buy_book_idx
    ON bazaar_orders(commodity_definition_id, limit_price_minor DESC, created_at ASC, order_id ASC)
    WHERE side = 'BUY' AND status = 'OPEN';

CREATE INDEX bazaar_orders_player_open_idx
    ON bazaar_orders(player_id, created_at)
    WHERE status = 'OPEN';

CREATE TABLE bazaar_fills (
    fill_id UUID PRIMARY KEY,
    fill_operation_id UUID NOT NULL UNIQUE,
    buy_order_id UUID NOT NULL REFERENCES bazaar_orders(order_id) ON DELETE RESTRICT,
    sell_order_id UUID NOT NULL REFERENCES bazaar_orders(order_id) ON DELETE RESTRICT,
    quantity BIGINT NOT NULL,
    execution_price_minor BIGINT NOT NULL,
    fee_minor BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT bazaar_fills_distinct_orders CHECK (buy_order_id <> sell_order_id),
    CONSTRAINT bazaar_fills_quantity_check CHECK (quantity > 0),
    CONSTRAINT bazaar_fills_price_check CHECK (execution_price_minor > 0),
    CONSTRAINT bazaar_fills_fee_check CHECK (fee_minor >= 0)
);

CREATE OR REPLACE FUNCTION validate_bazaar_fill()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    buy_side TEXT;
    buy_commodity TEXT;
    buy_limit BIGINT;
    sell_side TEXT;
    sell_commodity TEXT;
    sell_limit BIGINT;
BEGIN
    SELECT side, commodity_definition_id, limit_price_minor
    INTO buy_side, buy_commodity, buy_limit
    FROM bazaar_orders
    WHERE order_id = NEW.buy_order_id;

    SELECT side, commodity_definition_id, limit_price_minor
    INTO sell_side, sell_commodity, sell_limit
    FROM bazaar_orders
    WHERE order_id = NEW.sell_order_id;

    IF buy_side IS DISTINCT FROM 'BUY' OR sell_side IS DISTINCT FROM 'SELL' THEN
        RAISE EXCEPTION 'bazaar fill references wrong order sides'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF buy_commodity IS DISTINCT FROM sell_commodity THEN
        RAISE EXCEPTION 'bazaar fill references different commodities'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.execution_price_minor > buy_limit OR NEW.execution_price_minor < sell_limit THEN
        RAISE EXCEPTION 'bazaar execution price is outside order limits'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER bazaar_fills_validate
BEFORE INSERT
ON bazaar_fills
FOR EACH ROW
EXECUTE FUNCTION validate_bazaar_fill();

CREATE OR REPLACE FUNCTION reject_bazaar_fill_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'bazaar_fills is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER bazaar_fills_append_only
BEFORE UPDATE OR DELETE
ON bazaar_fills
FOR EACH ROW
EXECUTE FUNCTION reject_bazaar_fill_mutation();

CREATE TABLE map_runs (
    run_id UUID PRIMARY KEY,
    source_map_item_id UUID NOT NULL UNIQUE REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    status TEXT NOT NULL,
    difficulty INTEGER NOT NULL,
    environment_id TEXT NOT NULL,
    enemy_family_id TEXT NOT NULL,
    objective_id TEXT NOT NULL,
    modifier_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    generation_seed BIGINT NOT NULL,
    generation_version INTEGER NOT NULL,
    balance_version INTEGER NOT NULL,
    world_era_id TEXT NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    reward_operation_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT map_runs_status_check CHECK (status IN ('CREATED', 'ACTIVE', 'COMPLETED', 'FAILED', 'CLOSED')),
    CONSTRAINT map_runs_difficulty_check CHECK (difficulty BETWEEN 1 AND 1000000),
    CONSTRAINT map_runs_environment_id_check CHECK (environment_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_runs_enemy_family_id_check CHECK (enemy_family_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_runs_objective_id_check CHECK (objective_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_runs_modifier_ids_array CHECK (jsonb_typeof(modifier_ids) = 'array'),
    CONSTRAINT map_runs_generation_version_check CHECK (generation_version >= 0),
    CONSTRAINT map_runs_balance_version_check CHECK (balance_version >= 0),
    CONSTRAINT map_runs_world_era_id_check CHECK (world_era_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_runs_state_version_check CHECK (state_version >= 0),
    CONSTRAINT map_runs_time_order_check CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR finished_at >= COALESCE(started_at, created_at))
    ),
    CONSTRAINT map_runs_terminal_shape_check CHECK (
        (status IN ('CREATED', 'ACTIVE') AND finished_at IS NULL)
        OR
        (status IN ('COMPLETED', 'FAILED', 'CLOSED') AND finished_at IS NOT NULL)
    )
);

CREATE TABLE map_run_participants (
    run_id UUID NOT NULL REFERENCES map_runs(run_id) ON DELETE CASCADE,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (run_id, player_id)
);

CREATE TABLE map_clears (
    clear_id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES map_runs(run_id) ON DELETE RESTRICT,
    difficulty INTEGER NOT NULL,
    elapsed_millis BIGINT NOT NULL,
    solo BOOLEAN NOT NULL,
    world_era_id TEXT NOT NULL,
    balance_version INTEGER NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT map_clears_difficulty_check CHECK (difficulty BETWEEN 1 AND 1000000),
    CONSTRAINT map_clears_elapsed_check CHECK (elapsed_millis > 0),
    CONSTRAINT map_clears_world_era_id_check CHECK (world_era_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT map_clears_balance_version_check CHECK (balance_version >= 0)
);

CREATE OR REPLACE FUNCTION reject_map_clear_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'map_clears is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER map_clears_append_only
BEFORE UPDATE OR DELETE
ON map_clears
FOR EACH ROW
EXECUTE FUNCTION reject_map_clear_mutation();

CREATE INDEX map_clears_solo_difficulty_idx
    ON map_clears(solo, world_era_id, difficulty DESC, elapsed_millis ASC, completed_at ASC);

CREATE TABLE bounty_contracts (
    contract_id UUID PRIMARY KEY,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    family_id TEXT NOT NULL,
    tier INTEGER NOT NULL,
    status TEXT NOT NULL,
    eligible_kill_progress INTEGER NOT NULL DEFAULT 0,
    required_eligible_kills INTEGER NOT NULL,
    summon_authorizations_remaining INTEGER NOT NULL DEFAULT 0,
    fee_operation_id UUID NOT NULL UNIQUE,
    reward_operation_id UUID UNIQUE,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT bounty_contracts_family_id_check CHECK (family_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT bounty_contracts_tier_check CHECK (tier > 0),
    CONSTRAINT bounty_contracts_status_check CHECK (
        status IN ('ACTIVE_HUNT', 'SUMMON_READY', 'SUMMONED', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT bounty_contracts_required_kills_check CHECK (required_eligible_kills > 0),
    CONSTRAINT bounty_contracts_progress_check CHECK (
        eligible_kill_progress BETWEEN 0 AND required_eligible_kills
    ),
    CONSTRAINT bounty_contracts_summon_check CHECK (summon_authorizations_remaining >= 0),
    CONSTRAINT bounty_contracts_version_check CHECK (state_version >= 0),
    CONSTRAINT bounty_contracts_terminal_time_check CHECK (
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
        OR
        (status IN ('ACTIVE_HUNT', 'SUMMON_READY', 'SUMMONED') AND completed_at IS NULL)
    )
);

CREATE UNIQUE INDEX bounty_contracts_one_active_family_idx
    ON bounty_contracts(player_id, family_id)
    WHERE status IN ('ACTIVE_HUNT', 'SUMMON_READY', 'SUMMONED');

CREATE TABLE bounty_pouches (
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE CASCADE,
    family_id TEXT NOT NULL,
    pouch_tier INTEGER NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_id, family_id),
    CONSTRAINT bounty_pouches_family_id_check CHECK (family_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT bounty_pouches_tier_check CHECK (pouch_tier >= 0),
    CONSTRAINT bounty_pouches_version_check CHECK (state_version >= 0)
);

CREATE TABLE bounty_pouch_balances (
    player_id UUID NOT NULL,
    family_id TEXT NOT NULL,
    commodity_definition_id TEXT NOT NULL,
    quantity BIGINT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_id, family_id, commodity_definition_id),
    FOREIGN KEY (player_id, family_id)
        REFERENCES bounty_pouches(player_id, family_id)
        ON DELETE CASCADE,
    CONSTRAINT bounty_pouch_balances_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT bounty_pouch_balances_quantity_check CHECK (quantity >= 0),
    CONSTRAINT bounty_pouch_balances_version_check CHECK (state_version >= 0)
);

CREATE TABLE world_eras (
    era_id TEXT PRIMARY KEY,
    sequence_no INTEGER NOT NULL UNIQUE,
    source_operation_id UUID UNIQUE,
    started_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT world_eras_id_check CHECK (era_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT world_eras_sequence_check CHECK (sequence_no >= 0)
);

CREATE TABLE feature_states (
    feature_id TEXT PRIMARY KEY,
    accessibility TEXT NOT NULL,
    source_operation_id UUID,
    state_version BIGINT NOT NULL DEFAULT 0,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT feature_states_id_check CHECK (feature_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT feature_states_accessibility_check CHECK (accessibility IN ('LOCKED', 'AVAILABLE')),
    CONSTRAINT feature_states_version_check CHECK (state_version >= 0),
    CONSTRAINT feature_states_source_check CHECK (
        accessibility <> 'AVAILABLE' OR source_operation_id IS NOT NULL
    )
);

CREATE TABLE expansion_votes (
    vote_id UUID PRIMARY KEY,
    candidate_set_version INTEGER NOT NULL,
    status TEXT NOT NULL,
    opens_at TIMESTAMPTZ NOT NULL,
    closes_at TIMESTAMPTZ NOT NULL,
    winning_candidate_id TEXT,
    resolution_operation_id UUID UNIQUE,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT expansion_votes_candidate_version_check CHECK (candidate_set_version >= 0),
    CONSTRAINT expansion_votes_status_check CHECK (status IN ('SCHEDULED', 'OPEN', 'RESOLVED', 'CANCELLED')),
    CONSTRAINT expansion_votes_window_check CHECK (closes_at > opens_at),
    CONSTRAINT expansion_votes_winner_id_check CHECK (
        winning_candidate_id IS NULL OR winning_candidate_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT expansion_votes_resolution_shape_check CHECK (
        (
            status = 'RESOLVED'
            AND winning_candidate_id IS NOT NULL
            AND resolution_operation_id IS NOT NULL
            AND resolved_at IS NOT NULL
        )
        OR
        (
            status <> 'RESOLVED'
            AND winning_candidate_id IS NULL
            AND resolution_operation_id IS NULL
            AND resolved_at IS NULL
        )
    )
);

CREATE TABLE expansion_vote_candidates (
    vote_id UUID NOT NULL REFERENCES expansion_votes(vote_id) ON DELETE CASCADE,
    candidate_set_version INTEGER NOT NULL,
    candidate_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    feature_ids JSONB NOT NULL,
    resulting_world_era_id TEXT,
    ordinal INTEGER NOT NULL,
    PRIMARY KEY (vote_id, candidate_set_version, candidate_id),
    CONSTRAINT expansion_vote_candidates_version_check CHECK (candidate_set_version >= 0),
    CONSTRAINT expansion_vote_candidates_id_check CHECK (candidate_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT expansion_vote_candidates_name_check CHECK (BTRIM(display_name) <> ''),
    CONSTRAINT expansion_vote_candidates_feature_ids_array CHECK (
        jsonb_typeof(feature_ids) = 'array' AND jsonb_array_length(feature_ids) > 0
    ),
    CONSTRAINT expansion_vote_candidates_era_check CHECK (
        resulting_world_era_id IS NULL OR resulting_world_era_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT expansion_vote_candidates_ordinal_check CHECK (ordinal >= 0),
    CONSTRAINT expansion_vote_candidates_ordinal_unique UNIQUE (vote_id, candidate_set_version, ordinal)
);

ALTER TABLE expansion_votes
    ADD CONSTRAINT expansion_votes_winner_candidate_fk
    FOREIGN KEY (vote_id, candidate_set_version, winning_candidate_id)
    REFERENCES expansion_vote_candidates(vote_id, candidate_set_version, candidate_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE expansion_ballots (
    vote_id UUID NOT NULL,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    candidate_set_version INTEGER NOT NULL,
    candidate_id TEXT NOT NULL,
    cast_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (vote_id, player_id),
    FOREIGN KEY (vote_id, candidate_set_version, candidate_id)
        REFERENCES expansion_vote_candidates(vote_id, candidate_set_version, candidate_id)
        ON DELETE RESTRICT,
    CONSTRAINT expansion_ballots_candidate_version_check CHECK (candidate_set_version >= 0),
    CONSTRAINT expansion_ballots_candidate_id_check CHECK (candidate_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$')
);

CREATE INDEX expansion_ballots_count_idx
    ON expansion_ballots(vote_id, candidate_set_version, candidate_id);

CREATE TABLE historical_events (
    event_id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    source_kind TEXT NOT NULL,
    source_id TEXT NOT NULL,
    world_era_id TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT historical_events_type_check CHECK (event_type ~ '^[A-Z][A-Z0-9_]{0,95}$'),
    CONSTRAINT historical_events_source_kind_check CHECK (source_kind ~ '^[A-Z][A-Z0-9_]{0,95}$'),
    CONSTRAINT historical_events_source_id_check CHECK (BTRIM(source_id) <> ''),
    CONSTRAINT historical_events_era_check CHECK (
        world_era_id IS NULL OR world_era_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT historical_events_metadata_object CHECK (jsonb_typeof(metadata) = 'object'),
    CONSTRAINT historical_events_source_unique UNIQUE (source_kind, source_id, event_type)
);

CREATE OR REPLACE FUNCTION reject_historical_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'historical_events is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER historical_events_append_only
BEFORE UPDATE OR DELETE
ON historical_events
FOR EACH ROW
EXECUTE FUNCTION reject_historical_event_mutation();
