-- Isolated 1.8.9 runtimes may execute authorized matches, but never settle durable ratings/items directly.
-- Common/PostgreSQL authority assigns one activity to one backend, leases execution, accepts one bounded outcome report,
-- and records when trusted common settlement/recovery has closed the execution.

CREATE TABLE competitive_executions (
    execution_id UUID PRIMARY KEY,
    assignment_operation_id UUID NOT NULL UNIQUE,
    activity_kind TEXT NOT NULL,
    activity_id UUID NOT NULL,
    backend_id TEXT NOT NULL REFERENCES backends(backend_id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'ASSIGNED',
    lease_expires_at TIMESTAMPTZ NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    close_reason TEXT,
    settlement_operation_id UUID UNIQUE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    CONSTRAINT competitive_executions_activity_kind_check CHECK (
        activity_kind IN ('RANKED_ARENA', 'CLAN_WAR')
    ),
    CONSTRAINT competitive_executions_status_check CHECK (
        status IN ('ASSIGNED', 'ACTIVE', 'CLOSED')
    ),
    CONSTRAINT competitive_executions_close_reason_check CHECK (
        close_reason IS NULL OR close_reason IN ('SETTLED', 'FAILED')
    ),
    CONSTRAINT competitive_executions_state_version_check CHECK (state_version >= 0),
    CONSTRAINT competitive_executions_activity_unique UNIQUE (activity_kind, activity_id),
    CONSTRAINT competitive_executions_lifecycle_shape_check CHECK (
        (
            status = 'ASSIGNED'
            AND activated_at IS NULL
            AND close_reason IS NULL
            AND settlement_operation_id IS NULL
            AND closed_at IS NULL
        )
        OR
        (
            status = 'ACTIVE'
            AND activated_at IS NOT NULL
            AND close_reason IS NULL
            AND settlement_operation_id IS NULL
            AND closed_at IS NULL
        )
        OR
        (
            status = 'CLOSED'
            AND close_reason IS NOT NULL
            AND settlement_operation_id IS NOT NULL
            AND closed_at IS NOT NULL
        )
    )
);

CREATE INDEX competitive_executions_backend_live_idx
    ON competitive_executions(backend_id, lease_expires_at, execution_id)
    WHERE status IN ('ASSIGNED', 'ACTIVE');

CREATE TABLE competitive_result_reports (
    report_id UUID PRIMARY KEY,
    report_operation_id UUID NOT NULL UNIQUE,
    execution_id UUID NOT NULL UNIQUE REFERENCES competitive_executions(execution_id) ON DELETE RESTRICT,
    backend_id TEXT NOT NULL REFERENCES backends(backend_id) ON DELETE RESTRICT,
    report_kind TEXT NOT NULL,
    winner_id UUID,
    status TEXT NOT NULL DEFAULT 'PENDING',
    settlement_operation_id UUID UNIQUE,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT competitive_result_reports_kind_check CHECK (
        report_kind IN ('WINNER', 'FAILURE')
    ),
    CONSTRAINT competitive_result_reports_status_check CHECK (
        status IN ('PENDING', 'APPLIED')
    ),
    CONSTRAINT competitive_result_reports_winner_shape_check CHECK (
        (report_kind = 'WINNER' AND winner_id IS NOT NULL)
        OR (report_kind = 'FAILURE' AND winner_id IS NULL)
    ),
    CONSTRAINT competitive_result_reports_processing_shape_check CHECK (
        (status = 'PENDING' AND settlement_operation_id IS NULL AND processed_at IS NULL)
        OR (status = 'APPLIED' AND settlement_operation_id IS NOT NULL AND processed_at IS NOT NULL)
    )
);

CREATE INDEX competitive_result_reports_pending_idx
    ON competitive_result_reports(submitted_at, report_id)
    WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION validate_competitive_execution_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.execution_id IS DISTINCT FROM OLD.execution_id
       OR NEW.assignment_operation_id IS DISTINCT FROM OLD.assignment_operation_id
       OR NEW.activity_kind IS DISTINCT FROM OLD.activity_kind
       OR NEW.activity_id IS DISTINCT FROM OLD.activity_id
       OR NEW.backend_id IS DISTINCT FROM OLD.backend_id
       OR NEW.assigned_at IS DISTINCT FROM OLD.assigned_at THEN
        RAISE EXCEPTION 'competitive execution identity/assignment is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'closed competitive execution is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.state_version <> OLD.state_version + 1 THEN
        RAISE EXCEPTION 'competitive execution state_version must advance exactly once'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.status = OLD.status THEN
        IF NEW.status NOT IN ('ASSIGNED', 'ACTIVE')
           OR NEW.lease_expires_at <= OLD.lease_expires_at
           OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
           OR NEW.close_reason IS DISTINCT FROM OLD.close_reason
           OR NEW.settlement_operation_id IS DISTINCT FROM OLD.settlement_operation_id
           OR NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
            RAISE EXCEPTION 'live competitive execution same-state update must only extend its lease'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'ASSIGNED' AND NEW.status = 'ACTIVE' THEN
        IF NEW.activated_at IS NULL OR NEW.lease_expires_at <= OLD.lease_expires_at THEN
            RAISE EXCEPTION 'competitive activation requires activation time and extended lease'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('ASSIGNED', 'ACTIVE') AND NEW.status = 'CLOSED' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid competitive execution transition % -> %', OLD.status, NEW.status
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER competitive_executions_validate_transition
BEFORE UPDATE
ON competitive_executions
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_execution_transition();

CREATE OR REPLACE FUNCTION validate_competitive_result_report_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    execution_row competitive_executions%ROWTYPE;
    ranked_row ranked_matches%ROWTYPE;
    war_row clan_wars%ROWTYPE;
BEGIN
    SELECT * INTO execution_row
    FROM competitive_executions
    WHERE execution_id = NEW.execution_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown competitive execution %', NEW.execution_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF execution_row.status <> 'ACTIVE'
       OR execution_row.backend_id IS DISTINCT FROM NEW.backend_id
       OR execution_row.lease_expires_at <= NOW() THEN
        RAISE EXCEPTION 'competitive execution is not reportable by backend %', NEW.backend_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF execution_row.activity_kind = 'RANKED_ARENA' THEN
        SELECT * INTO ranked_row FROM ranked_matches WHERE match_id = execution_row.activity_id;
        IF NOT FOUND OR ranked_row.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'ranked activity is not active for execution %', NEW.execution_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.report_kind = 'WINNER'
           AND NEW.winner_id NOT IN (ranked_row.player_a_id, ranked_row.player_b_id) THEN
            RAISE EXCEPTION 'ranked report winner is not a match participant'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF execution_row.activity_kind = 'CLAN_WAR' THEN
        SELECT * INTO war_row FROM clan_wars WHERE war_id = execution_row.activity_id;
        IF NOT FOUND OR war_row.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'clan-war activity is not active for execution %', NEW.execution_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF NEW.report_kind = 'WINNER'
           AND NEW.winner_id NOT IN (war_row.challenger_clan_id, war_row.defender_clan_id) THEN
            RAISE EXCEPTION 'clan-war report winner is not a war participant'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'unknown competitive activity kind %', execution_row.activity_kind
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_result_reports_validate_insert
BEFORE INSERT
ON competitive_result_reports
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_result_report_insert();

CREATE OR REPLACE FUNCTION validate_competitive_result_report_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.report_id IS DISTINCT FROM OLD.report_id
       OR NEW.report_operation_id IS DISTINCT FROM OLD.report_operation_id
       OR NEW.execution_id IS DISTINCT FROM OLD.execution_id
       OR NEW.backend_id IS DISTINCT FROM OLD.backend_id
       OR NEW.report_kind IS DISTINCT FROM OLD.report_kind
       OR NEW.winner_id IS DISTINCT FROM OLD.winner_id
       OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
        RAISE EXCEPTION 'competitive result report identity/payload is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'APPLIED' THEN
        RAISE EXCEPTION 'applied competitive result report is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status <> 'PENDING' OR NEW.status <> 'APPLIED'
       OR NEW.settlement_operation_id IS NULL
       OR NEW.processed_at IS NULL THEN
        RAISE EXCEPTION 'competitive result report may only transition PENDING -> APPLIED'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER competitive_result_reports_validate_update
BEFORE UPDATE
ON competitive_result_reports
FOR EACH ROW
EXECUTE FUNCTION validate_competitive_result_report_update();

CREATE OR REPLACE FUNCTION reject_competitive_result_report_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'competitive_result_reports is append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER competitive_result_reports_append_only
BEFORE DELETE
ON competitive_result_reports
FOR EACH ROW
EXECUTE FUNCTION reject_competitive_result_report_delete();
