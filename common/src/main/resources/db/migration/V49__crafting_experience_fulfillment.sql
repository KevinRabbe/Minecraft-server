CREATE TABLE craft_experience_fulfillments (
    craft_id UUID PRIMARY KEY REFERENCES craft_records(craft_id) ON DELETE RESTRICT,
    xp_operation_id UUID NOT NULL UNIQUE,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION reject_craft_experience_fulfillment_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'craft_experience_fulfillments is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER craft_experience_fulfillments_append_only
BEFORE UPDATE OR DELETE
ON craft_experience_fulfillments
FOR EACH ROW
EXECUTE FUNCTION reject_craft_experience_fulfillment_mutation();
