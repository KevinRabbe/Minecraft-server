package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL authority for secure player-to-player direct trades. */
public final class SecureTradeRepository {
    private static final String COIN_OFFER_OPERATION = "SECURE_TRADE_COIN_OFFER";
    private static final String CONFIRM_OPERATION = "SECURE_TRADE_CONFIRM";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public SecureTradeRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public SecureTradeSnapshot createTrade(UUID operationId, UUID playerAId, UUID playerBId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerAId, "playerAId");
        Objects.requireNonNull(playerBId, "playerBId");
        if (playerAId.equals(playerBId)) {
            throw new IllegalArgumentException("secure trade requires two distinct players");
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<SecureTradeSnapshot> existing = findByCreateOperation(connection, operationId);
                if (existing.isPresent()) {
                    SecureTradeSnapshot previous = existing.orElseThrow();
                    if (!previous.playerAId().equals(playerAId) || !previous.playerBId().equals(playerBId)) {
                        throw new SecureTradeException(
                                "operation_id reused with a different secure-trade creation request: " + operationId
                        );
                    }
                    connection.commit();
                    return previous;
                }

                requirePlayer(connection, playerAId);
                requirePlayer(connection, playerBId);
                UUID tradeId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO secure_trades(
                            trade_id, player_a_id, player_b_id, status, revision, create_operation_id
                        ) VALUES (?, ?, ?, 'OPEN', 0, ?)
                        """)) {
                    statement.setObject(1, tradeId);
                    statement.setObject(2, playerAId);
                    statement.setObject(3, playerBId);
                    statement.setObject(4, operationId);
                    statement.executeUpdate();
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

    public SecureTradeSnapshot load(UUID tradeId) throws SQLException {
        Objects.requireNonNull(tradeId, "tradeId");
        try (Connection connection = dataSource.getConnection()) {
            return readTrade(connection, tradeId, false);
        }
    }

    /**
     * Replaces one participant's Coin offer. The delta moves between spendable wallet and trade escrow atomically.
     * Every real offer change increments the trade revision and invalidates both confirmations.
     */
    public SecureTradeCoinOfferResult setCoinOffer(
            UUID operationId,
            UUID tradeId,
            UUID playerId,
            long newAmountMinor,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        if (newAmountMinor < 0) {
            throw new IllegalArgumentException("newAmountMinor must be >= 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), COIN_OFFER_OPERATION, operationId);
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireUuid(data, "player_id", playerId, operationId);
                    requireLong(data, "new_amount_minor", newAmountMinor, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    SecureTradeCoinOfferResult result = coinOfferResultFrom(data);
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                requireOpenParticipant(trade, playerId);
                long currentEscrow = readCoinEscrow(connection, tradeId, playerId);
                if (currentEscrow == newAmountMinor) {
                    throw new SecureTradeException("Coin offer must change the current escrow amount");
                }

                CoinWalletSnapshot wallet = readWallet(connection, playerId, true);
                long walletBalance = wallet.balanceMinor();
                long walletVersion = wallet.stateVersion();
                long delta = newAmountMinor - currentEscrow;
                if (delta > 0) {
                    if (walletBalance < delta) {
                        throw new SecureTradeException("Insufficient Coin balance for secure-trade escrow");
                    }
                    walletBalance -= delta;
                    walletVersion = incrementVersion(walletVersion, "wallet", playerId);
                    updateWallet(connection, playerId, wallet.stateVersion(), walletBalance, walletVersion);
                    insertCoinLedger(connection, operationId, playerId, delta, "DEBIT", normalizedReason);
                } else {
                    long refund = Math.negateExact(delta);
                    walletBalance = addExact(walletBalance, refund, "Coin wallet overflow during trade escrow refund");
                    walletVersion = incrementVersion(walletVersion, "wallet", playerId);
                    updateWallet(connection, playerId, wallet.stateVersion(), walletBalance, walletVersion);
                    insertCoinLedger(connection, operationId, playerId, refund, "CREDIT", normalizedReason);
                }

                writeCoinEscrow(connection, tradeId, playerId, newAmountMinor);
                long nextRevision = incrementRevision(trade.revision(), tradeId);
                resetConfirmationsAndAdvanceRevision(connection, tradeId, trade.revision(), nextRevision);
                SecureTradeSnapshot updatedTrade = readTrade(connection, tradeId, false);
                SecureTradeCoinOfferResult result = new SecureTradeCoinOfferResult(
                        updatedTrade,
                        playerId,
                        newAmountMinor,
                        walletBalance,
                        walletVersion
                );

                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("trade_id", tradeId.toString());
                data.put("player_id", playerId.toString());
                data.put("new_amount_minor", newAmountMinor);
                data.put("reason", normalizedReason);
                data.put("trade", tradeMap(updatedTrade));
                data.put("escrow_amount_minor", newAmountMinor);
                data.put("wallet_balance_minor", walletBalance);
                data.put("wallet_state_version", walletVersion);
                insertProcessed(connection, operationId, COIN_OFFER_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Confirms exactly the current revision. The second matching confirmation atomically locks the trade. */
    public SecureTradeSnapshot confirm(UUID operationId, UUID tradeId, UUID playerId) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), CONFIRM_OPERATION, operationId);
                    requireUuid(data, "trade_id", tradeId, operationId);
                    requireUuid(data, "player_id", playerId, operationId);
                    SecureTradeSnapshot result = tradeFrom(data.get("trade"));
                    connection.commit();
                    return result;
                }

                SecureTradeSnapshot trade = readTrade(connection, tradeId, true);
                requireParticipant(trade, playerId);
                if (trade.status() == SecureTradeStatus.SETTLED || trade.status() == SecureTradeStatus.CANCELLED) {
                    throw new SecureTradeException("terminal secure trade cannot be confirmed: " + tradeId);
                }

                if (trade.status() == SecureTradeStatus.OPEN) {
                    boolean playerA = trade.playerAId().equals(playerId);
                    Long ownConfirmation = playerA
                            ? trade.playerAConfirmedRevision()
                            : trade.playerBConfirmedRevision();
                    if (!Long.valueOf(trade.revision()).equals(ownConfirmation)) {
                        try (PreparedStatement statement = connection.prepareStatement(playerA ? """
                                UPDATE secure_trades
                                SET player_a_confirmed_revision = ?, updated_at = NOW()
                                WHERE trade_id = ? AND status = 'OPEN' AND revision = ?
                                """ : """
                                UPDATE secure_trades
                                SET player_b_confirmed_revision = ?, updated_at = NOW()
                                WHERE trade_id = ? AND status = 'OPEN' AND revision = ?
                                """)) {
                            statement.setLong(1, trade.revision());
                            statement.setObject(2, tradeId);
                            statement.setLong(3, trade.revision());
                            if (statement.executeUpdate() != 1) {
                                throw new SecureTradeException("secure trade changed concurrently during confirmation");
                            }
                        }
                    }

                    SecureTradeSnapshot afterConfirmation = readTrade(connection, tradeId, true);
                    if (Long.valueOf(afterConfirmation.revision()).equals(afterConfirmation.playerAConfirmedRevision())
                            && Long.valueOf(afterConfirmation.revision()).equals(afterConfirmation.playerBConfirmedRevision())) {
                        try (PreparedStatement statement = connection.prepareStatement("""
                                UPDATE secure_trades
                                SET status = 'LOCKED', updated_at = NOW()
                                WHERE trade_id = ? AND status = 'OPEN' AND revision = ?
                                  AND player_a_confirmed_revision = revision
                                  AND player_b_confirmed_revision = revision
                                """)) {
                            statement.setObject(1, tradeId);
                            statement.setLong(2, afterConfirmation.revision());
                            if (statement.executeUpdate() != 1) {
                                throw new SecureTradeException("secure trade changed concurrently while locking");
                            }
                        }
                    }
                }

                SecureTradeSnapshot result = readTrade(connection, tradeId, false);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("trade_id", tradeId.toString());
                data.put("player_id", playerId.toString());
                data.put("trade", tradeMap(result));
                insertProcessed(connection, operationId, CONFIRM_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public long coinEscrow(UUID tradeId, UUID playerId) throws SQLException {
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            SecureTradeSnapshot trade = readTrade(connection, tradeId, false);
            requireParticipant(trade, playerId);
            return readCoinEscrow(connection, tradeId, playerId);
        }
    }

    private static Optional<SecureTradeSnapshot> findByCreateOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT trade_id
                FROM secure_trades
                WHERE create_operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(readTrade(connection, row.getObject("trade_id", UUID.class), false))
                        : Optional.empty();
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

    private static void requireOpenParticipant(SecureTradeSnapshot trade, UUID playerId) {
        requireParticipant(trade, playerId);
        if (trade.status() != SecureTradeStatus.OPEN) {
            throw new SecureTradeException("secure-trade offers may change only while OPEN: " + trade.tradeId());
        }
    }

    private static void requireParticipant(SecureTradeSnapshot trade, UUID playerId) {
        if (!trade.participant(playerId)) {
            throw new SecureTradeException("player is not a secure-trade participant: " + playerId);
        }
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Unknown player_id: " + playerId);
                }
            }
        }
    }

    private static long readCoinEscrow(Connection connection, UUID tradeId, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT amount_minor
                FROM secure_trade_coin_escrow
                WHERE trade_id = ? AND owner_player_id = ?
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong("amount_minor") : 0L;
            }
        }
    }

    private static void writeCoinEscrow(
            Connection connection,
            UUID tradeId,
            UUID playerId,
            long amountMinor
    ) throws SQLException {
        if (amountMinor == 0) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM secure_trade_coin_escrow
                    WHERE trade_id = ? AND owner_player_id = ?
                    """)) {
                statement.setObject(1, tradeId);
                statement.setObject(2, playerId);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO secure_trade_coin_escrow(trade_id, owner_player_id, amount_minor)
                VALUES (?, ?, ?)
                ON CONFLICT (trade_id, owner_player_id)
                DO UPDATE SET amount_minor = EXCLUDED.amount_minor
                """)) {
            statement.setObject(1, tradeId);
            statement.setObject(2, playerId);
            statement.setLong(3, amountMinor);
            statement.executeUpdate();
        }
    }

    private static void resetConfirmationsAndAdvanceRevision(
            Connection connection,
            UUID tradeId,
            long expectedRevision,
            long nextRevision
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE secure_trades
                SET revision = ?,
                    player_a_confirmed_revision = NULL,
                    player_b_confirmed_revision = NULL,
                    updated_at = NOW()
                WHERE trade_id = ? AND status = 'OPEN' AND revision = ?
                """)) {
            statement.setLong(1, nextRevision);
            statement.setObject(2, tradeId);
            statement.setLong(3, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new SecureTradeException("secure trade changed concurrently while advancing revision");
            }
        }
    }

    private static CoinWalletSnapshot readWallet(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT balance_minor, state_version
                FROM wallets
                WHERE player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SecureTradeException("Wallet does not exist for player_id " + playerId);
                }
                return new CoinWalletSnapshot(
                        playerId,
                        row.getLong("balance_minor"),
                        row.getLong("state_version")
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
                throw new SecureTradeException("Wallet authority changed concurrently for " + playerId);
            }
        }
    }

    private static void insertCoinLedger(
            Connection connection,
            UUID operationId,
            UUID playerId,
            long amountMinor,
            String direction,
            String reason
    ) throws SQLException {
        if (amountMinor <= 0) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, 0, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, playerId);
            statement.setString(3, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(5, amountMinor);
            statement.setString(6, direction);
            statement.setString(7, reason);
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
                if (!row.next()) {
                    return Optional.empty();
                }
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
            Map<String, Object> result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, writeJson(result));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requireType(
            ProcessedOperation operation,
            String expectedType,
            UUID operationId
    ) {
        if (!expectedType.equals(operation.operationType())) {
            throw new SecureTradeException(
                    "operation_id " + operationId + " already belongs to operation type " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> tradeMap(SecureTradeSnapshot trade) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("trade_id", trade.tradeId().toString());
        value.put("player_a_id", trade.playerAId().toString());
        value.put("player_b_id", trade.playerBId().toString());
        value.put("status", trade.status().name());
        value.put("revision", trade.revision());
        value.put("player_a_confirmed_revision", trade.playerAConfirmedRevision());
        value.put("player_b_confirmed_revision", trade.playerBConfirmedRevision());
        value.put("created_at", trade.createdAt().toString());
        value.put("updated_at", trade.updatedAt().toString());
        value.put("settled_at", trade.settledAt() == null ? null : trade.settledAt().toString());
        return value;
    }

    private static SecureTradeSnapshot tradeFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "trade");
        return new SecureTradeSnapshot(
                uuidValue(value, "trade_id"),
                uuidValue(value, "player_a_id"),
                uuidValue(value, "player_b_id"),
                SecureTradeStatus.valueOf(stringValue(value, "status")),
                longValue(value, "revision"),
                nullableLong(value, "player_a_confirmed_revision"),
                nullableLong(value, "player_b_confirmed_revision"),
                Instant.parse(stringValue(value, "created_at")),
                Instant.parse(stringValue(value, "updated_at")),
                nullableInstant(value, "settled_at")
        );
    }

    private static SecureTradeCoinOfferResult coinOfferResultFrom(Map<String, Object> data) {
        return new SecureTradeCoinOfferResult(
                tradeFrom(data.get("trade")),
                uuidValue(data, "player_id"),
                longValue(data, "escrow_amount_minor"),
                longValue(data, "wallet_balance_minor"),
                longValue(data, "wallet_state_version")
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not parse secure-trade idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SecureTradeException("Could not serialize secure-trade idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SecureTradeException("secure-trade idempotency field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new SecureTradeException("secure-trade idempotency result is missing field: " + field);
        }
        return Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new SecureTradeException("secure-trade idempotency field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static Long nullableLong(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new SecureTradeException("secure-trade idempotency field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static Instant nullableInstant(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw == null ? null : Instant.parse(Objects.toString(raw));
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw new SecureTradeException("operation_id reused with a different secure-trade request: " + operationId);
        }
    }

    private static long incrementRevision(long current, UUID tradeId) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException("secure trade revision overflow: " + tradeId, exception);
        }
    }

    private static long incrementVersion(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException(target + " state_version overflow for " + id, exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new SecureTradeException(message, exception);
        }
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (!REASON_ID.matcher(normalized).matches()) {
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

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
