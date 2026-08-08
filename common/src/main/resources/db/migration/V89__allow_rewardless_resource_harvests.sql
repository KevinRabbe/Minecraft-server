ALTER TABLE resource_harvest_fulfillments
    ALTER COLUMN commodity_delivery_id DROP NOT NULL;

ALTER TABLE resource_harvests
    ALTER COLUMN commodity_definition_id DROP NOT NULL,
    DROP CONSTRAINT resource_harvests_quantity_check,
    DROP CONSTRAINT resource_harvests_commodity_definition_check;

ALTER TABLE resource_harvests
    ADD CONSTRAINT resource_harvests_commodity_shape_check CHECK (
        (
            commodity_definition_id IS NULL
            AND commodity_quantity = 0
        )
        OR
        (
            commodity_definition_id IS NOT NULL
            AND commodity_definition_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'
            AND commodity_quantity > 0
        )
    );

ALTER TABLE resource_harvest_fulfillments
    ADD CONSTRAINT resource_harvest_fulfillments_reward_shape_check CHECK (
        commodity_delivery_id IS NOT NULL OR xp_operation_id IS NOT NULL OR completed_at IS NOT NULL
    );
