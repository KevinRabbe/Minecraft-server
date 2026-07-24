package io.github.kevinrabbe.minecraftserver.common.economy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL authority for fixed-point Coin balances. No API exists to arbitrarily set a balance. */
public final class CoinWalletRepository {
    private static final String CREDIT_OPERATION = "COIN_SYSTEM_CREDIT";
    private static final String DEBIT_OPERATION = "COIN_SYSTEM_DEBIT";
    private static final String TRANSFER_OPERATION = "COIN_PLAYER_TRANSFER";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");

    private final DataSource dataSource;

    public CoinWalletRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public CoinWalletSnapshot load(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            return readWallet(connection, playerId, false);
        }
    }

    /** Controlled Coin faucet. Callers must supply a stable reason such as salvage or reward type. */
    public CoinWalletMutationResult creditFromSystem(
            UUID operationId,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        requirePositiveAmount(amountMinor);
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CoinWalletMutationResult> processed = findProcessedMutation(
                        connection,
                        operationId,
                        CREDIT_OPERATION
                );
                if (processed.isPresent()) {
                    CoinWalletMutationResult previous = processed.orElseThrow();
                    requireSameMutationRequest(previous, playerId, amountMinor, normalizedReason, operationId);
                    connection.commit();
                    return previous;
                }

                CoinWalletSnapshot current = readWallet(connection, playerId, true);
                long nextBalance = addExact(
                        current.balanceMinor(),
                        amountMinor,
                        "Coin balance overflow for " + playerId
                );
                long nextVersion = incrementVersion(current.stateVersion(), playerId);
                updateWallet(connection, playerId, current.stateVersion(), nextBalance, nextVersion);
                insertLedgerLine(
                        connection,
                        operationId,
                        0,
                        playerId,
                        amountMinor,
                        "CREDIT",
                        normalizedReason
                );

                CoinWalletMutationResult result = new CoinWalletMutationResult(
                        playerId,
                        amountMinor,
                        nextBalance,
                        nextVersion,
                        normalizedReason
                );
                insertProcessedMutation(connection, operationId, CREDIT_OPERATION, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Controlled Coin sink. Fails atomically when the wallet cannot cover the full amount. */
    public CoinWalletMutationResult debitToSystem(
            UUID operationId,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        requirePositiveAmount(amountMinor);
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CoinWalletMutationResult> processed = findProcessedMutation(
                        connection,
                        operationId,
                        DEBIT_OPERATION
                );
                if (processed.isPresent()) {
                    CoinWalletMutationResult previous = processed.orElseThrow();
                    requireSameMutationRequest(previous, playerId, amountMinor, normalizedReason, operationId);
                    connection.commit();
                    return previous;
                }

                CoinWalletSnapshot current = readWallet(connection, playerId, true);
                if (current.balanceMinor() < amountMinor) {
                    throw new CoinWalletException(
                            "Insufficient Coin balance for " + playerId
                                    + ": required " + amountMinor
                                    + " but available " + current.balanceMinor()
                    );
                }

                long nextBalance = current.balanceMinor() - amountMinor;
                long nextVersion = incrementVersion(current.stateVersion(), playerId);
                updateWallet(connection, playerId, current.stateVersion(), nextBalance, nextVersion);
                insertLedgerLine(
                        connection,
                        operationId,
                        0,
                        playerId,
                        amountMinor,
                        "DEBIT",
                        normalizedReason
                );

                CoinWalletMutationResult result = new CoinWalletMutationResult(
                        playerId,
                        amountMinor,
                        nextBalance,
                        nextVersion,
                        normalizedReason
                );
                insertProcessedMutation(connection, operationId, DEBIT_OPERATION, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Atomic player-to-player transfer. Both wallet rows are locked in deterministic UUID order. */
    public CoinTransferResult transfer(
            UUID operationId,
            UUID fromPlayerId,
            UUID toPlayerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(fromPlayerId, "fromPlayerId");
        Objects.requireNonNull(toPlayerId, "toPlayerId");
        if (fromPlayerId.equals(toPlayerId)) {
            throw new IllegalArgumentException("Coin transfer requires distinct players");
        }
        requirePositiveAmount(amountMinor);
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CoinTransferResult> processed = findProcessedTransfer(
                        connection,
                        operationId,
                        TRANSFER_OPERATION
                );
                if (processed.isPresent()) {
                    CoinTransferResult previous = processed.orElseThrow();
                    requireSameTransferRequest(
                            previous,
                            fromPlayerId,
                            toPlayerId,
                            amountMinor,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous;
                }

                Map<UUID, CoinWalletSnapshot> locked = lockWallets(
                        connection,
                        fromPlayerId,
                        toPlayerId
                );
                CoinWalletSnapshot from = requireLockedWallet(locked, fromPlayerId);
                CoinWalletSnapshot to = requireLockedWallet(locked, toPlayerId);
                if (from.balanceMinor() < amountMinor) {
                    throw new CoinWalletException(
                            "Insufficient Coin balance for transfer from " + fromPlayerId
                    );
                }

                long nextFromBalance = from.balanceMinor() - amountMinor;
                long nextToBalance = addExact(
                        to.balanceMinor(),
                        amountMinor,
                        "Coin balance overflow for " + toPlayerId
                );
                long nextFromVersion = incrementVersion(from.stateVersion(), fromPlayerId);
                long nextToVersion = incrementVersion(to.stateVersion(), toPlayerId);

                updateWallet(
                        connection,
                        fromPlayerId,
                        from.stateVersion(),
                        nextFromBalance,
                        nextFromVersion
                );
                updateWallet(
                        connection,
                        toPlayerId,
                        to.stateVersion(),
                        nextToBalance,
                        nextToVersion
                );
                insertLedgerLine(
                        connection,
                        operationId,
                        0,
                        fromPlayerId,
                        amountMinor,
                        "DEBIT",
                        normalizedReason
                );
                insertLedgerLine(
                        connection,
                        operationId,
                        1,
                        toPlayerId,
                        amountMinor,
                        "CREDIT",
                        normalizedReason
                );

                CoinTransferResult result = new CoinTransferResult(
                        fromPlayerId,
                        toPlayerId,
                        amountMinor,
                        nextFromBalance,
                        nextFromVersion,
                        nextToBalance,
                        nextToVersion,
                        normalizedReason
                );
                insertProcessedTransfer(connection, operationId, TRANSFER_OPERATION, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static Map<UUID, CoinWalletSnapshot> lockWallets(
            Connection connection,
            UUID firstPlayerId,
            UUID secondPlayerId
    ) throws SQLException {
        String sql = """
                SELECT player_id, balance_minor, state_version
                FROM wallets
                WHERE player_id IN (?, ?)
                ORDER BY player_id
                FOR UPDATE
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, firstPlayerId);
            statement.setObject(2, secondPlayerId);
            try (ResultSet results = statement.executeQuery()) {
                HashMap<UUID, CoinWalletSnapshot> wallets = new HashMap<>();
                while (results.next()) {
                    UUID playerId = results.getObject("player_id", UUID.class);
                    wallets.put(playerId, new CoinWalletSnapshot(
                            playerId,
                            results.getLong("balance_minor"),
                            results.getLong("state_version")
                    ));
                }
                return wallets;
            }
        }
    }

    private static CoinWalletSnapshot requireLockedWallet(
            Map<UUID, CoinWalletSnapshot> locked,
            UUID playerId
    ) {
        CoinWalletSnapshot wallet = locked.get(playerId);
        if (wallet == null) {
            throw new CoinWalletException("Wallet does not exist for player_id " + playerId);
        }
        return wallet;
    }

    private static CoinWalletSnapshot readWallet(
            Connection connection,
            UUID playerId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT balance_minor, state_version
                FROM wallets
                WHERE player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new CoinWalletException("Wallet does not exist for player_id " + playerId);
                }
                return new CoinWalletSnapshot(
                        playerId,
                        results.getLong("balance_minor"),
                        results.getLong("state_version")
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
                SET balance_minor = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE player_id = ?
                  AND state_version = ?
                """)) {
            statement.setLong(1, nextBalance);
            statement.setLong(2, nextVersion);
            statement.setObject(3, playerId);
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new CoinWalletException("Wallet authority changed concurrently for " + playerId);
            }
        }
    }

    private static void insertLedgerLine(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            long amountMinor,
            String direction,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id,
                    line_no,
                    player_id,
                    asset_type,
                    asset_id,
                    amount,
                    direction,
                    reason
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(5, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(6, amountMinor);
            statement.setString(7, direction);
            statement.setString(8, reason);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedMutation(
            Connection connection,
            UUID operationId,
            String operationType,
            CoinWalletMutationResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (
                    ?,
                    ?,
                    jsonb_build_object(
                        'player_id', ?,
                        'amount_minor', ?,
                        'balance_minor', ?,
                        'state_version', ?,
                        'reason', ?
                    )
                )
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, result.playerId().toString());
            statement.setLong(4, result.amountMinor());
            statement.setLong(5, result.balanceMinor());
            statement.setLong(6, result.stateVersion());
            statement.setString(7, result.reason());
            statement.executeUpdate();
        }
    }

    private static void insertProcessedTransfer(
            Connection connection,
            UUID operationId,
            String operationType,
            CoinTransferResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (
                    ?,
                    ?,
                    jsonb_build_object(
                        'from_player_id', ?,
                        'to_player_id', ?,
                        'amount_minor', ?,
                        'from_balance_minor', ?,
                        'from_state_version', ?,
                        'to_balance_minor', ?,
                        'to_state_version', ?,
                        'reason', ?
                    )
                )
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, result.fromPlayerId().toString());
            statement.setString(4, result.toPlayerId().toString());
            statement.setLong(5, result.amountMinor());
            statement.setLong(6, result.fromBalanceMinor());
            statement.setLong(7, result.fromStateVersion());
            statement.setLong(8, result.toBalanceMinor());
            statement.setLong(9, result.toStateVersion());
            statement.setString(10, result.reason());
            statement.executeUpdate();
        }
    }

    private static Optional<CoinWalletMutationResult> findProcessedMutation(
            Connection connection,
            UUID operationId,
            String expectedOperationType
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'player_id' AS player_id,
                       result ->> 'amount_minor' AS amount_minor,
                       result ->> 'balance_minor' AS balance_minor,
                       result ->> 'state_version' AS state_version,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                requireOperationType(results.getString("operation_type"), expectedOperationType, operationId);
                try {
                    return Optional.of(new CoinWalletMutationResult(
                            UUID.fromString(requireField(results, "player_id")),
                            Long.parseLong(requireField(results, "amount_minor")),
                            Long.parseLong(requireField(results, "balance_minor")),
                            Long.parseLong(requireField(results, "state_version")),
                            requireField(results, "reason")
                    ));
                } catch (IllegalArgumentException exception) {
                    throw new CoinWalletException(
                            "Malformed persisted wallet operation result for " + operationId,
                            exception
                    );
                }
            }
        }
    }

    private static Optional<CoinTransferResult> findProcessedTransfer(
            Connection connection,
            UUID operationId,
            String expectedOperationType
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'from_player_id' AS from_player_id,
                       result ->> 'to_player_id' AS to_player_id,
                       result ->> 'amount_minor' AS amount_minor,
                       result ->> 'from_balance_minor' AS from_balance_minor,
                       result ->> 'from_state_version' AS from_state_version,
                       result ->> 'to_balance_minor' AS to_balance_minor,
                       result ->> 'to_state_version' AS to_state_version,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                requireOperationType(results.getString("operation_type"), expectedOperationType, operationId);
                try {
                    return Optional.of(new CoinTransferResult(
                            UUID.fromString(requireField(results, "from_player_id")),
                            UUID.fromString(requireField(results, "to_player_id")),
                            Long.parseLong(requireField(results, "amount_minor")),
                            Long.parseLong(requireField(results, "from_balance_minor")),
                            Long.parseLong(requireField(results, "from_state_version")),
                            Long.parseLong(requireField(results, "to_balance_minor")),
                            Long.parseLong(requireField(results, "to_state_version")),
                            requireField(results, "reason")
                    ));
                } catch (IllegalArgumentException exception) {
                    throw new CoinWalletException(
                            "Malformed persisted Coin transfer result for " + operationId,
                            exception
                    );
                }
            }
        }
    }

    private static void requireOperationType(
            String actual,
            String expected,
            UUID operationId
    ) {
        if (!expected.equals(actual)) {
            throw new CoinWalletException(
                    "operation_id already belongs to " + actual + ": " + operationId
            );
        }
    }

    private static void requireSameMutationRequest(
            CoinWalletMutationResult previous,
            UUID playerId,
            long amountMinor,
            String reason,
            UUID operationId
    ) {
        if (!previous.playerId().equals(playerId)
                || previous.amountMinor() != amountMinor
                || !previous.reason().equals(reason)) {
            throw new CoinWalletException(
                    "operation_id was already used for a different wallet mutation: " + operationId
            );
        }
    }

    private static void requireSameTransferRequest(
            CoinTransferResult previous,
            UUID fromPlayerId,
            UUID toPlayerId,
            long amountMinor,
            String reason,
            UUID operationId
    ) {
        if (!previous.fromPlayerId().equals(fromPlayerId)
                || !previous.toPlayerId().equals(toPlayerId)
                || previous.amountMinor() != amountMinor
                || !previous.reason().equals(reason)) {
            throw new CoinWalletException(
                    "operation_id was already used for a different Coin transfer: " + operationId
            );
        }
    }

    private static String requireField(ResultSet results, String column) throws SQLException {
        String value = results.getString(column);
        if (value == null || value.isBlank()) {
            throw new CoinWalletException("Processed operation result is missing " + column);
        }
        return value;
    }

    private static long incrementVersion(long currentVersion, UUID playerId) {
        return addExact(currentVersion, 1, "Wallet state_version overflow for " + playerId);
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CoinWalletException(message, exception);
        }
    }

    private static void requirePositiveAmount(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be > 0");
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

    private static void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
