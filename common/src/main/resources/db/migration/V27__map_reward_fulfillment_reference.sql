ALTER TABLE map_reward_grants
    ADD COLUMN fulfillment_reference_id UUID;

ALTER TABLE map_reward_grants
    DROP CONSTRAINT map_reward_grants_fulfillment_shape_check;

ALTER TABLE map_reward_grants
    ADD CONSTRAINT map_reward_grants_fulfillment_shape_check CHECK (
        (
            status = 'PENDING'
            AND fulfillment_operation_id IS NULL
            AND fulfillment_reference_id IS NULL
            AND fulfilled_at IS NULL
        )
        OR
        (
            status = 'FULFILLED'
            AND fulfillment_operation_id IS NOT NULL
            AND fulfillment_reference_id IS NOT NULL
            AND fulfilled_at IS NOT NULL
        )
    );
