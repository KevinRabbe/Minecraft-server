package io.github.kevinrabbe.minecraftserver.common.verification;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only bounded reconciliation for state-coupled Map opening, encounter reservations, handoffs, and run lifecycle. */
public final class MapEncounterIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;

    private final DataSource dataSource;

    public MapEncounterIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            verifyStateCoupledOpenEvidence(connection, issues, maxIssues);
            verifyReservationEvidence(connection, issues, maxIssues);
            verifyHandoffEvidence(connection, issues, maxIssues);
            verifyStartedRunsHaveHandoff(connection, issues, maxIssues);
            verifyRunLifecycleEvidence(connection, issues, maxIssues);
            return List.copyOf(issues);
        }
    }

    private static void verifyStateCoupledOpenEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH mismatches AS (
                    SELECT e.open_operation_id::text AS subject_id
                    FROM map_open_player_state_evidence e
                    LEFT JOIN map_runs r ON r.run_id = e.run_id
                    LEFT JOIN player_sessions ps ON ps.network_session_id = e.session_id
                    LEFT JOIN player_state st ON st.player_id = r.opened_by_player_id
                    LEFT JOIN map_encounter_reservations mr ON mr.reservation_id = e.encounter_reservation_id
                    WHERE r.run_id IS NULL
                       OR r.open_operation_id IS DISTINCT FROM e.open_operation_id
                       OR ps.network_session_id IS NULL
                       OR ps.player_id IS DISTINCT FROM r.opened_by_player_id
                       OR e.player_state_version IS DISTINCT FROM e.expected_player_state_version + 1
                       OR ps.state_version < e.player_state_version
                       OR st.player_id IS NULL
                       OR st.state_version < e.player_state_version
                       OR BTRIM(e.backend_id) = ''
                       OR e.payload_sha256 !~ '^[0-9a-f]{64}$'
                       OR (
                            e.encounter_reservation_id IS NOT NULL
                            AND (
                                mr.reservation_id IS NULL
                                OR mr.open_operation_id IS DISTINCT FROM e.open_operation_id
                                OR mr.run_id IS DISTINCT FROM e.run_id
                                OR mr.player_id IS DISTINCT FROM r.opened_by_player_id
                                OR mr.status NOT IN ('BOUND', 'RELEASED')
                            )
                       )
                    UNION
                    SELECT mr.open_operation_id::text
                    FROM map_encounter_reservations mr
                    LEFT JOIN map_open_player_state_evidence e
                      ON e.encounter_reservation_id = mr.reservation_id
                    WHERE mr.run_id IS NOT NULL
                      AND mr.status IN ('BOUND', 'RELEASED')
                      AND (
                        e.open_operation_id IS NULL
                        OR e.open_operation_id IS DISTINCT FROM mr.open_operation_id
                        OR e.run_id IS DISTINCT FROM mr.run_id
                      )
                )
                SELECT subject_id
                FROM mismatches
                ORDER BY subject_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_OPEN_PLAYER_STATE_EVIDENCE_MISMATCH",
                            rows.getString("subject_id"),
                            "State-coupled Map opening does not reconcile to its run, session/state version, or encounter reservation"
                    ));
                }
            }
        }
    }

    private static void verifyReservationEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT mr.reservation_id
                FROM map_encounter_reservations mr
                LEFT JOIN zone_instances z ON z.instance_id = mr.target_instance_id
                LEFT JOIN map_runs r ON r.run_id = mr.run_id
                WHERE z.instance_id IS NULL
                   OR z.backend_id IS DISTINCT FROM mr.target_backend_id
                   OR z.zone_id IS DISTINCT FROM mr.target_zone_id
                   OR z.template_version IS DISTINCT FROM mr.target_template_version
                   OR CASE mr.status
                        WHEN 'RESERVED' THEN mr.run_id IS NOT NULL OR mr.state_version <> 0
                        WHEN 'BOUND' THEN mr.run_id IS NULL OR mr.state_version <> 1
                        WHEN 'EXPIRED' THEN mr.run_id IS NOT NULL OR mr.state_version <> 1
                        WHEN 'RELEASED' THEN (
                            (mr.run_id IS NULL AND mr.state_version <> 1)
                            OR (mr.run_id IS NOT NULL AND mr.state_version <> 2)
                        )
                        ELSE TRUE
                      END
                   OR (
                        mr.run_id IS NOT NULL
                        AND (
                            r.run_id IS NULL
                            OR r.source_map_item_id IS DISTINCT FROM mr.source_map_item_id
                            OR r.opened_by_player_id IS DISTINCT FROM mr.player_id
                            OR r.open_operation_id IS DISTINCT FROM mr.open_operation_id
                        )
                   )
                   OR (
                        mr.status = 'RELEASED'
                        AND mr.run_id IS NOT NULL
                        AND r.status NOT IN ('COMPLETED', 'FAILED')
                   )
                ORDER BY mr.reservation_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID reservationId = rows.getObject("reservation_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_ENCOUNTER_RESERVATION_EVIDENCE_MISMATCH",
                            reservationId.toString(),
                            "Map encounter reservation does not reconcile to its target identity, lifecycle version, or bound run"
                    ));
                }
            }
        }
    }

    private static void verifyHandoffEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT h.run_id
                FROM map_encounter_handoffs h
                LEFT JOIN map_encounter_reservations mr ON mr.reservation_id = h.reservation_id
                LEFT JOIN map_runs r ON r.run_id = h.run_id
                LEFT JOIN transfer_tickets t ON t.transfer_id = h.transfer_id
                WHERE mr.reservation_id IS NULL
                   OR mr.run_id IS DISTINCT FROM h.run_id
                   OR mr.player_id IS DISTINCT FROM h.player_id
                   OR mr.target_instance_id IS DISTINCT FROM h.target_instance_id
                   OR mr.target_backend_id IS DISTINCT FROM h.target_backend_id
                   OR mr.status NOT IN ('BOUND', 'RELEASED')
                   OR r.run_id IS NULL
                   OR r.opened_by_player_id IS DISTINCT FROM h.player_id
                   OR t.transfer_id IS NULL
                   OR t.player_id IS DISTINCT FROM h.player_id
                   OR t.target_zone_id IS DISTINCT FROM mr.target_zone_id
                   OR t.target_backend_id IS DISTINCT FROM h.target_backend_id
                   OR t.target_instance_id IS DISTINCT FROM h.target_instance_id
                   OR t.pinned_instance IS DISTINCT FROM TRUE
                ORDER BY h.run_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID runId = rows.getObject("run_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_ENCOUNTER_HANDOFF_EVIDENCE_MISMATCH",
                            runId.toString(),
                            "Map encounter handoff does not reconcile to its reservation, run, or pinned transfer identity"
                    ));
                }
            }
        }
    }

    private static void verifyStartedRunsHaveHandoff(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.run_id
                FROM map_runs r
                JOIN map_encounter_reservations mr ON mr.run_id = r.run_id
                LEFT JOIN map_encounter_handoffs h ON h.run_id = r.run_id
                WHERE r.start_operation_id IS NOT NULL
                  AND h.run_id IS NULL
                ORDER BY r.run_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID runId = rows.getObject("run_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_ENCOUNTER_HANDOFF_EVIDENCE_MISMATCH",
                            runId.toString(),
                            "Reserved Map run started without the required persisted encounter handoff"
                    ));
                }
            }
        }
    }

    private static void verifyRunLifecycleEvidence(
            Connection connection,
            List<IntegrityIssue> issues,
            int maxIssues
    ) throws SQLException {
        int remaining = remaining(issues, maxIssues);
        if (remaining == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.run_id
                FROM map_runs r
                LEFT JOIN map_clears c ON c.run_id = r.run_id
                WHERE (
                    r.status = 'CREATED'
                    AND (
                        r.state_version <> 0
                        OR r.started_at IS NOT NULL
                        OR r.finished_at IS NOT NULL
                        OR r.start_operation_id IS NOT NULL
                        OR r.start_expected_state_version IS NOT NULL
                        OR r.start_reason IS NOT NULL
                        OR r.terminal_operation_id IS NOT NULL
                        OR r.terminal_expected_state_version IS NOT NULL
                        OR r.terminal_reason IS NOT NULL
                        OR c.run_id IS NOT NULL
                    )
                ) OR (
                    r.status = 'ACTIVE'
                    AND (
                        r.started_at IS NULL
                        OR r.finished_at IS NOT NULL
                        OR r.start_operation_id IS NULL
                        OR r.start_expected_state_version IS NULL
                        OR r.start_reason IS NULL
                        OR r.start_reason !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                        OR r.state_version IS DISTINCT FROM r.start_expected_state_version + 1
                        OR r.terminal_operation_id IS NOT NULL
                        OR r.terminal_expected_state_version IS NOT NULL
                        OR r.terminal_reason IS NOT NULL
                        OR c.run_id IS NOT NULL
                    )
                ) OR (
                    r.status = 'COMPLETED'
                    AND (
                        r.started_at IS NULL
                        OR r.finished_at IS NULL
                        OR r.start_operation_id IS NULL
                        OR r.start_expected_state_version IS NULL
                        OR r.start_reason IS NULL
                        OR r.start_reason !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                        OR r.terminal_operation_id IS NULL
                        OR r.terminal_expected_state_version IS NULL
                        OR r.terminal_reason IS NULL
                        OR r.terminal_reason !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                        OR r.start_expected_state_version + 1 IS DISTINCT FROM r.terminal_expected_state_version
                        OR r.state_version IS DISTINCT FROM r.terminal_expected_state_version + 1
                        OR c.run_id IS NULL
                    )
                ) OR (
                    r.status = 'FAILED'
                    AND (
                        r.finished_at IS NULL
                        OR r.terminal_operation_id IS NULL
                        OR r.terminal_expected_state_version IS NULL
                        OR r.terminal_reason IS NULL
                        OR r.terminal_reason !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                        OR r.state_version IS DISTINCT FROM r.terminal_expected_state_version + 1
                        OR c.run_id IS NOT NULL
                        OR (
                            r.start_operation_id IS NULL
                            AND (
                                r.started_at IS NOT NULL
                                OR r.start_expected_state_version IS NOT NULL
                                OR r.start_reason IS NOT NULL
                                OR r.terminal_expected_state_version <> 0
                            )
                        )
                        OR (
                            r.start_operation_id IS NOT NULL
                            AND (
                                r.started_at IS NULL
                                OR r.start_expected_state_version IS NULL
                                OR r.start_reason IS NULL
                                OR r.start_reason !~ '^[a-z0-9][a-z0-9._-]{0,95}$'
                                OR r.start_expected_state_version + 1 IS DISTINCT FROM r.terminal_expected_state_version
                            )
                        )
                    )
                )
                ORDER BY r.run_id
                LIMIT ?
                """)) {
            statement.setInt(1, remaining);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID runId = rows.getObject("run_id", UUID.class);
                    issues.add(new IntegrityIssue(
                            IntegritySeverity.CRITICAL,
                            "MAP_RUN_LIFECYCLE_EVIDENCE_MISMATCH",
                            runId.toString(),
                            "Map run lifecycle state/version evidence is not a valid CREATED/ACTIVE/COMPLETED/FAILED transition chain"
                    ));
                }
            }
        }
    }

    private static int remaining(List<IntegrityIssue> issues, int maxIssues) {
        return Math.max(0, maxIssues - issues.size());
    }
}
