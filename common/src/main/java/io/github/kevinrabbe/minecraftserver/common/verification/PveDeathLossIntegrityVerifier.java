package io.github.kevinrabbe.minecraftserver.common.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Read-only bounded reconstruction of committed ordinary-PvE pocket-Coin death-loss evidence. */
public final class PveDeathLossIntegrityVerifier {
    private static final int MAX_ALLOWED_ISSUES = 10_000;
    private static final String OPERATION_TYPE = "PVE_DEATH_LOSS";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;

    public PveDeathLossIntegrityVerifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<IntegrityIssue> verify(int maxIssues) throws SQLException {
        if (maxIssues <= 0 || maxIssues > MAX_ALLOWED_ISSUES) {
            throw new IllegalArgumentException("maxIssues must be between 1 and " + MAX_ALLOWED_ISSUES);
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            ArrayList<IntegrityIssue> issues = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT operation_id, result::text AS result
                    FROM processed_operations
                    WHERE operation_type = ?
                    ORDER BY completed_at, operation_id
                    LIMIT ?
                    """)) {
                statement.setString(1, OPERATION_TYPE);
                statement.setInt(2, maxIssues);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next() && issues.size() < maxIssues) {
                        verifyOperation(
                                connection,
                                rows.getObject("operation_id", UUID.class),
                                rows.getString("result"),
                                issues
                        );
                    }
                }
            }
            return List.copyOf(issues);
        }
    }

    private static void verifyOperation(
            Connection connection,
            UUID operationId,
            String rawResult,
            List<IntegrityIssue> issues
    ) throws SQLException {
        ParsedResult result;
        try {
            result = parse(rawResult);
        } catch (RuntimeException | IOException exception) {
            addIssue(issues, operationId, "Processed result is malformed: " + exception.getMessage());
            return;
        }

        String arithmeticError = arithmeticError(result);
        if (arithmeticError != null) {
            addIssue(issues, operationId, arithmeticError);
            return;
        }

        WalletHead wallet = loadWallet(connection, result.playerId());
        if (wallet == null || wallet.stateVersion() < result.walletStateVersion()) {
            addIssue(
                    issues,
                    operationId,
                    "Current pocket wallet is missing or behind committed death-loss wallet state_version="
                            + result.walletStateVersion()
            );
            return;
        }

        LedgerShape ledger = loadLedgerShape(connection, operationId, result);
        if (result.lossMinor() == 0) {
            if (ledger.totalRows() != 0) {
                addIssue(issues, operationId, "Zero-loss death outcome unexpectedly has Coin ledger rows");
            }
        } else if (ledger.totalRows() != 1 || ledger.matchingRows() != 1) {
            addIssue(
                    issues,
                    operationId,
                    "Death-loss Coin sink does not have exactly one matching DEBIT ledger line"
            );
        }
    }

    private static ParsedResult parse(String rawResult) throws IOException {
        JsonNode root = JSON.readTree(rawResult);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("result must be a JSON object");
        }
        UUID playerId = UUID.fromString(text(root, "player_id"));
        String policyVersion = identifier(root, "policy_version");
        String reason = identifier(root, "reason");
        return new ParsedResult(
                playerId,
                policyVersion,
                nonNegativeLong(root, "previous_balance_minor"),
                nonNegativeLong(root, "previous_wallet_state_version"),
                nonNegativeLong(root, "loss_minor"),
                nonNegativeLong(root, "wallet_balance_minor"),
                nonNegativeLong(root, "wallet_state_version"),
                reason
        );
    }

    private static String arithmeticError(ParsedResult result) {
        if (result.lossMinor() > result.previousBalanceMinor()) {
            return "Committed death loss exceeds the locked previous pocket balance";
        }
        if (result.walletBalanceMinor() != result.previousBalanceMinor() - result.lossMinor()) {
            return "Committed death-loss balance arithmetic is inconsistent";
        }
        long expectedVersion;
        try {
            expectedVersion = result.lossMinor() == 0
                    ? result.previousWalletStateVersion()
                    : Math.addExact(result.previousWalletStateVersion(), 1L);
        } catch (ArithmeticException exception) {
            return "Committed death-loss wallet state_version overflowed";
        }
        if (result.walletStateVersion() != expectedVersion) {
            return "Committed death-loss wallet state_version transition is inconsistent";
        }
        return null;
    }

    private static WalletHead loadWallet(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state_version
                FROM wallets
                WHERE player_id = ?
                """)) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new WalletHead(row.getLong("state_version")) : null;
            }
        }
    }

    private static LedgerShape loadLedgerShape(
            Connection connection,
            UUID operationId,
            ParsedResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total_rows,
                       COUNT(*) FILTER (WHERE
                           line_no = 0
                           AND player_id = ?
                           AND asset_type = ?
                           AND asset_id = ?
                           AND amount = ?
                           AND direction = 'DEBIT'
                           AND reason = ?
                       ) AS matching_rows
                FROM economic_ledger
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, result.playerId());
            statement.setString(2, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(3, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(4, result.lossMinor());
            statement.setString(5, result.reason());
            statement.setObject(6, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Death-loss ledger aggregate returned no row");
                return new LedgerShape(row.getLong("total_rows"), row.getLong("matching_rows"));
            }
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("missing/invalid " + field);
        }
        return value.textValue();
    }

    private static String identifier(JsonNode root, String field) {
        String value = text(root, field);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + field + " identifier");
        }
        return value;
    }

    private static long nonNegativeLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("missing/invalid " + field);
        }
        long result = value.longValue();
        if (result < 0) throw new IllegalArgumentException(field + " must not be negative");
        return result;
    }

    private static void addIssue(List<IntegrityIssue> issues, UUID operationId, String detail) {
        issues.add(new IntegrityIssue(
                IntegritySeverity.CRITICAL,
                "PVE_DEATH_LOSS_EVIDENCE_MISMATCH",
                operationId.toString(),
                detail
        ));
    }

    private record ParsedResult(
            UUID playerId,
            String policyVersion,
            long previousBalanceMinor,
            long previousWalletStateVersion,
            long lossMinor,
            long walletBalanceMinor,
            long walletStateVersion,
            String reason
    ) { }

    private record WalletHead(long stateVersion) { }

    private record LedgerShape(long totalRows, long matchingRows) { }
}
