package io.github.kevinrabbe.minecraftserver.common.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * Serializes simultaneous attempts for one logical operation_id inside the caller's transaction.
 *
 * <p>The 128-bit UUID is folded to PostgreSQL's 64-bit advisory-lock key. A hash collision can only serialize
 * unrelated operations; it cannot merge or corrupt their independent processed_operations identities.</p>
 */
public final class PostgresOperationLock {
    private PostgresOperationLock() {
    }

    public static void lock(Connection connection, UUID operationId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(operationId, "operationId");

        long lockKey = operationId.getMostSignificantBits() ^ operationId.getLeastSignificantBits();
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, lockKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("PostgreSQL did not acquire operation advisory lock");
                }
            }
        }
    }
}
