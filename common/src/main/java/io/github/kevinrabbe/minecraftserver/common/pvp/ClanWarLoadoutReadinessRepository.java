package io.github.kevinrabbe.minecraftserver.common.pvp;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Finalization authority for per-player Clan-War loadout selection.
 *
 * <p>Confirmation does not assert any particular item count or kit shape; those are tuning/content decisions. It only
 * declares that the roster player's current WAR_CUSTODY selection is final. V69 invalidates the declaration whenever
 * that player's custody rows change.</p>
 */
public final class ClanWarLoadoutReadinessRepository {
    private static final String CONFIRM_OPERATION = "CLAN_WAR_LOADOUT_CONFIRM";

    private final DataSource dataSource;

    public ClanWarLoadoutReadinessRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ClanWarLoadoutConfirmation confirm(UUID operationId, UUID warId, UUID playerId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(warId, "warId");
        Objects.requireNonNull(playerId, "playerId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ClanWarLoadoutConfirmation> replay = replay(connection, operationId, warId, playerId);
                if (replay.isPresent()) {
                    connection.commit();
                    return replay.orElseThrow();
                }

                requireRosterLockedPlayer(connection, warId, playerId);
                ClanWarLoadoutConfirmation confirmation = upsertConfirmation(connection, warId, playerId);
                insertProcessed(connection, operationId, confirmation);
                connection.commit();
                return confirmation;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public Optional<ClanWarLoadoutConfirmation> load(UUID warId, UUID playerId) throws SQLException {
        Objects.requireNonNull(warId, "warId");
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT war_id, player_id, confirmed_at
                     FROM clan_war_loadout_confirmations
                     WHERE war_id = ? AND player_id = ?
                     """)) {
            statement.setObject(1, warId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readConfirmation(row)) : Optional.empty();
            }
        }
    }

    public List<ClanWarLoadoutConfirmation> list(UUID warId) throws SQLException {
        Objects.requireNonNull(warId, "warId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT war_id, player_id, confirmed_at
                     FROM clan_war_loadout_confirmations
                     WHERE war_id = ?
                     ORDER BY player_id ASC
                     """)) {
            statement.setObject(1, warId);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<ClanWarLoadoutConfirmation> result = new ArrayList<>();
                while (rows.next()) result.add(readConfirmation(rows));
                return List.copyOf(result);
            }
        }
    }

    private static void requireRosterLockedPlayer(Connection connection, UUID warId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM clan_wars war
                JOIN clan_war_rosters roster
                  ON roster.war_id = war.war_id
                 AND roster.player_id = ?
                 AND roster.released_at IS NULL
                WHERE war.war_id = ?
                  AND war.status = 'ROSTER_LOCKED'
                FOR UPDATE OF war, roster
                """)) {
            statement.setObject(1, playerId);
            statement.setObject(2, warId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanWarException(
                            "loadout may be finalized only by a live roster player while the war is ROSTER_LOCKED"
                    );
                }
            }
        }
    }

    private static ClanWarLoadoutConfirmation upsertConfirmation(
            Connection connection,
            UUID warId,
            UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO clan_war_loadout_confirmations(war_id, player_id, confirmed_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (war_id, player_id)
                DO UPDATE SET confirmed_at = EXCLUDED.confirmed_at
                RETURNING war_id, player_id, confirmed_at
                """)) {
            statement.setObject(1, warId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Clan-War loadout confirmation returned no row");
                return readConfirmation(row);
            }
        }
    }

    private static Optional<ClanWarLoadoutConfirmation> replay(
            Connection connection,
            UUID operationId,
            UUID warId,
            UUID playerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'war_id' AS war_id,
                       result ->> 'player_id' AS player_id,
                       result ->> 'confirmed_at' AS confirmed_at
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                String operationType = row.getString("operation_type");
                if (!CONFIRM_OPERATION.equals(operationType)) {
                    throw new ClanWarException("operation_id already belongs to " + operationType);
                }
                UUID replayWarId = UUID.fromString(row.getString("war_id"));
                UUID replayPlayerId = UUID.fromString(row.getString("player_id"));
                if (!warId.equals(replayWarId) || !playerId.equals(replayPlayerId)) {
                    throw new ClanWarException("operation_id reused with a different Clan-War loadout confirmation");
                }
                return Optional.of(new ClanWarLoadoutConfirmation(
                        replayWarId,
                        replayPlayerId,
                        Instant.parse(row.getString("confirmed_at"))
                ));
            }
        }
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            ClanWarLoadoutConfirmation confirmation
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (
                    ?,
                    ?,
                    jsonb_build_object(
                        'war_id', CAST(? AS TEXT),
                        'player_id', CAST(? AS TEXT),
                        'confirmed_at', CAST(? AS TEXT)
                    )
                )
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, CONFIRM_OPERATION);
            statement.setObject(3, confirmation.warId());
            statement.setObject(4, confirmation.playerId());
            statement.setString(5, confirmation.confirmedAt().toString());
            statement.executeUpdate();
        }
    }

    private static ClanWarLoadoutConfirmation readConfirmation(ResultSet row) throws SQLException {
        Timestamp confirmedAt = row.getTimestamp("confirmed_at");
        return new ClanWarLoadoutConfirmation(
                row.getObject("war_id", UUID.class),
                row.getObject("player_id", UUID.class),
                confirmedAt.toInstant()
        );
    }

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
