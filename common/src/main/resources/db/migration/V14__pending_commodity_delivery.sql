CREATE TABLE pending_commodity_deliveries (
    delivery_id UUID PRIMARY KEY,
    recipient_player_id UUID NOT NULL REFERENCES players(player_id) ON DELETE RESTRICT,
    definition_id TEXT NOT NULL,
    total_quantity BIGINT NOT NULL,
    remaining_quantity BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    issue_operation_id UUID NOT NULL UNIQUE,
    issue_reason TEXT NOT NULL,
    last_claim_operation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    CONSTRAINT pending_commodity_definition_not_blank CHECK (BTRIM(definition_id) <> ''),
    CONSTRAINT pending_commodity_total_positive CHECK (total_quantity > 0),
    CONSTRAINT pending_commodity_remaining_range CHECK (
        remaining_quantity >= 0 AND remaining_quantity <= total_quantity
    ),
    CONSTRAINT pending_commodity_status_check CHECK (status IN ('PENDING', 'CLAIMED')),
    CONSTRAINT pending_commodity_reason_not_blank CHECK (BTRIM(issue_reason) <> ''),
    CONSTRAINT pending_commodity_status_shape_check CHECK (
        (status = 'PENDING' AND remaining_quantity > 0 AND claimed_at IS NULL)
        OR
        (status = 'CLAIMED' AND remaining_quantity = 0 AND claimed_at IS NOT NULL)
    )
);

CREATE INDEX pending_commodity_deliveries_recipient_pending_idx
    ON pending_commodity_deliveries(recipient_player_id, created_at, delivery_id)
    WHERE status = 'PENDING';

CREATE TABLE pending_commodity_claims (
    claim_operation_id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES pending_commodity_deliveries(delivery_id) ON DELETE RESTRICT,
    session_id UUID NOT NULL REFERENCES player_sessions(network_session_id) ON DELETE RESTRICT,
    backend_id TEXT NOT NULL,
    claim_quantity BIGINT NOT NULL,
    remaining_before BIGINT NOT NULL,
    remaining_after BIGINT NOT NULL,
    player_state_version BIGINT NOT NULL,
    payload_sha256 TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pending_commodity_claim_quantity_positive CHECK (claim_quantity > 0),
    CONSTRAINT pending_commodity_claim_remaining_check CHECK (
        remaining_before > 0
        AND remaining_after >= 0
        AND remaining_before > remaining_after
        AND remaining_before - remaining_after = claim_quantity
    ),
    CONSTRAINT pending_commodity_claim_state_version_check CHECK (player_state_version >= 0),
    CONSTRAINT pending_commodity_claim_backend_not_blank CHECK (BTRIM(backend_id) <> ''),
    CONSTRAINT pending_commodity_claim_payload_hash_check CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT pending_commodity_claim_reason_not_blank CHECK (BTRIM(reason) <> '')
);

ALTER TABLE pending_commodity_deliveries
    ADD CONSTRAINT pending_commodity_last_claim_fk
    FOREIGN KEY (last_claim_operation_id)
    REFERENCES pending_commodity_claims(claim_operation_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE OR REPLACE FUNCTION validate_pending_commodity_delivery_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING'
           OR NEW.remaining_quantity <> NEW.total_quantity
           OR NEW.last_claim_operation_id IS NOT NULL
           OR NEW.claimed_at IS NOT NULL THEN
            RAISE EXCEPTION 'new commodity delivery must start fully PENDING and unclaimed'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.delivery_id IS DISTINCT FROM OLD.delivery_id
       OR NEW.recipient_player_id IS DISTINCT FROM OLD.recipient_player_id
       OR NEW.definition_id IS DISTINCT FROM OLD.definition_id
       OR NEW.total_quantity IS DISTINCT FROM OLD.total_quantity
       OR NEW.issue_operation_id IS DISTINCT FROM OLD.issue_operation_id
       OR NEW.issue_reason IS DISTINCT FROM OLD.issue_reason
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'commodity delivery issuance fields are immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status <> 'PENDING'
       OR NEW.remaining_quantity >= OLD.remaining_quantity
       OR NEW.last_claim_operation_id IS NULL
       OR NEW.last_claim_operation_id IS NOT DISTINCT FROM OLD.last_claim_operation_id THEN
        RAISE EXCEPTION 'commodity delivery update must be a new partial/final claim'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.remaining_quantity = 0 THEN
        IF NEW.status <> 'CLAIMED' OR NEW.claimed_at IS NULL THEN
            RAISE EXCEPTION 'final commodity claim must mark delivery CLAIMED'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        IF NEW.status <> 'PENDING' OR NEW.claimed_at IS NOT NULL THEN
            RAISE EXCEPTION 'partial commodity claim must keep delivery PENDING'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER pending_commodity_deliveries_validate_transition
BEFORE INSERT OR UPDATE
ON pending_commodity_deliveries
FOR EACH ROW
EXECUTE FUNCTION validate_pending_commodity_delivery_transition();

CREATE OR REPLACE FUNCTION reject_pending_commodity_claim_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'pending_commodity_claims is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER pending_commodity_claims_append_only
BEFORE UPDATE OR DELETE
ON pending_commodity_claims
FOR EACH ROW
EXECUTE FUNCTION reject_pending_commodity_claim_mutation();

CREATE OR REPLACE FUNCTION validate_pending_commodity_claim_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pending_commodity_claims c
        JOIN player_sessions s ON s.network_session_id = c.session_id
        WHERE c.claim_operation_id = NEW.last_claim_operation_id
          AND c.delivery_id = NEW.delivery_id
          AND c.claim_quantity = OLD.remaining_quantity - NEW.remaining_quantity
          AND c.remaining_before = OLD.remaining_quantity
          AND c.remaining_after = NEW.remaining_quantity
          AND s.player_id = NEW.recipient_player_id
          AND s.owner_backend_id = c.backend_id
          AND s.state_version = c.player_state_version
    ) THEN
        RAISE EXCEPTION 'commodity delivery % claim has no matching fenced player-state evidence', NEW.delivery_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER pending_commodity_deliveries_require_claim_evidence
AFTER UPDATE OF remaining_quantity, status, last_claim_operation_id, claimed_at
ON pending_commodity_deliveries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_pending_commodity_claim_evidence();
