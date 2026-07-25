package io.github.kevinrabbe.minecraftserver.common.economy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/**
 * Player-facing secure-trade confirmation authority.
 *
 * <p>Unlike the legacy trusted-caller confirmation method, this boundary requires the exact revision the player
 * inspected. The revision check and confirmation happen under the same PostgreSQL row lock, so an offer cannot change
 * between inspection intent and confirmation without the confirmation failing closed.</p>
 */
public final class SecureTradeConfirmationRepository {
    private final DataSource dataSource;

    public SecureTradeConfirmationRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public SecureTradeSnapshot confirmViewedRevision(
            UUID tradeId,
            UUID playerId,
            long expectedRevision
    ) throws SQLException {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be >= 0");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                requireParticipant(trade, playerId);
                if (trade.status() == SecureTradeStatus.SETTLED || trade.status() == SecureTradeStatus.CANCELLED) {
                    throw new SecureTradeException("terminal secure trade cannot be confirmed: " + tradeId);
                }
                if (trade.revision() != expectedRevision) {
                    throw new SecureTradeException(
                            "secure trade changed after it was viewed: expected revision " + expectedRevision
                                    + " but current revision is " + trade.revision()
                    );
                }

                // LOCKED is an idempotent success for the exact viewed revision. This lets the Paper bridge retry
                // terminal settlement after a process/network failure between locking and settlement.
                if (trade.status() == SecureTradeStatus.LOCKED) {
                    connection.commit();
                    return trade;
                }

                boolean playerA = trade.playerAId().equals(playerId);
                Long ownConfirmation = playerA
                        ? trade.playerAConfirmedRevision()
                        : trade.playerBConfirmedRevision();
                if (!Long.valueOf(expectedRevision).equals(ownConfirmation)) {
                    try (PreparedStatement statement = connection.prepareStatement(playerA ? """
                            UPDATE secure_trades
                            SET player_a_confirmed_revision = ?, updated_at = NOW()
                            WHERE trade_id = ? AND status = 'OPEN' AND revision = ?
                            """ : """
                            UPDATE secure_trades
                            SET player_b_confirmed_revision = ?, updated_at = NOW()
                            WHERE trade_id = ? AND status = 'OPEN' AND revision = ?
                            """)) {
                        statement.setLong(1, expectedRevision);
                        statement.setObject(2, tradeId);
                        statement.setLong(3, expectedRevision);
                        if (statement.executeUpdate() != 1) {
                            throw new SecureTradeException("secure trade changed concurrently during confirmation");
                        }
                    }
                }

                SecureTradeSnapshot afterConfirmation = readTrade(connection, tradeId, true);
                if (Long.valueOf(expectedRevision).equals(afterConfirmation.playerAConfirmedRevision())
                        && Long.valueOf(expectedRevision).equals(afterConfirmation.playerBConfirmedRevision())) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE secure_trades
                            SET status = 'LOCKED', updated_at = NOW()
                            WHERE trade_id = ?
                              AND status = 'OPEN'
                              AND revision = ?
                              AND player_a_confirmed_revision = ?
                              AND player_b_confirmed_revision = ?
                            """)) {
                        statement.setObject(1, tradeId);
                        statement.setLong(2, expectedRevision);
                        statement.setLong(3, expectedRevision);
                        statement.setLong(4, expectedRevision);
                        if (statement.executeUpdate() != 1) {
                            throw new SecureTradeException("secure trade changed concurrently while locking");
                        }
                    }
                }

                SecureTradeSnapshot result = readTrade(connection, tradeId, false);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static SecureTradeSnapshot readTrade(Connection connection, UUID tradeId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_a_id,
                       player_b_id,
                       status,
                       revision,
                       player_a_confirmed_revision,
                       player_b_confirmed_revision,
                       created_at,
                       updated_at,
                       settled_at
                FROM secure_trades
                WHERE trade_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tradeId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Unknown secure trade: " + tradeId);
                }
                Timestamp settledAt = row.getTimestamp("settled_at");
                return new SecureTradeSnapshot(
                        tradeId,
                        row.getObject("player_a_id", UUID.class),
                        row.getObject("player_b_id", UUID.class),
                        SecureTradeStatus.valueOf(row.getString("status")),
                        row.getLong("revision"),
                        row.getObject("player_a_confirmed_revision", Long.class),
                        row.getObject("player_b_confirmed_revision", Long.class),
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant(),
                        settledAt == null ? null : settledAt.toInstant()
                );
            }
        }
    }

    private static void requireParticipant(SecureTradeSnapshot trade, UUID playerId) {
        if (!trade.participant(playerId)) {
            throw new SecureTradeException("player is not a secure-trade participant: " + playerId);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
