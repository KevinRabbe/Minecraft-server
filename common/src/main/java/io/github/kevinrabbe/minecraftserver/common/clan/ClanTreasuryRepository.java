package io.github.kevinrabbe.minecraftserver.common.clan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Protected clan Coin custody with role-gated exactly-once transfers. */
public final class ClanTreasuryRepository {
    private static final String DEPOSIT_OPERATION = "CLAN_TREASURY_DEPOSIT";
    private static final String WITHDRAW_OPERATION = "CLAN_TREASURY_WITHDRAW";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public ClanTreasuryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ClanTreasurySnapshot load(UUID clanId) throws SQLException {
        Objects.requireNonNull(clanId, "clanId");
        try (Connection connection = dataSource.getConnection()) {
            return readTreasury(connection, clanId, false);
        }
    }

    public ClanTreasuryTransferResult deposit(
            UUID operationId,
            UUID clanId,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(playerId, "playerId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0");
        }
        String normalizedReason = requireReason(reason);
        return transfer(operationId, clanId, playerId, amountMinor, normalizedReason, true);
    }

    public ClanTreasuryTransferResult withdraw(
            UUID operationId,
            UUID clanId,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(clanId, "clanId");
        Objects.requireNonNull(playerId, "playerId");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0");
        }
        String normalizedReason = requireReason(reason);
        return transfer(operationId, clanId, playerId, amountMinor, normalizedReason, false);
    }

    private ClanTreasuryTransferResult transfer(
            UUID operationId,
            UUID clanId,
            UUID playerId,
            long amountMinor,
            String reason,
            boolean deposit
    ) throws SQLException {
        String operationType = deposit ? DEPOSIT_OPERATION : WITHDRAW_OPERATION;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), operationType, operationId);
                    requireUuid(data, "clan_id", clanId, operationId);
                    requireUuid(data, "player_id", playerId, operationId);
                    requireLong(data, "amount_minor", amountMinor, operationId);
                    requireString(data, "reason", reason, operationId);
                    ClanTreasuryTransferResult result = resultFrom(data.get("result"));
                    connection.commit();
                    return result;
                }

                lockClan(connection, clanId);
                ClanRole role = lockMemberRole(connection, clanId, playerId);
                if (!deposit && role != ClanRole.LEADER && role != ClanRole.OFFICER) {
                    throw new ClanAssetException("clan treasury withdrawal requires LEADER or OFFICER role");
                }
                LockedWallet wallet = lockWallet(connection, playerId);
                ClanTreasurySnapshot treasury = readTreasury(connection, clanId, true);

                long nextWalletBalance;
                long nextTreasuryBalance;
                if (deposit) {
                    if (wallet.balanceMinor() < amountMinor) {
                        throw new ClanAssetException("insufficient player Coin balance for clan treasury deposit");
                    }
                    nextWalletBalance = wallet.balanceMinor() - amountMinor;
                    nextTreasuryBalance = addExact(
                            treasury.balanceMinor(), amountMinor, "clan treasury balance overflow"
                    );
                } else {
                    if (treasury.balanceMinor() < amountMinor) {
                        throw new ClanAssetException("insufficient clan treasury balance");
                    }
                    nextTreasuryBalance = treasury.balanceMinor() - amountMinor;
                    nextWalletBalance = addExact(
                            wallet.balanceMinor(), amountMinor, "player wallet balance overflow"
                    );
                }
                long nextWalletVersion = increment(wallet.stateVersion(), "wallet", playerId);
                long nextTreasuryVersion = increment(treasury.stateVersion(), "clan treasury", clanId);
                updateWallet(
                        connection,
                        playerId,
                        wallet.stateVersion(),
                        nextWalletBalance,
                        nextWalletVersion
                );
                ClanTreasurySnapshot updatedTreasury = updateTreasury(
                        connection,
                        clanId,
                        treasury.stateVersion(),
                        nextTreasuryBalance,
                        nextTreasuryVersion
                );

                if (deposit) {
                    insertLedger(
                            connection, operationId, 0, playerId, amountMinor, "DEBIT", reason, null
                    );
                    insertLedger(
                            connection, operationId, 1, null, amountMinor, "CREDIT", reason, clanId.toString()
                    );
                } else {
                    insertLedger(
                            connection, operationId, 0, null, amountMinor, "DEBIT", reason, clanId.toString()
                    );
                    insertLedger(
                            connection, operationId, 1, playerId, amountMinor, "CREDIT", reason, null
                    );
                }

                ClanTreasuryTransferResult result = new ClanTreasuryTransferResult(
                        updatedTreasury,
                        playerId,
                        amountMinor,
                        nextWalletBalance,
                        nextWalletVersion
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("clan_id", clanId.toString());
                data.put("player_id", playerId.toString());
                data.put("amount_minor", amountMinor);
                data.put("reason", reason);
                data.put("result", resultMap(result));
                insertProcessed(connection, operationId, operationType, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static void lockClan(Connection connection, UUID clanId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM clans WHERE clan_id = ? FOR UPDATE")) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("Unknown clan_id: " + clanId);
                }
            }
        }
    }

    private static ClanRole lockMemberRole(Connection connection, UUID clanId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role
                FROM clan_members
                WHERE clan_id = ? AND player_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, clanId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("player is not a clan member: " + playerId);
                }
                return ClanRole.valueOf(row.getString("role"));
            }
        }
    }

    private static LockedWallet lockWallet(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT balance_minor, state_version
                FROM wallets
                WHERE player_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("Wallet does not exist for player_id: " + playerId);
                }
                return new LockedWallet(row.getLong("balance_minor"), row.getLong("state_version"));
            }
        }
    }

    private static ClanTreasurySnapshot readTreasury(Connection connection, UUID clanId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT balance_minor, state_version, updated_at
                FROM clan_treasuries
                WHERE clan_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, clanId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("Clan treasury does not exist: " + clanId);
                }
                return new ClanTreasurySnapshot(
                        clanId,
                        row.getLong("balance_minor"),
                        row.getLong("state_version"),
                        row.getTimestamp("updated_at").toInstant()
                );
            }
        }
    }

    private static void updateWallet(
            Connection connection,
            UUID playerId,
            long expectedVersion,
            long nextBalance,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wallets
                SET balance_minor = ?, state_version = ?, updated_at = NOW()
                WHERE player_id = ? AND state_version = ?
                """)) {
            statement.setLong(1, nextBalance);
            statement.setLong(2, nextVersion);
            statement.setObject(3, playerId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new ClanAssetException("player wallet changed concurrently");
            }
        }
    }

    private static ClanTreasurySnapshot updateTreasury(
            Connection connection,
            UUID clanId,
            long expectedVersion,
            long nextBalance,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE clan_treasuries
                SET balance_minor = ?, state_version = ?, updated_at = NOW()
                WHERE clan_id = ? AND state_version = ?
                RETURNING updated_at
                """)) {
            statement.setLong(1, nextBalance);
            statement.setLong(2, nextVersion);
            statement.setObject(3, clanId);
            statement.setLong(4, expectedVersion);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ClanAssetException("clan treasury changed concurrently");
                }
                return new ClanTreasurySnapshot(
                        clanId,
                        nextBalance,
                        nextVersion,
                        row.getTimestamp("updated_at").toInstant()
                );
            }
        }
    }

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            long amountMinor,
            String direction,
            String reason,
            String relatedEntityId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id,
                    amount, direction, reason, related_entity_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            if (playerId == null) statement.setNull(3, java.sql.Types.OTHER); else statement.setObject(3, playerId);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(5, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(6, amountMinor);
            statement.setString(7, direction);
            statement.setString(8, reason);
            if (relatedEntityId == null) statement.setNull(9, java.sql.Types.VARCHAR); else statement.setString(9, relatedEntityId);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result_json
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"),
                        readJsonMap(row.getString("result_json"))
                ));
            }
        }
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            Map<String, Object> data
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requireType(
            ProcessedOperation operation,
            String expectedType,
            UUID operationId
    ) {
        if (!expectedType.equals(operation.operationType())) {
            throw new ClanAssetException(
                    "operation_id " + operationId + " already belongs to " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> resultMap(ClanTreasuryTransferResult result) {
        return Map.of(
                "clan_id", result.treasury().clanId().toString(),
                "treasury_balance_minor", result.treasury().balanceMinor(),
                "treasury_state_version", result.treasury().stateVersion(),
                "treasury_updated_at", result.treasury().updatedAt().toString(),
                "player_id", result.playerId().toString(),
                "amount_minor", result.amountMinor(),
                "wallet_balance_minor", result.walletBalanceMinor(),
                "wallet_state_version", result.walletStateVersion()
        );
    }

    private static ClanTreasuryTransferResult resultFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        ClanTreasurySnapshot treasury = new ClanTreasurySnapshot(
                uuidValue(value, "clan_id"),
                longValue(value, "treasury_balance_minor"),
                longValue(value, "treasury_state_version"),
                Instant.parse(stringValue(value, "treasury_updated_at"))
        );
        return new ClanTreasuryTransferResult(
                treasury,
                uuidValue(value, "player_id"),
                longValue(value, "amount_minor"),
                longValue(value, "wallet_balance_minor"),
                longValue(value, "wallet_state_version")
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ClanAssetException("Could not parse clan treasury idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ClanAssetException("Could not serialize clan treasury idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ClanAssetException("clan treasury field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) throw new ClanAssetException("missing clan treasury field: " + field);
        return Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) throw new ClanAssetException("clan treasury field is not numeric: " + field);
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) throw reused(operationId);
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) throw reused(operationId);
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) throw reused(operationId);
    }

    private static ClanAssetException reused(UUID operationId) {
        return new ClanAssetException("operation_id reused with a different clan treasury request: " + operationId);
    }

    private static long increment(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new ClanAssetException(target + " state_version overflow for " + id, exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ClanAssetException(message, exception);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,95}")) {
            throw new IllegalArgumentException("reason must be a stable lowercase identifier: " + normalized);
        }
        return normalized;
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record LockedWallet(long balanceMinor, long stateVersion) { }

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(result, "result")));
        }
    }
}
