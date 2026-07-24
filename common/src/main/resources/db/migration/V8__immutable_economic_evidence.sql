ALTER TABLE processed_operations
    ADD CONSTRAINT processed_operations_type_not_blank
        CHECK (BTRIM(operation_type) <> '');

ALTER TABLE economic_ledger
    DROP CONSTRAINT economic_ledger_amount_check;

ALTER TABLE economic_ledger
    ADD CONSTRAINT economic_ledger_amount_check CHECK (amount > 0),
    ADD CONSTRAINT economic_ledger_asset_type_not_blank CHECK (BTRIM(asset_type) <> ''),
    ADD CONSTRAINT economic_ledger_asset_id_not_blank CHECK (BTRIM(asset_id) <> ''),
    ADD CONSTRAINT economic_ledger_reason_not_blank CHECK (BTRIM(reason) <> '');

CREATE OR REPLACE FUNCTION reject_processed_operation_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'processed_operations is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER processed_operations_append_only
BEFORE UPDATE OR DELETE
ON processed_operations
FOR EACH ROW
EXECUTE FUNCTION reject_processed_operation_mutation();

CREATE OR REPLACE FUNCTION reject_economic_ledger_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'economic_ledger is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER economic_ledger_append_only
BEFORE UPDATE OR DELETE
ON economic_ledger
FOR EACH ROW
EXECUTE FUNCTION reject_economic_ledger_mutation();
