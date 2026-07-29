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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL authority for ordinary-PvE pocket-Coin death loss.
 *
 * <p>The wallet row is locked before the configured policy is evaluated, so percentage/tier/curve policies observe the
 * exact balance they mutate even when spending or another death races concurrently. Protected Bank Manager custody is
 * never read or mutated here. The stable policy version is persisted with the frozen result; retries return that result
 * without re-evaluating policy code.</p>
 */
public final class PveDeathLossRepository {
    static final String OPERATION_TYPE = "PVE_DEATH_LOSS";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public PveDeathLossRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public PveDeathLossResult apply(
            UUID operationId,
            UUID playerId,
            String policyVersion,
            PveDeathLossPolicy policy,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(policy, "policy");
        String normalizedPolicyVersion = requireIdentifier(policyVersion, "policyVersion");
        String normalizedReason = requireIdentifier(reason, "reason");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedOperation existing = processed.orElseThrow();
                    if (!OPERATION_TYPE.equals(existing.operationType())) {
                        throw new CoinWalletException(
                                "operation_id already belongs to " + existing.operationType() + ": " + operationId
                        );
                    }
                    PveDeathLossResult result = decodeResult(existing.result(), operationId);
                    requireSameRequest(
                            result,
                            playerId,
                            normalizedPolicyVersion,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return result;
                }

                CoinWalletSnapshot wallet = readWallet(connection, playerId, true);
                long lossMinor = policy.lossMinor(wallet.balanceMinor());
                if (lossMinor < 0 || lossMinor > wallet.balanceMinor()) {
                    throw new CoinWalletException(
                            "PvE death-loss policy " + normalizedPolicyVersion + " returned invalid loss "
                                    + lossMinor + " for locked pocket balance " + wallet.balanceMinor()
                    );
                }

                long nextBalance = wallet.balanceMinor() - lossMinor;
                long nextVersion = wallet.stateVersion();
                if (lossMinor > 0) {
                    nextVersion = incrementVersion(wallet.stateVersion(), playerId);
                    updateWallet(connection, playerId, wallet.stateVersion(), nextBalance, nextVersion);
                    insertLedger(connection, operationId, playerId, lossMinor, normalizedReason);
                }

                PveDeathLossResult result = new PveDeathLossResult(
                        playerId,
                        normalizedPolicyVersion,
                        wallet.balanceMinor(),
                        wallet.stateVersion(),
                        lossMinor,
                        nextBalance,
                        nextVersion,
                        normalizedReason
                );
                insertProcessed(connection, operationId, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
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
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CoinWalletException("Wallet does not exist for player_id " + playerId);
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

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            UUID playerId,
            long lossMinor,
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
                ) VALUES (?, 0, ?, ?, ?, ?, 'DEBIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, playerId);
            statement.setString(3, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(5, lossMinor);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedOperation> findProcessed(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, result::text AS result
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ProcessedOperation(
                        row.getString("operation_type"),
                        row.getString("result")
                ));
            }
        }
    }

    private static void insertProcessed(
            Connection connection,
            UUID operationId,
            PveDeathLossResult result
    ) throws SQLException {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("player_id", result.playerId().toString());
        data.put("policy_version", result.policyVersion());
        data.put("previous_balance_minor", result.previousBalanceMinor());
        data.put("previous_wallet_state_version", result.previousWalletStateVersion());
        data.put("loss_minor", result.lossMinor());
        data.put("wallet_balance_minor", result.walletBalanceMinor());
        data.put("wallet_state_version", result.walletStateVersion());
        data.put("reason", result.reason());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, OPERATION_TYPE);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static PveDeathLossResult decodeResult(String rawResult, UUID operationId) {
        Map<String, Object> data;
        try {
            data = JSON.readValue(rawResult, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new CoinWalletException("Malformed PvE death-loss result for operation " + operationId, exception);
        }
        try {
            return new PveDeathLossResult(
                    UUID.fromString(requireString(data, "player_id")),
                    requireString(data, "policy_version"),
                    requireLong(data, "previous_balance_minor"),
                    requireLong(data, "previous_wallet_state_version"),
                    requireLong(data, "loss_minor"),
                    requireLong(data, "wallet_balance_minor"),
                    requireLong(data, "wallet_state_version"),
                    requireString(data, "reason")
            );
        } catch (IllegalArgumentException exception) {
            throw new CoinWalletException("Invalid PvE death-loss result for operation " + operationId, exception);
        }
    }

    private static void requireSameRequest(
            PveDeathLossResult result,
            UUID playerId,
            String policyVersion,
            String reason,
            UUID operationId
    ) {
        if (!result.playerId().equals(playerId)
                || !result.policyVersion().equals(policyVersion)
                || !result.reason().equals(reason)) {
            throw new CoinWalletException(
                    "PvE death-loss operation_id was reused with a different request: " + operationId
            );
        }
    }

    private static long incrementVersion(long current, UUID playerId) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new CoinWalletException("Wallet state_version overflow for " + playerId, exception);
        }
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has invalid identifier format: " + normalized);
        }
        return normalized;
    }

    private static String requireString(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing/invalid " + field);
        }
        return text;
    }

    private static long requireLong(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("missing/invalid " + field);
        }
        long result = number.longValue();
        if (number.doubleValue() != (double) result) {
            throw new IllegalArgumentException("non-integral " + field);
        }
        return result;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CoinWalletException("Could not serialize PvE death-loss result", exception);
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private record ProcessedOperation(String operationType, String result) { }
}
