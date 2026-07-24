CREATE TABLE clans (
    clan_id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    tag TEXT NOT NULL UNIQUE,
    created_by_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT clans_name_check CHECK (BTRIM(name) <> ''),
    CONSTRAINT clans_tag_check CHECK (BTRIM(tag) <> ''),
    CONSTRAINT clans_version_check CHECK (state_version >= 0)
);

CREATE TABLE clan_members (
    clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE CASCADE,
    player_id UUID NOT NULL UNIQUE REFERENCES players(player_id) ON DELETE RESTRICT,
    role TEXT NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (clan_id, player_id),
    CONSTRAINT clan_members_role_check CHECK (role IN ('LEADER', 'OFFICER', 'MEMBER'))
);

CREATE UNIQUE INDEX clan_members_one_leader_idx
    ON clan_members(clan_id)
    WHERE role = 'LEADER';

CREATE TABLE clan_treasuries (
    clan_id UUID PRIMARY KEY REFERENCES clans(clan_id) ON DELETE CASCADE,
    balance_minor BIGINT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT clan_treasuries_balance_check CHECK (balance_minor >= 0),
    CONSTRAINT clan_treasuries_version_check CHECK (state_version >= 0)
);

CREATE OR REPLACE FUNCTION create_clan_treasury()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO clan_treasuries(clan_id)
    VALUES (NEW.clan_id)
    ON CONFLICT (clan_id) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER clans_create_treasury
AFTER INSERT
ON clans
FOR EACH ROW
EXECUTE FUNCTION create_clan_treasury();

CREATE TABLE clan_commodity_balances (
    clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE CASCADE,
    commodity_definition_id TEXT NOT NULL,
    quantity BIGINT NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (clan_id, commodity_definition_id),
    CONSTRAINT clan_commodity_balances_definition_check CHECK (
        commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
    ),
    CONSTRAINT clan_commodity_balances_quantity_check CHECK (quantity >= 0),
    CONSTRAINT clan_commodity_balances_version_check CHECK (state_version >= 0)
);

CREATE TABLE ranked_ratings (
    player_id UUID PRIMARY KEY REFERENCES players(player_id) ON DELETE CASCADE,
    rating INTEGER NOT NULL DEFAULT 1000,
    state_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ranked_ratings_rating_check CHECK (rating >= 0),
    CONSTRAINT ranked_ratings_version_check CHECK (state_version >= 0)
);

CREATE TABLE ranked_matches (
    match_id UUID PRIMARY KEY,
    player_a_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    player_b_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    status TEXT NOT NULL,
    winner_player_id UUID REFERENCES players(player_id) ON DELETE RESTRICT,
    result_operation_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ranked_matches_players_distinct CHECK (player_a_id <> player_b_id),
    CONSTRAINT ranked_matches_status_check CHECK (status IN ('CREATED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ranked_matches_winner_check CHECK (
        winner_player_id IS NULL OR winner_player_id IN (player_a_id, player_b_id)
    ),
    CONSTRAINT ranked_matches_result_shape_check CHECK (
        (
            status = 'COMPLETED'
            AND result_operation_id IS NOT NULL
            AND finished_at IS NOT NULL
        )
        OR
        (
            status <> 'COMPLETED'
            AND result_operation_id IS NULL
        )
    ),
    CONSTRAINT ranked_matches_time_order_check CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR finished_at >= COALESCE(started_at, created_at))
    )
);

CREATE TABLE clan_wars (
    war_id UUID PRIMARY KEY,
    challenger_clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE RESTRICT,
    defender_clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE RESTRICT,
    status TEXT NOT NULL,
    winning_clan_id UUID REFERENCES clans(clan_id) ON DELETE RESTRICT,
    settlement_operation_id UUID UNIQUE,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    CONSTRAINT clan_wars_clans_distinct CHECK (challenger_clan_id <> defender_clan_id),
    CONSTRAINT clan_wars_status_check CHECK (
        status IN ('CHALLENGED', 'ACCEPTED', 'ROSTER_LOCKED', 'ACTIVE', 'COMPLETED', 'CANCELLED', 'FAILED')
    ),
    CONSTRAINT clan_wars_winner_check CHECK (
        winning_clan_id IS NULL OR winning_clan_id IN (challenger_clan_id, defender_clan_id)
    ),
    CONSTRAINT clan_wars_version_check CHECK (state_version >= 0),
    CONSTRAINT clan_wars_settlement_shape_check CHECK (
        (
            status = 'COMPLETED'
            AND settlement_operation_id IS NOT NULL
            AND finished_at IS NOT NULL
        )
        OR
        (
            status <> 'COMPLETED'
            AND settlement_operation_id IS NULL
        )
    ),
    CONSTRAINT clan_wars_time_order_check CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR finished_at >= COALESCE(started_at, created_at))
    )
);

CREATE TABLE clan_war_rosters (
    war_id UUID NOT NULL REFERENCES clan_wars(war_id) ON DELETE CASCADE,
    clan_id UUID NOT NULL REFERENCES clans(clan_id) ON DELETE RESTRICT,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    locked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (war_id, player_id),
    CONSTRAINT clan_war_rosters_clan_membership_unique UNIQUE (war_id, clan_id, player_id)
);

CREATE TABLE clan_war_items (
    war_id UUID NOT NULL REFERENCES clan_wars(war_id) ON DELETE RESTRICT,
    player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    item_instance_id UUID NOT NULL REFERENCES item_instances(item_instance_id) ON DELETE RESTRICT,
    entry_item_version BIGINT NOT NULL,
    released_at TIMESTAMPTZ,
    PRIMARY KEY (war_id, item_instance_id),
    CONSTRAINT clan_war_items_version_check CHECK (entry_item_version >= 0)
);

CREATE UNIQUE INDEX clan_war_items_one_active_custody_idx
    ON clan_war_items(item_instance_id)
    WHERE released_at IS NULL;

ALTER TABLE item_instances
    DROP CONSTRAINT item_instances_location_kind_check,
    DROP CONSTRAINT item_instances_location_shape_check;

ALTER TABLE item_instances
    ADD CONSTRAINT item_instances_location_kind_check CHECK (
        location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'CLAN_STORAGE',
            'WAR_CUSTODY',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_instances_location_shape_check CHECK (
        (
            location_kind IN (
                'PLAYER_INVENTORY',
                'PENDING_DELIVERY',
                'AUCTION_ESCROW',
                'CLAN_STORAGE',
                'WAR_CUSTODY'
            )
            AND location_id IS NOT NULL
        )
        OR
        (location_kind IN ('QUARANTINE', 'DESTROYED') AND location_id IS NULL)
    );

ALTER TABLE item_provenance
    DROP CONSTRAINT item_provenance_to_location_kind_check,
    DROP CONSTRAINT item_provenance_to_location_shape_check,
    DROP CONSTRAINT item_provenance_from_location_kind_check,
    DROP CONSTRAINT item_provenance_from_location_shape_check;

ALTER TABLE item_provenance
    ADD CONSTRAINT item_provenance_to_location_kind_check CHECK (
        to_location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'CLAN_STORAGE',
            'WAR_CUSTODY',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_provenance_to_location_shape_check CHECK (
        (
            to_location_kind IN (
                'PLAYER_INVENTORY',
                'PENDING_DELIVERY',
                'AUCTION_ESCROW',
                'CLAN_STORAGE',
                'WAR_CUSTODY'
            )
            AND to_location_id IS NOT NULL
        )
        OR
        (to_location_kind IN ('QUARANTINE', 'DESTROYED') AND to_location_id IS NULL)
    ),
    ADD CONSTRAINT item_provenance_from_location_kind_check CHECK (
        from_location_kind IS NULL
        OR from_location_kind IN (
            'PLAYER_INVENTORY',
            'PENDING_DELIVERY',
            'AUCTION_ESCROW',
            'CLAN_STORAGE',
            'WAR_CUSTODY',
            'QUARANTINE',
            'DESTROYED'
        )
    ),
    ADD CONSTRAINT item_provenance_from_location_shape_check CHECK (
        (from_location_kind IS NULL AND from_location_id IS NULL)
        OR
        (
            from_location_kind IN (
                'PLAYER_INVENTORY',
                'PENDING_DELIVERY',
                'AUCTION_ESCROW',
                'CLAN_STORAGE',
                'WAR_CUSTODY'
            )
            AND from_location_id IS NOT NULL
        )
        OR
        (from_location_kind IN ('QUARANTINE', 'DESTROYED') AND from_location_id IS NULL)
    );

CREATE OR REPLACE FUNCTION validate_item_instance_player_location()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.location_id) THEN
        RAISE EXCEPTION 'PLAYER_INVENTORY location references unknown player_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.location_kind = 'CLAN_STORAGE'
       AND NOT EXISTS (SELECT 1 FROM clans WHERE clan_id = NEW.location_id) THEN
        RAISE EXCEPTION 'CLAN_STORAGE location references unknown clan_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.location_kind = 'WAR_CUSTODY'
       AND NOT EXISTS (SELECT 1 FROM clan_wars WHERE war_id = NEW.location_id) THEN
        RAISE EXCEPTION 'WAR_CUSTODY location references unknown war_id %', NEW.location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION validate_item_provenance_player_locations()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.from_location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.from_location_id) THEN
        RAISE EXCEPTION 'item provenance from-location references unknown player_id %', NEW.from_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.to_location_kind = 'PLAYER_INVENTORY'
       AND NOT EXISTS (SELECT 1 FROM players WHERE player_id = NEW.to_location_id) THEN
        RAISE EXCEPTION 'item provenance to-location references unknown player_id %', NEW.to_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.from_location_kind = 'CLAN_STORAGE'
       AND NOT EXISTS (SELECT 1 FROM clans WHERE clan_id = NEW.from_location_id) THEN
        RAISE EXCEPTION 'item provenance from-location references unknown clan_id %', NEW.from_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.to_location_kind = 'CLAN_STORAGE'
       AND NOT EXISTS (SELECT 1 FROM clans WHERE clan_id = NEW.to_location_id) THEN
        RAISE EXCEPTION 'item provenance to-location references unknown clan_id %', NEW.to_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.from_location_kind = 'WAR_CUSTODY'
       AND NOT EXISTS (SELECT 1 FROM clan_wars WHERE war_id = NEW.from_location_id) THEN
        RAISE EXCEPTION 'item provenance from-location references unknown war_id %', NEW.from_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NEW.to_location_kind = 'WAR_CUSTODY'
       AND NOT EXISTS (SELECT 1 FROM clan_wars WHERE war_id = NEW.to_location_id) THEN
        RAISE EXCEPTION 'item provenance to-location references unknown war_id %', NEW.to_location_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;
