package io.github.kevinrabbe.minecraftserver.common.economy;

import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL authority for protected Bank Manager Coin custody.
 *
 * <p>No API exists to set balances/tier directly. Wallet<->bank transfers, upgrades, and interest are atomic,
 * idempotent operations with fixed-point arithmetic.</p>
 */
public final class BankManagerRepository {
    private static final String DEPOSIT_OPERATION = "BANK_DEPOSIT";
    private static final String WITHDRAW_OPERATION = "BANK_WITHDRAW";
    private static final String UPGRADE_OPERATION = "BANK_TIER_UPGRADE";
    private static final String INTEREST_OPERATION = "BANK_INTEREST_CREDIT";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000L);

    private final DataSource dataSource;
    private final BankTierCatalog tiers;

    public BankManagerRepository(DataSource dataSource, BankTierCatalog tiers) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tiers = Objects.requireNonNull(tiers, "tiers");
    }

    public BankAccountSnapshot load(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection()) {
            BankAccountSnapshot account = readBank(connection, playerId, false);
            account.requireWithin(tiers.require(account.tier()));
            return account;
        }
    }

    public BankTransferResult deposit(
            UUID operationId,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        return transfer(operationId, playerId, amountMinor, reason, true);
    }

    public BankTransferResult withdraw(
            UUID operationId,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        return transfer(operationId, playerId, amountMinor, reason, false);
    }

    private BankTransferResult transfer(
            UUID operationId,
            UUID playerId,
            long amountMinor,
            String reason,
            boolean deposit
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        requirePositive(amountMinor, "amountMinor");
        String normalizedReason = requireReason(reason);
        String operationType = deposit ? DEPOSIT_OPERATION : WITHDRAW_OPERATION;

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<BankTransferResult> processed = findProcessedTransfer(connection, operationId, operationType);
                if (processed.isPresent()) {
                    BankTransferResult previous = processed.orElseThrow();
                    requireSameTransferRequest(previous, playerId, amountMinor, normalizedReason, operationId);
                    connection.commit();
                    return previous;
                }

                CoinWalletSnapshot wallet = readWallet(connection, playerId, true);
                BankAccountSnapshot bank = readBank(connection, playerId, true);
                BankTierDefinition tier = tiers.require(bank.tier());
                bank.requireWithin(tier);

                long nextWalletBalance;
                long nextBankBalance;
                if (deposit) {
                    if (wallet.balanceMinor() < amountMinor) {
                        throw new BankManagerException("Insufficient wallet Coin balance for deposit");
                    }
                    nextWalletBalance = wallet.balanceMinor() - amountMinor;
                    nextBankBalance = addExact(bank.balanceMinor(), amountMinor, "Bank balance overflow");
                    if (nextBankBalance > tier.capacityMinor()) {
                        throw new BankManagerException(
                                "Bank tier " + bank.tier() + " capacity exceeded: " + tier.capacityMinor()
                        );
                    }
                } else {
                    if (bank.balanceMinor() < amountMinor) {
                        throw new BankManagerException("Insufficient protected bank Coin balance for withdrawal");
                    }
                    nextBankBalance = bank.balanceMinor() - amountMinor;
                    nextWalletBalance = addExact(wallet.balanceMinor(), amountMinor, "Wallet balance overflow");
                }

                long nextWalletVersion = incrementVersion(wallet.stateVersion(), "wallet", playerId);
                long nextBankVersion = incrementVersion(bank.stateVersion(), "bank", playerId);
                updateWallet(
                        connection,
                        playerId,
                        wallet.stateVersion(),
                        nextWalletBalance,
                        nextWalletVersion
                );
                updateBank(
                        connection,
                        playerId,
                        bank.stateVersion(),
                        nextBankBalance,
                        bank.tier(),
                        nextBankVersion,
                        bank.lastInterestPeriod()
                );

                insertCoinLedger(
                        connection,
                        operationId,
                        0,
                        playerId,
                        amountMinor,
                        deposit ? "DEBIT" : "CREDIT",
                        normalizedReason
                );
                insertCoinLedger(
                        connection,
                        operationId,
                        1,
                        playerId,
                        amountMinor,
                        deposit ? "CREDIT" : "DEBIT",
                        normalizedReason
                );

                BankTransferResult result = new BankTransferResult(
                        playerId,
                        amountMinor,
                        nextWalletBalance,
                        nextWalletVersion,
                        nextBankBalance,
                        nextBankVersion,
                        normalizedReason
                );
                insertProcessedTransfer(connection, operationId, operationType, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Upgrades exactly one tier and destroys the configured upgrade cost from spendable Coins. */
    public BankUpgradeResult upgrade(
            UUID operationId,
            UUID playerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<BankUpgradeResult> processed = findProcessedUpgrade(connection, operationId);
                if (processed.isPresent()) {
                    BankUpgradeResult previous = processed.orElseThrow();
                    requireSameUpgradeRequest(previous, playerId, normalizedReason, operationId);
                    connection.commit();
                    return previous;
                }

                CoinWalletSnapshot wallet = readWallet(connection, playerId, true);
                BankAccountSnapshot bank = readBank(connection, playerId, true);
                tiers.require(bank.tier());
                BankTierDefinition target;
                try {
                    target = tiers.next(bank.tier());
                } catch (RuntimeException exception) {
                    throw new BankManagerException("Bank Manager is already at maximum configured tier", exception);
                }

                long costMinor = target.upgradeCostMinor();
                if (wallet.balanceMinor() < costMinor) {
                    throw new BankManagerException("Insufficient wallet Coin balance for Bank Manager upgrade");
                }
                if (bank.balanceMinor() > target.capacityMinor()) {
                    throw new BankManagerException("Target bank tier cannot hold the current protected balance");
                }

                long nextWalletBalance = wallet.balanceMinor() - costMinor;
                long nextWalletVersion = wallet.stateVersion();
                if (costMinor > 0) {
                    nextWalletVersion = incrementVersion(wallet.stateVersion(), "wallet", playerId);
                    updateWallet(
                            connection,
                            playerId,
                            wallet.stateVersion(),
                            nextWalletBalance,
                            nextWalletVersion
                    );
                    insertCoinLedger(
                            connection,
                            operationId,
                            0,
                            playerId,
                            costMinor,
                            "DEBIT",
                            normalizedReason
                    );
                }

                long nextBankVersion = incrementVersion(bank.stateVersion(), "bank", playerId);
                updateBank(
                        connection,
                        playerId,
                        bank.stateVersion(),
                        bank.balanceMinor(),
                        target.tier(),
                        nextBankVersion,
                        bank.lastInterestPeriod()
                );

                BankUpgradeResult result = new BankUpgradeResult(
                        playerId,
                        bank.tier(),
                        target.tier(),
                        costMinor,
                        nextWalletBalance,
                        nextWalletVersion,
                        bank.balanceMinor(),
                        nextBankVersion,
                        normalizedReason
                );
                insertProcessedUpgrade(connection, operationId, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /**
     * Credits one configured daily-interest period exactly once. Interest is capped by the current protected capacity.
     * The caller owns scheduling/time-zone policy and supplies the stable logical period date.
     */
    public BankInterestResult creditDailyInterest(
            UUID operationId,
            UUID playerId,
            LocalDate interestPeriod,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(interestPeriod, "interestPeriod");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<BankInterestResult> processed = findProcessedInterest(connection, operationId);
                if (processed.isPresent()) {
                    BankInterestResult previous = processed.orElseThrow();
                    requireSameInterestRequest(previous, playerId, interestPeriod, normalizedReason, operationId);
                    connection.commit();
                    return previous;
                }

                BankAccountSnapshot bank = readBank(connection, playerId, true);
                BankTierDefinition tier = tiers.require(bank.tier());
                bank.requireWithin(tier);
                if (bank.lastInterestPeriod() != null && !interestPeriod.isAfter(bank.lastInterestPeriod())) {
                    throw new BankManagerException(
                            "Interest period " + interestPeriod + " was already credited or is older than "
                                    + bank.lastInterestPeriod()
                    );
                }

                long rawInterest = interestMinor(bank.balanceMinor(), tier.dailyInterestBasisPoints());
                long remainingCapacity = tier.capacityMinor() - bank.balanceMinor();
                long creditedMinor = Math.min(rawInterest, remainingCapacity);
                long nextBalance = addExact(bank.balanceMinor(), creditedMinor, "Bank interest balance overflow");
                long nextVersion = incrementVersion(bank.stateVersion(), "bank", playerId);

                updateBank(
                        connection,
                        playerId,
                        bank.stateVersion(),
                        nextBalance,
                        bank.tier(),
                        nextVersion,
                        interestPeriod
                );
                if (creditedMinor > 0) {
                    insertCoinLedger(
                            connection,
                            operationId,
                            0,
                            playerId,
                            creditedMinor,
                            "CREDIT",
                            normalizedReason
                    );
                }

                BankInterestResult result = new BankInterestResult(
                        playerId,
                        interestPeriod,
                        creditedMinor,
                        nextBalance,
                        nextVersion,
                        normalizedReason
                );
                insertProcessedInterest(connection, operationId, result);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private static long interestMinor(long balanceMinor, int basisPoints) {
        if (balanceMinor == 0 || basisPoints == 0) {
            return 0;
        }
        return BigInteger.valueOf(balanceMinor)
                .multiply(BigInteger.valueOf(basisPoints))
                .divide(BASIS_POINTS)
                .longValueExact();
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
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BankManagerException("Wallet does not exist for player_id " + playerId);
                }
                return new CoinWalletSnapshot(
                        playerId,
                        result.getLong("balance_minor"),
                        result.getLong("state_version")
                );
            }
        }
    }

    private static BankAccountSnapshot readBank(Connection connection, UUID playerId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT balance_minor, tier, state_version, last_interest_period
                FROM bank_accounts
                WHERE player_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, playerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new BankManagerException("Bank account does not exist for player_id " + playerId);
                }
                Date period = result.getDate("last_interest_period");
                return new BankAccountSnapshot(
                        playerId,
                        result.getLong("balance_minor"),
                        result.getInt("tier"),
                        result.getLong("state_version"),
                        period == null ? null : period.toLocalDate()
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
                throw new BankManagerException("Wallet authority changed concurrently for " + playerId);
            }
        }
    }

    private static void updateBank(
            Connection connection,
            UUID playerId,
            long expectedVersion,
            long nextBalance,
            int nextTier,
            long nextVersion,
            LocalDate lastInterestPeriod
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bank_accounts
                SET balance_minor = ?,
                    tier = ?,
                    state_version = ?,
                    last_interest_period = ?,
                    updated_at = NOW()
                WHERE player_id = ? AND state_version = ?
                """)) {
            statement.setLong(1, nextBalance);
            statement.setInt(2, nextTier);
            statement.setLong(3, nextVersion);
            if (lastInterestPeriod == null) {
                statement.setNull(4, java.sql.Types.DATE);
            } else {
                statement.setDate(4, Date.valueOf(lastInterestPeriod));
            }
            statement.setObject(5, playerId);
            statement.setLong(6, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw new BankManagerException("Bank authority changed concurrently for " + playerId);
            }
        }
    }

    private static void insertCoinLedger(
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
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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

    private static void insertProcessedTransfer(
            Connection connection,
            UUID operationId,
            String operationType,
            BankTransferResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'player_id', ?,
                    'amount_minor', ?,
                    'wallet_balance_minor', ?,
                    'wallet_state_version', ?,
                    'bank_balance_minor', ?,
                    'bank_state_version', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, result.playerId().toString());
            statement.setLong(4, result.amountMinor());
            statement.setLong(5, result.walletBalanceMinor());
            statement.setLong(6, result.walletStateVersion());
            statement.setLong(7, result.bankBalanceMinor());
            statement.setLong(8, result.bankStateVersion());
            statement.setString(9, result.reason());
            statement.executeUpdate();
        }
    }

    private static void insertProcessedUpgrade(
            Connection connection,
            UUID operationId,
            BankUpgradeResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'player_id', ?,
                    'previous_tier', ?,
                    'new_tier', ?,
                    'cost_minor', ?,
                    'wallet_balance_minor', ?,
                    'wallet_state_version', ?,
                    'bank_balance_minor', ?,
                    'bank_state_version', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, UPGRADE_OPERATION);
            statement.setString(3, result.playerId().toString());
            statement.setInt(4, result.previousTier());
            statement.setInt(5, result.newTier());
            statement.setLong(6, result.costMinor());
            statement.setLong(7, result.walletBalanceMinor());
            statement.setLong(8, result.walletStateVersion());
            statement.setLong(9, result.bankBalanceMinor());
            statement.setLong(10, result.bankStateVersion());
            statement.setString(11, result.reason());
            statement.executeUpdate();
        }
    }

    private static void insertProcessedInterest(
            Connection connection,
            UUID operationId,
            BankInterestResult result
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'player_id', ?,
                    'interest_period', ?,
                    'credited_minor', ?,
                    'bank_balance_minor', ?,
                    'bank_state_version', ?,
                    'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, INTEREST_OPERATION);
            statement.setString(3, result.playerId().toString());
            statement.setString(4, result.interestPeriod().toString());
            statement.setLong(5, result.creditedMinor());
            statement.setLong(6, result.bankBalanceMinor());
            statement.setLong(7, result.bankStateVersion());
            statement.setString(8, result.reason());
            statement.executeUpdate();
        }
    }

    private static Optional<BankTransferResult> findProcessedTransfer(
            Connection connection,
            UUID operationId,
            String expectedType
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'player_id' AS player_id,
                       result ->> 'amount_minor' AS amount_minor,
                       result ->> 'wallet_balance_minor' AS wallet_balance_minor,
                       result ->> 'wallet_state_version' AS wallet_state_version,
                       result ->> 'bank_balance_minor' AS bank_balance_minor,
                       result ->> 'bank_state_version' AS bank_state_version,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                requireOperationType(result.getString("operation_type"), expectedType, operationId);
                return Optional.of(new BankTransferResult(
                        UUID.fromString(requireField(result, "player_id")),
                        Long.parseLong(requireField(result, "amount_minor")),
                        Long.parseLong(requireField(result, "wallet_balance_minor")),
                        Long.parseLong(requireField(result, "wallet_state_version")),
                        Long.parseLong(requireField(result, "bank_balance_minor")),
                        Long.parseLong(requireField(result, "bank_state_version")),
                        requireField(result, "reason")
                ));
            } catch (IllegalArgumentException exception) {
                throw new BankManagerException("Invalid processed bank transfer result for " + operationId, exception);
            }
        }
    }

    private static Optional<BankUpgradeResult> findProcessedUpgrade(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'player_id' AS player_id,
                       result ->> 'previous_tier' AS previous_tier,
                       result ->> 'new_tier' AS new_tier,
                       result ->> 'cost_minor' AS cost_minor,
                       result ->> 'wallet_balance_minor' AS wallet_balance_minor,
                       result ->> 'wallet_state_version' AS wallet_state_version,
                       result ->> 'bank_balance_minor' AS bank_balance_minor,
                       result ->> 'bank_state_version' AS bank_state_version,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                requireOperationType(result.getString("operation_type"), UPGRADE_OPERATION, operationId);
                return Optional.of(new BankUpgradeResult(
                        UUID.fromString(requireField(result, "player_id")),
                        Integer.parseInt(requireField(result, "previous_tier")),
                        Integer.parseInt(requireField(result, "new_tier")),
                        Long.parseLong(requireField(result, "cost_minor")),
                        Long.parseLong(requireField(result, "wallet_balance_minor")),
                        Long.parseLong(requireField(result, "wallet_state_version")),
                        Long.parseLong(requireField(result, "bank_balance_minor")),
                        Long.parseLong(requireField(result, "bank_state_version")),
                        requireField(result, "reason")
                ));
            } catch (IllegalArgumentException exception) {
                throw new BankManagerException("Invalid processed Bank Manager upgrade result for " + operationId, exception);
            }
        }
    }

    private static Optional<BankInterestResult> findProcessedInterest(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'player_id' AS player_id,
                       result ->> 'interest_period' AS interest_period,
                       result ->> 'credited_minor' AS credited_minor,
                       result ->> 'bank_balance_minor' AS bank_balance_minor,
                       result ->> 'bank_state_version' AS bank_state_version,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                requireOperationType(result.getString("operation_type"), INTEREST_OPERATION, operationId);
                return Optional.of(new BankInterestResult(
                        UUID.fromString(requireField(result, "player_id")),
                        LocalDate.parse(requireField(result, "interest_period")),
                        Long.parseLong(requireField(result, "credited_minor")),
                        Long.parseLong(requireField(result, "bank_balance_minor")),
                        Long.parseLong(requireField(result, "bank_state_version")),
                        requireField(result, "reason")
                ));
            } catch (IllegalArgumentException exception) {
                throw new BankManagerException("Invalid processed bank interest result for " + operationId, exception);
            }
        }
    }

    private static void requireSameTransferRequest(
            BankTransferResult previous,
            UUID playerId,
            long amountMinor,
            String reason,
            UUID operationId
    ) {
        if (!previous.playerId().equals(playerId)
                || previous.amountMinor() != amountMinor
                || !previous.reason().equals(reason)) {
            throw new BankManagerException("operation_id reused with different bank transfer request: " + operationId);
        }
    }

    private static void requireSameUpgradeRequest(
            BankUpgradeResult previous,
            UUID playerId,
            String reason,
            UUID operationId
    ) {
        if (!previous.playerId().equals(playerId) || !previous.reason().equals(reason)) {
            throw new BankManagerException("operation_id reused with different Bank Manager upgrade request: " + operationId);
        }
    }

    private static void requireSameInterestRequest(
            BankInterestResult previous,
            UUID playerId,
            LocalDate interestPeriod,
            String reason,
            UUID operationId
    ) {
        if (!previous.playerId().equals(playerId)
                || !previous.interestPeriod().equals(interestPeriod)
                || !previous.reason().equals(reason)) {
            throw new BankManagerException("operation_id reused with different bank interest request: " + operationId);
        }
    }

    private static String requireField(ResultSet result, String field) throws SQLException {
        String value = result.getString(field);
        if (value == null) {
            throw new BankManagerException("Processed bank result is missing field: " + field);
        }
        return value;
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new BankManagerException(
                    "operation_id " + operationId + " already belongs to operation type " + actual
            );
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new BankManagerException(message, exception);
        }
    }

    private static long incrementVersion(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new BankManagerException(target + " state_version overflow for " + id, exception);
        }
    }

    private static void requirePositive(long amount, String field) {
        if (amount <= 0) {
            throw new IllegalArgumentException(field + " must be > 0");
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

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
