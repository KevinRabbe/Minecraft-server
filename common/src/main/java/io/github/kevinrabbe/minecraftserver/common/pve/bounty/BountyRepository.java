package io.github.kevinrabbe.minecraftserver.common.pve.bounty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletSnapshot;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL authority for paid mob-family bounty contracts and recoverable boss summons.
 * Runtime bosses are disposable; the persistent contract, summon lease and reward settlement are not.
 *
 * <p>One contract freezes the exact configured {@code content_version} selected at start. Hunt eligibility, boss
 * identity and rewards must therefore remain addressable under that exact family/tier/version for the whole lifecycle.
 * Every operation is idempotent through one append-only {@code processed_operations} row containing the exact original
 * result snapshot. Retries never reconstruct historical results from later mutable state or newer content versions.</p>
 */
public final class BountyRepository {
    private static final String START_OPERATION = "BOUNTY_CONTRACT_START";
    private static final String PROGRESS_OPERATION = "BOUNTY_KILL_PROGRESS";
    private static final String PREPARE_SUMMON_OPERATION = "BOUNTY_SUMMON_PREPARE";
    private static final String CLAIM_SUMMON_OPERATION = "BOUNTY_SUMMON_CLAIM";
    private static final String HEARTBEAT_SUMMON_OPERATION = "BOUNTY_SUMMON_HEARTBEAT";
    private static final String COMPLETE_OPERATION = "BOUNTY_BOSS_COMPLETE";
    private static final String FAIL_OPERATION = "BOUNTY_BOSS_FAIL";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final BountyTierCatalog catalog;
    private final BountyRewardResolver rewards;
    private final Duration summonLeaseDuration;
    private final Clock clock;

    public BountyRepository(
            DataSource dataSource,
            BountyTierCatalog catalog,
            BountyRewardResolver rewards,
            Duration summonLeaseDuration
    ) {
        this(dataSource, catalog, rewards, summonLeaseDuration, Clock.systemUTC());
    }

    public BountyRepository(
            DataSource dataSource,
            BountyTierCatalog catalog,
            BountyRewardResolver rewards,
            Duration summonLeaseDuration,
            Clock clock
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.summonLeaseDuration = Objects.requireNonNull(summonLeaseDuration, "summonLeaseDuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (summonLeaseDuration.isZero()
                || summonLeaseDuration.isNegative()
                || summonLeaseDuration.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("summonLeaseDuration must be > 0 and <= 30 minutes");
        }
    }

    public BountyContractSnapshot loadContract(UUID contractId) throws SQLException {
        Objects.requireNonNull(contractId, "contractId");
        try (Connection connection = dataSource.getConnection()) {
            return readContract(connection, contractId, false);
        }
    }

    public BountySummonSnapshot loadSummon(UUID summonId) throws SQLException {
        Objects.requireNonNull(summonId, "summonId");
        try (Connection connection = dataSource.getConnection()) {
            return readSummon(connection, summonId, false);
        }
    }

    public BountyContractStartResult startContract(
            UUID operationId,
            UUID playerId,
            BountyFamilyId familyId,
            int tier,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        familyId = Objects.requireNonNull(familyId, "familyId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), START_OPERATION, operationId);
                    requireUuid(data, "request_player_id", playerId, operationId);
                    requireString(data, "request_family_id", familyId.value(), operationId);
                    requireInt(data, "request_tier", tier, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountyContractStartResult result = new BountyContractStartResult(
                            contractFrom(data.get("contract")),
                            longValue(data, "wallet_balance_minor"),
                            longValue(data, "wallet_state_version")
                    );
                    connection.commit();
                    return result;
                }

                // New contracts select the highest configured version only after replay has been ruled out.
                BountyTierDefinition definition = catalog.require(familyId, tier);
                CoinWalletSnapshot wallet = readWallet(connection, playerId, true);
                long feeMinor = definition.contractFeeMinor();
                if (wallet.balanceMinor() < feeMinor) {
                    throw new BountyException("Insufficient Coin balance for bounty contract");
                }

                long nextWalletBalance = wallet.balanceMinor() - feeMinor;
                long nextWalletVersion = wallet.stateVersion();
                if (feeMinor > 0) {
                    nextWalletVersion = incrementVersion(wallet.stateVersion(), "wallet", playerId);
                    updateWallet(
                            connection,
                            playerId,
                            wallet.stateVersion(),
                            nextWalletBalance,
                            nextWalletVersion
                    );
                    insertCoinLedger(connection, operationId, playerId, feeMinor, normalizedReason);
                }

                UUID contractId = UUID.randomUUID();
                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bounty_contracts(
                            contract_id,
                            player_id,
                            family_id,
                            tier,
                            content_version,
                            status,
                            eligible_kill_progress,
                            required_eligible_kills,
                            summon_authorizations_remaining,
                            fee_operation_id,
                            state_version,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'ACTIVE_HUNT', 0, ?, 0, ?, 0, ?, ?)
                        """)) {
                    statement.setObject(1, contractId);
                    statement.setObject(2, playerId);
                    statement.setString(3, familyId.value());
                    statement.setInt(4, tier);
                    statement.setInt(5, definition.contentVersion());
                    statement.setInt(6, definition.requiredEligibleKills());
                    statement.setObject(7, operationId);
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.setTimestamp(9, Timestamp.from(now));
                    statement.executeUpdate();
                }

                BountyContractStartResult result = new BountyContractStartResult(
                        readContract(connection, contractId, false),
                        nextWalletBalance,
                        nextWalletVersion
                );
                LinkedHashMap<String, Object> resultData = new LinkedHashMap<>();
                resultData.put("request_player_id", playerId.toString());
                resultData.put("request_family_id", familyId.value());
                resultData.put("request_tier", tier);
                resultData.put("reason", normalizedReason);
                resultData.put("fee_minor", feeMinor);
                resultData.put("wallet_balance_minor", result.walletBalanceMinor());
                resultData.put("wallet_state_version", result.walletStateVersion());
                resultData.put("contract", contractMap(result.contract()));
                insertProcessed(connection, operationId, START_OPERATION, resultData);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public BountyContractSnapshot recordEligibleKills(
            UUID operationId,
            UUID contractId,
            UUID playerId,
            int eligibleKills,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(playerId, "playerId");
        if (eligibleKills <= 0) {
            throw new IllegalArgumentException("eligibleKills must be > 0");
        }
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), PROGRESS_OPERATION, operationId);
                    requireUuid(data, "request_contract_id", contractId, operationId);
                    requireUuid(data, "request_player_id", playerId, operationId);
                    requireInt(data, "eligible_kills", eligibleKills, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountyContractSnapshot result = contractFrom(data.get("contract"));
                    connection.commit();
                    return result;
                }

                BountyContractSnapshot current = readContract(connection, contractId, true);
                requireContractOwner(current, playerId);
                if (current.status() != BountyContractStatus.ACTIVE_HUNT) {
                    throw new BountyException("Eligible kills require ACTIVE_HUNT contract");
                }

                int nextProgress;
                try {
                    nextProgress = Math.min(
                            current.requiredEligibleKills(),
                            Math.addExact(current.eligibleKillProgress(), eligibleKills)
                    );
                } catch (ArithmeticException ignored) {
                    nextProgress = current.requiredEligibleKills();
                }
                boolean ready = nextProgress == current.requiredEligibleKills();
                BountyContractStatus nextStatus = ready
                        ? BountyContractStatus.SUMMON_READY
                        : BountyContractStatus.ACTIVE_HUNT;
                int summonAuthorizations = ready ? 1 : 0;
                long nextVersion = incrementVersion(current.stateVersion(), "bounty contract", contractId);
                updateContractProgress(
                        connection,
                        current,
                        nextProgress,
                        nextStatus,
                        summonAuthorizations,
                        nextVersion
                );

                BountyContractSnapshot result = readContract(connection, contractId, false);
                LinkedHashMap<String, Object> resultData = new LinkedHashMap<>();
                resultData.put("request_contract_id", contractId.toString());
                resultData.put("request_player_id", playerId.toString());
                resultData.put("eligible_kills", eligibleKills);
                resultData.put("reason", normalizedReason);
                resultData.put("contract", contractMap(result));
                insertProcessed(connection, operationId, PROGRESS_OPERATION, resultData);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public BountySummonPrepareResult prepareSummon(
            UUID operationId,
            UUID contractId,
            UUID playerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(contractId, "contractId");
        Objects.requireNonNull(playerId, "playerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), PREPARE_SUMMON_OPERATION, operationId);
                    requireUuid(data, "request_contract_id", contractId, operationId);
                    requireUuid(data, "request_player_id", playerId, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountySummonPrepareResult result = new BountySummonPrepareResult(
                            contractFrom(data.get("contract")),
                            summonFrom(data.get("summon")),
                            stringValue(data, "boss_definition_id")
                    );
                    connection.commit();
                    return result;
                }

                BountyContractSnapshot current = readContract(connection, contractId, true);
                requireContractOwner(current, playerId);
                if (current.status() != BountyContractStatus.SUMMON_READY
                        || current.summonAuthorizationsRemaining() != 1) {
                    throw new BountyException("Contract does not own one ready summon authorization");
                }
                BountyTierDefinition definition = requireFrozenDefinition(current);
                long nextVersion = incrementVersion(current.stateVersion(), "bounty contract", contractId);
                Instant now = clock.instant();

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE bounty_contracts
                        SET status = 'SUMMONED',
                            summon_authorizations_remaining = 0,
                            state_version = ?,
                            updated_at = ?
                        WHERE contract_id = ?
                          AND state_version = ?
                          AND status = 'SUMMON_READY'
                          AND summon_authorizations_remaining = 1
                        """)) {
                    statement.setLong(1, nextVersion);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setObject(3, contractId);
                    statement.setLong(4, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new BountyException("Bounty contract changed concurrently while preparing summon");
                    }
                }

                UUID summonId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bounty_summons(
                            summon_id, contract_id, status, state_version, created_at
                        ) VALUES (?, ?, 'READY', 0, ?)
                        """)) {
                    statement.setObject(1, summonId);
                    statement.setObject(2, contractId);
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.executeUpdate();
                }

                BountySummonPrepareResult result = new BountySummonPrepareResult(
                        readContract(connection, contractId, false),
                        readSummon(connection, summonId, false),
                        definition.bossDefinitionId()
                );
                LinkedHashMap<String, Object> resultData = new LinkedHashMap<>();
                resultData.put("request_contract_id", contractId.toString());
                resultData.put("request_player_id", playerId.toString());
                resultData.put("reason", normalizedReason);
                resultData.put("boss_definition_id", result.bossDefinitionId());
                resultData.put("contract", contractMap(result.contract()));
                resultData.put("summon", summonMap(result.summon()));
                insertProcessed(connection, operationId, PREPARE_SUMMON_OPERATION, resultData);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public BountySummonLeaseResult claimSummon(
            UUID operationId,
            UUID summonId,
            String backendId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(summonId, "summonId");
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), CLAIM_SUMMON_OPERATION, operationId);
                    requireUuid(data, "request_summon_id", summonId, operationId);
                    requireString(data, "request_backend_id", normalizedBackendId, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountySummonLeaseResult result = new BountySummonLeaseResult(
                            summonFrom(data.get("summon")),
                            stringValue(data, "boss_definition_id")
                    );
                    connection.commit();
                    return result;
                }

                BountySummonSnapshot current = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                boolean claimable = current.status() == BountySummonStatus.READY
                        || (current.status() == BountySummonStatus.ACTIVE
                        && current.leaseExpiresAt() != null
                        && !current.leaseExpiresAt().isAfter(now));
                if (!claimable) {
                    throw new BountyException("Bounty summon is not claimable: " + summonId);
                }
                BountyContractSnapshot contract = readContract(connection, current.contractId(), false);
                if (contract.status() != BountyContractStatus.SUMMONED) {
                    throw new BountyException("Bounty summon contract is not in SUMMONED state");
                }
                BountyTierDefinition definition = requireFrozenDefinition(contract);
                long nextVersion = incrementVersion(current.stateVersion(), "bounty summon", summonId);
                Instant leaseExpiresAt = now.plus(summonLeaseDuration);
                Instant activatedAt = current.activatedAt() == null ? now : current.activatedAt();

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE bounty_summons
                        SET status = 'ACTIVE',
                            owner_backend_id = ?,
                            lease_expires_at = ?,
                            state_version = ?,
                            activated_at = ?,
                            resolved_at = NULL
                        WHERE summon_id = ? AND state_version = ?
                        """)) {
                    statement.setString(1, normalizedBackendId);
                    statement.setTimestamp(2, Timestamp.from(leaseExpiresAt));
                    statement.setLong(3, nextVersion);
                    statement.setTimestamp(4, Timestamp.from(activatedAt));
                    statement.setObject(5, summonId);
                    statement.setLong(6, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new BountyException("Bounty summon changed concurrently while claiming");
                    }
                }

                BountySummonLeaseResult result = new BountySummonLeaseResult(
                        readSummon(connection, summonId, false),
                        definition.bossDefinitionId()
                );
                insertLeaseProcessed(
                        connection,
                        operationId,
                        CLAIM_SUMMON_OPERATION,
                        summonId,
                        normalizedBackendId,
                        null,
                        normalizedReason,
                        result
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public BountySummonLeaseResult heartbeatSummon(
            UUID operationId,
            UUID summonId,
            String backendId,
            long expectedSummonStateVersion,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(summonId, "summonId");
        if (expectedSummonStateVersion < 0) {
            throw new IllegalArgumentException("expectedSummonStateVersion must be >= 0");
        }
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(
                            processed.orElseThrow(),
                            HEARTBEAT_SUMMON_OPERATION,
                            operationId
                    );
                    requireUuid(data, "request_summon_id", summonId, operationId);
                    requireString(data, "request_backend_id", normalizedBackendId, operationId);
                    requireLong(data, "expected_summon_state_version", expectedSummonStateVersion, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountySummonLeaseResult result = new BountySummonLeaseResult(
                            summonFrom(data.get("summon")),
                            stringValue(data, "boss_definition_id")
                    );
                    connection.commit();
                    return result;
                }

                BountySummonSnapshot current = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                requireActiveLease(current, normalizedBackendId, expectedSummonStateVersion, now);
                BountyContractSnapshot contract = readContract(connection, current.contractId(), false);
                BountyTierDefinition definition = requireFrozenDefinition(contract);
                long nextVersion = incrementVersion(current.stateVersion(), "bounty summon", summonId);
                Instant leaseExpiresAt = now.plus(summonLeaseDuration);

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE bounty_summons
                        SET lease_expires_at = ?, state_version = ?
                        WHERE summon_id = ? AND state_version = ? AND status = 'ACTIVE'
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(leaseExpiresAt));
                    statement.setLong(2, nextVersion);
                    statement.setObject(3, summonId);
                    statement.setLong(4, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new BountyException("Bounty summon changed concurrently during heartbeat");
                    }
                }

                BountySummonLeaseResult result = new BountySummonLeaseResult(
                        readSummon(connection, summonId, false),
                        definition.bossDefinitionId()
                );
                insertLeaseProcessed(
                        connection,
                        operationId,
                        HEARTBEAT_SUMMON_OPERATION,
                        summonId,
                        normalizedBackendId,
                        expectedSummonStateVersion,
                        normalizedReason,
                        result
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public BountyCompletionResult completeBoss(
            UUID operationId,
            UUID summonId,
            String backendId,
            long expectedSummonStateVersion,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(summonId, "summonId");
        if (expectedSummonStateVersion < 0) {
            throw new IllegalArgumentException("expectedSummonStateVersion must be >= 0");
        }
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), COMPLETE_OPERATION, operationId);
                    requireUuid(data, "request_summon_id", summonId, operationId);
                    requireString(data, "request_backend_id", normalizedBackendId, operationId);
                    requireLong(data, "expected_summon_state_version", expectedSummonStateVersion, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountyCompletionResult result = new BountyCompletionResult(
                            contractFrom(data.get("contract")),
                            longMap(data.get("pouch_rewards"))
                    );
                    connection.commit();
                    return result;
                }

                BountySummonSnapshot summon = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                requireActiveLease(summon, normalizedBackendId, expectedSummonStateVersion, now);
                BountyContractSnapshot contract = readContract(connection, summon.contractId(), true);
                if (contract.status() != BountyContractStatus.SUMMONED) {
                    throw new BountyException("Bounty contract is not SUMMONED at boss completion");
                }
                BountyTierDefinition definition = requireFrozenDefinition(contract);
                Map<String, Long> resolvedRewards = normalizeRewards(
                        rewards.resolve(contract.contractId(), definition),
                        definition
                );

                ensurePouch(connection, contract.playerId(), contract.familyId());
                for (Map.Entry<String, Long> reward : resolvedRewards.entrySet()) {
                    creditPouch(
                            connection,
                            contract.playerId(),
                            contract.familyId(),
                            reward.getKey(),
                            reward.getValue()
                    );
                }

                long nextSummonVersion = incrementVersion(summon.stateVersion(), "bounty summon", summonId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE bounty_summons
                        SET status = 'DEFEATED',
                            lease_expires_at = NULL,
                            state_version = ?,
                            resolved_at = ?
                        WHERE summon_id = ? AND state_version = ? AND status = 'ACTIVE'
                        """)) {
                    statement.setLong(1, nextSummonVersion);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setObject(3, summonId);
                    statement.setLong(4, summon.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new BountyException("Bounty summon changed concurrently during completion");
                    }
                }

                long nextContractVersion = incrementVersion(
                        contract.stateVersion(),
                        "bounty contract",
                        contract.contractId()
                );
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE bounty_contracts
                        SET status = 'COMPLETED',
                            reward_operation_id = ?,
                            state_version = ?,
                            updated_at = ?,
                            completed_at = ?
                        WHERE contract_id = ? AND state_version = ? AND status = 'SUMMONED'
                        """)) {
                    Timestamp completedAt = Timestamp.from(now);
                    statement.setObject(1, operationId);
                    statement.setLong(2, nextContractVersion);
                    statement.setTimestamp(3, completedAt);
                    statement.setTimestamp(4, completedAt);
                    statement.setObject(5, contract.contractId());
                    statement.setLong(6, contract.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new BountyException("Bounty contract changed concurrently during completion");
                    }
                }

                BountyCompletionResult result = new BountyCompletionResult(
                        readContract(connection, contract.contractId(), false),
                        resolvedRewards
                );
                LinkedHashMap<String, Object> resultData = requestForLease(
                        summonId,
                        normalizedBackendId,
                        expectedSummonStateVersion,
                        normalizedReason
                );
                resultData.put("contract", contractMap(result.contract()));
                resultData.put("pouch_rewards", result.pouchRewards());
                insertProcessed(connection, operationId, COMPLETE_OPERATION, resultData);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public BountyContractSnapshot failBoss(
            UUID operationId,
            UUID summonId,
            String backendId,
            long expectedSummonStateVersion,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(summonId, "summonId");
        if (expectedSummonStateVersion < 0) {
            throw new IllegalArgumentException("expectedSummonStateVersion must be >= 0");
        }
        String normalizedBackendId = requireNonBlank(backendId, "backendId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), FAIL_OPERATION, operationId);
                    requireUuid(data, "request_summon_id", summonId, operationId);
                    requireString(data, "request_backend_id", normalizedBackendId, operationId);
                    requireLong(data, "expected_summon_state_version", expectedSummonStateVersion, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    BountyContractSnapshot result = contractFrom(data.get("contract"));
                    connection.commit();
                    return result;
                }

                BountySummonSnapshot summon = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                requireActiveLease(summon, normalizedBackendId, expectedSummonStateVersion, now);
                BountyContractSnapshot contract = readContract(connection, summon.contractId(), true);
                if (contract.status() != BountyContractStatus.SUMMONED) {
                    throw new BountyException("Bounty contract is not SUMMONED at failure");
                }
                // Missing historical content is corruption even on the failure path; fail closed rather than erase it.
                requireFrozenDefinition(contract);

                long nextSummonVersion = incrementVersion(summon.stateVersion(), "bounty summon", summonId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE bounty_summons
                        SET status = 'FAILED',
                            lease_expires_at = NULL,
                            state_version = ?,
                            resolved_at = ?
                        WHERE summon_id = ? AND state_version = ? AND status = 'ACTIVE'
                        """)) {
                    statement.setLong(1, nextSummonVersion);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setObject(3, summonId);
                    statement.setLong(4, summon.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new BountyException("Bounty summon changed concurrently during failure");
                    }
                }

                long nextContractVersion = incrementVersion(
                        contract.stateVersion(),
                        "bounty contract",
                        contract.contractId()
                );
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE bounty_contracts
                        SET status = 'FAILED',
                            state_version = ?,
                            updated_at = ?,
                            completed_at = ?
                        WHERE contract_id = ? AND state_version = ? AND status = 'SUMMONED'
                        """)) {
                    Timestamp finishedAt = Timestamp.from(now);
                    statement.setLong(1, nextContractVersion);
                    statement.setTimestamp(2, finishedAt);
                    statement.setTimestamp(3, finishedAt);
                    statement.setObject(4, contract.contractId());
                    statement.setLong(5, contract.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new BountyException("Bounty contract changed concurrently during failure");
                    }
                }

                BountyContractSnapshot result = readContract(connection, contract.contractId(), false);
                LinkedHashMap<String, Object> resultData = requestForLease(
                        summonId,
                        normalizedBackendId,
                        expectedSummonStateVersion,
                        normalizedReason
                );
                resultData.put("contract", contractMap(result));
                insertProcessed(connection, operationId, FAIL_OPERATION, resultData);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private BountyTierDefinition requireFrozenDefinition(BountyContractSnapshot contract) {
        return catalog.require(contract.familyId(), contract.tier(), contract.contentVersion());
    }

    private static void insertLeaseProcessed(
            Connection connection,
            UUID operationId,
            String operationType,
            UUID summonId,
            String backendId,
            Long expectedStateVersion,
            String reason,
            BountySummonLeaseResult result
    ) throws SQLException {
        LinkedHashMap<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("request_summon_id", summonId.toString());
        resultData.put("request_backend_id", backendId);
        if (expectedStateVersion != null) {
            resultData.put("expected_summon_state_version", expectedStateVersion);
        }
        resultData.put("reason", reason);
        resultData.put("boss_definition_id", result.bossDefinitionId());
        resultData.put("summon", summonMap(result.summon()));
        insertProcessed(connection, operationId, operationType, resultData);
    }

    private static LinkedHashMap<String, Object> requestForLease(
            UUID summonId,
            String backendId,
            long expectedStateVersion,
            String reason
    ) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("request_summon_id", summonId.toString());
        result.put("request_backend_id", backendId);
        result.put("expected_summon_state_version", expectedStateVersion);
        result.put("reason", reason);
        return result;
    }

    private static void requireContractOwner(BountyContractSnapshot contract, UUID playerId) {
        if (!contract.playerId().equals(playerId)) {
            throw new BountyException("Bounty contract does not belong to player " + playerId);
        }
    }

    private static void requireActiveLease(
            BountySummonSnapshot summon,
            String backendId,
            long expectedStateVersion,
            Instant now
    ) {
        if (summon.status() != BountySummonStatus.ACTIVE
                || !backendId.equals(summon.ownerBackendId())
                || summon.stateVersion() != expectedStateVersion
                || summon.leaseExpiresAt() == null
                || !summon.leaseExpiresAt().isAfter(now)) {
            throw new BountyException("Stale or non-owning bounty summon lease for " + summon.summonId());
        }
    }

    private static BountyContractSnapshot readContract(Connection connection, UUID contractId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT player_id,
                       family_id,
                       tier,
                       content_version,
                       status,
                       eligible_kill_progress,
                       required_eligible_kills,
                       summon_authorizations_remaining,
                       state_version
                FROM bounty_contracts
                WHERE contract_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, contractId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new BountyException("Unknown bounty contract: " + contractId);
                }
                return new BountyContractSnapshot(
                        contractId,
                        row.getObject("player_id", UUID.class),
                        new BountyFamilyId(row.getString("family_id")),
                        row.getInt("tier"),
                        row.getInt("content_version"),
                        BountyContractStatus.valueOf(row.getString("status")),
                        row.getInt("eligible_kill_progress"),
                        row.getInt("required_eligible_kills"),
                        row.getInt("summon_authorizations_remaining"),
                        row.getLong("state_version")
                );
            }
        }
    }

    private static BountySummonSnapshot readSummon(Connection connection, UUID summonId, boolean forUpdate)
            throws SQLException {
        String sql = """
                SELECT contract_id,
                       status,
                       owner_backend_id,
                       lease_expires_at,
                       state_version,
                       created_at,
                       activated_at,
                       resolved_at
                FROM bounty_summons
                WHERE summon_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, summonId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new BountyException("Unknown bounty summon: " + summonId);
                }
                Timestamp lease = row.getTimestamp("lease_expires_at");
                Timestamp activated = row.getTimestamp("activated_at");
                Timestamp resolved = row.getTimestamp("resolved_at");
                return new BountySummonSnapshot(
                        summonId,
                        row.getObject("contract_id", UUID.class),
                        BountySummonStatus.valueOf(row.getString("status")),
                        row.getString("owner_backend_id"),
                        lease == null ? null : lease.toInstant(),
                        row.getLong("state_version"),
                        row.getTimestamp("created_at").toInstant(),
                        activated == null ? null : activated.toInstant(),
                        resolved == null ? null : resolved.toInstant()
                );
            }
        }
    }

    private static void updateContractProgress(
            Connection connection,
            BountyContractSnapshot current,
            int nextProgress,
            BountyContractStatus nextStatus,
            int summonAuthorizations,
            long nextVersion
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bounty_contracts
                SET eligible_kill_progress = ?,
                    status = ?,
                    summon_authorizations_remaining = ?,
                    state_version = ?,
                    updated_at = NOW()
                WHERE contract_id = ? AND state_version = ? AND status = 'ACTIVE_HUNT'
                """)) {
            statement.setInt(1, nextProgress);
            statement.setString(2, nextStatus.name());
            statement.setInt(3, summonAuthorizations);
            statement.setLong(4, nextVersion);
            statement.setObject(5, current.contractId());
            statement.setLong(6, current.stateVersion());
            if (statement.executeUpdate() != 1) {
                throw new BountyException("Bounty contract changed concurrently while recording kills");
            }
        }
    }

    private static void ensurePouch(Connection connection, UUID playerId, BountyFamilyId familyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bounty_pouches(player_id, family_id)
                VALUES (?, ?)
                ON CONFLICT (player_id, family_id) DO NOTHING
                """)) {
            statement.setObject(1, playerId);
            statement.setString(2, familyId.value());
            statement.executeUpdate();
        }
    }

    private static void creditPouch(
            Connection connection,
            UUID playerId,
            BountyFamilyId familyId,
            String commodityDefinitionId,
            long quantity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bounty_pouch_balances(
                    player_id, family_id, commodity_definition_id, quantity, state_version
                ) VALUES (?, ?, ?, ?, 0)
                ON CONFLICT (player_id, family_id, commodity_definition_id)
                DO UPDATE SET
                    quantity = bounty_pouch_balances.quantity + EXCLUDED.quantity,
                    state_version = bounty_pouch_balances.state_version + 1,
                    updated_at = NOW()
                """)) {
            statement.setObject(1, playerId);
            statement.setString(2, familyId.value());
            statement.setString(3, commodityDefinitionId);
            statement.setLong(4, quantity);
            statement.executeUpdate();
        }
    }

    private static Map<String, Long> normalizeRewards(
            Map<String, Long> rawRewards,
            BountyTierDefinition definition
    ) {
        Objects.requireNonNull(rawRewards, "resolved bounty rewards");
        Set<String> allowed = Set.copyOf(definition.materialDefinitionIds());
        LinkedHashMap<String, Long> normalized = new LinkedHashMap<>();
        rawRewards.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String definitionId = entry.getKey();
                    Long quantity = entry.getValue();
                    if (definitionId == null || definitionId.isBlank() || quantity == null || quantity <= 0) {
                        throw new BountyException("Bounty reward resolver returned invalid commodity reward");
                    }
                    String normalizedId = definitionId.trim();
                    if (!allowed.contains(normalizedId)) {
                        throw new BountyException(
                                "Bounty reward resolver returned material outside tier allowlist: " + normalizedId
                        );
                    }
                    try {
                        normalized.merge(normalizedId, quantity, Math::addExact);
                    } catch (ArithmeticException exception) {
                        throw new BountyException("Bounty reward quantity overflow for " + normalizedId, exception);
                    }
                });
        if (normalized.isEmpty()) {
            throw new BountyException("Successful bounty boss must resolve at least one material reward");
        }
        return Map.copyOf(normalized);
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
                    throw new BountyException("Wallet does not exist for player_id " + playerId);
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
                throw new BountyException("Wallet changed concurrently for " + playerId);
            }
        }
    }

    private static void insertCoinLedger(
            Connection connection,
            UUID operationId,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        if (amountMinor <= 0) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, 0, ?, ?, ?, ?, 'DEBIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setObject(2, playerId);
            statement.setString(3, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(5, amountMinor);
            statement.setString(6, reason);
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
            throw new BountyException(
                    "operation_id " + operationId + " already belongs to operation type " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> contractMap(BountyContractSnapshot contract) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("contract_id", contract.contractId().toString());
        value.put("player_id", contract.playerId().toString());
        value.put("family_id", contract.familyId().value());
        value.put("tier", contract.tier());
        value.put("content_version", contract.contentVersion());
        value.put("status", contract.status().name());
        value.put("eligible_kill_progress", contract.eligibleKillProgress());
        value.put("required_eligible_kills", contract.requiredEligibleKills());
        value.put("summon_authorizations_remaining", contract.summonAuthorizationsRemaining());
        value.put("state_version", contract.stateVersion());
        return value;
    }

    private static BountyContractSnapshot contractFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "contract");
        return new BountyContractSnapshot(
                uuidValue(value, "contract_id"),
                uuidValue(value, "player_id"),
                new BountyFamilyId(stringValue(value, "family_id")),
                intValue(value, "tier"),
                intValue(value, "content_version"),
                BountyContractStatus.valueOf(stringValue(value, "status")),
                intValue(value, "eligible_kill_progress"),
                intValue(value, "required_eligible_kills"),
                intValue(value, "summon_authorizations_remaining"),
                longValue(value, "state_version")
        );
    }

    private static Map<String, Object> summonMap(BountySummonSnapshot summon) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("summon_id", summon.summonId().toString());
        value.put("contract_id", summon.contractId().toString());
        value.put("status", summon.status().name());
        value.put("owner_backend_id", summon.ownerBackendId());
        value.put("lease_expires_at", instantString(summon.leaseExpiresAt()));
        value.put("state_version", summon.stateVersion());
        value.put("created_at", summon.createdAt().toString());
        value.put("activated_at", instantString(summon.activatedAt()));
        value.put("resolved_at", instantString(summon.resolvedAt()));
        return value;
    }

    private static BountySummonSnapshot summonFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "summon");
        return new BountySummonSnapshot(
                uuidValue(value, "summon_id"),
                uuidValue(value, "contract_id"),
                BountySummonStatus.valueOf(stringValue(value, "status")),
                nullableString(value, "owner_backend_id"),
                nullableInstant(value, "lease_expires_at"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "created_at")),
                nullableInstant(value, "activated_at"),
                nullableInstant(value, "resolved_at")
        );
    }

    private static Map<String, Long> longMap(Object raw) {
        Map<String, Object> value = objectMap(raw, "pouch_rewards");
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        value.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!(entry.getValue() instanceof Number number)) {
                throw new BountyException("Processed bounty reward is not numeric: " + entry.getKey());
            }
            result.put(entry.getKey(), number.longValue());
        });
        return Map.copyOf(result);
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new BountyException("Failed to parse processed bounty result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BountyException("Failed to serialize processed bounty result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new BountyException("Processed bounty result field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new BountyException("Processed bounty result is missing field: " + field);
        }
        return Objects.toString(raw);
    }

    private static String nullableString(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw == null ? null : Objects.toString(raw);
    }

    private static int intValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new BountyException("Processed bounty result field is not numeric: " + field);
        }
        return number.intValue();
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new BountyException("Processed bounty result field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static Instant nullableInstant(Map<String, Object> value, String field) {
        String raw = nullableString(value, field);
        return raw == null ? null : Instant.parse(raw);
    }

    private static String instantString(Instant value) {
        return value == null ? null : value.toString();
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw new BountyException("operation_id reused with different bounty request: " + operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw new BountyException("operation_id reused with different bounty request: " + operationId);
        }
    }

    private static void requireInt(Map<String, Object> data, String field, int expected, UUID operationId) {
        if (intValue(data, field) != expected) {
            throw new BountyException("operation_id reused with different bounty request: " + operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw new BountyException("operation_id reused with different bounty request: " + operationId);
        }
    }

    private static long incrementVersion(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new BountyException(target + " state_version overflow for " + id, exception);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
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

    private record ProcessedOperation(String operationType, Map<String, Object> result) {
        private ProcessedOperation {
            operationType = Objects.requireNonNull(operationType, "operationType");
            result = Map.copyOf(Objects.requireNonNull(result, "result"));
        }
    }
}
