package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates durable pending commodity deliveries for already-earned game rewards. */
public final class CommodityDeliveryAuthority {
    private final DataSource dataSource;
    private final CommodityDefinitionResolver definitions;
    private final Clock clock;

    public CommodityDeliveryAuthority(DataSource dataSource, CommodityDefinitionResolver definitions) {
        this(dataSource, definitions, Clock.systemUTC());
    }

    public CommodityDeliveryAuthority(DataSource dataSource, CommodityDefinitionResolver definitions, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CommodityDeliverySnapshot createPending(
            UUID operationId,
            UUID playerId,
            String definitionId,
            long quantity
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        String canonicalDefinitionId = definitions.requireCommodity(definitionId);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<CommodityDeliverySnapshot> existing = findBySourceOperation(connection, operationId);
                if (existing.isPresent()) {
                    CommodityDeliverySnapshot previous = existing.orElseThrow();
                    if (!previous.playerId().equals(playerId)
                            || !previous.commodityDefinitionId().equals(canonicalDefinitionId)
                            || previous.quantity() != quantity) {
                        throw new BazaarException(
                                "operation_id reused with a different commodity delivery request: " + operationId
                        );
                    }
                    connection.commit();
                    return previous;
                }

                requirePlayer(connection, playerId);
                UUID deliveryId = UUID.randomUUID();
                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO pending_commodity_deliveries(
                            delivery_id,
                            player_id,
                            commodity_definition_id,
                            quantity,
                            source_operation_id,
                            status,
                            created_at
                        ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                        """)) {
                    statement.setObject(1, deliveryId);
                    statement.setObject(2, playerId);
                    statement.setString(3, canonicalDefinitionId);
                    statement.setLong(4, quantity);
                    statement.setObject(5, operationId);
                    statement.setTimestamp(6, Timestamp.from(now));
                    statement.executeUpdate();
                }
                CommodityDeliverySnapshot result = read(connection, deliveryId);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static Optional<CommodityDeliverySnapshot> findBySourceOperation(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT delivery_id,
                       player_id,
                       commodity_definition_id,
                       quantity,
                       source_operation_id,
                       status,
                       claim_operation_id,
                       created_at,
                       claimed_at
                FROM pending_commodity_deliveries
                WHERE source_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        }
    }

    private static CommodityDeliverySnapshot read(Connection connection, UUID deliveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT delivery_id,
                       player_id,
                       commodity_definition_id,
                       quantity,
                       source_operation_id,
                       status,
                       claim_operation_id,
                       created_at,
                       claimed_at
                FROM pending_commodity_deliveries
                WHERE delivery_id = ?
                """)) {
            statement.setObject(1, deliveryId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new BazaarException("Commodity delivery disappeared after creation: " + deliveryId);
                }
                return map(row);
            }
        }
    }

    private static CommodityDeliverySnapshot map(ResultSet row) throws SQLException {
        Timestamp claimedAt = row.getTimestamp("claimed_at");
        return new CommodityDeliverySnapshot(
                row.getObject("delivery_id", UUID.class),
                row.getObject("player_id", UUID.class),
                row.getString("commodity_definition_id"),
                row.getLong("quantity"),
                row.getObject("source_operation_id", UUID.class),
                CommodityDeliveryStatus.valueOf(row.getString("status")),
                row.getObject("claim_operation_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                claimedAt == null ? null : claimedAt.toInstant()
        );
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new BazaarException("Unknown player_id for commodity delivery: " + playerId);
                }
            }
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
