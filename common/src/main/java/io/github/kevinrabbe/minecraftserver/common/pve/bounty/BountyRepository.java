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
 * Runtime bosses are disposable; the persistent summon lease is not.
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
    private final Clock clock;
    private final Duration summonLeaseDuration;

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
        if (summonLeaseDuration.isZero() || summonLeaseDuration.isNegative()
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
        BountyTierDefinition definition = catalog.require(Objects.requireNonNull(familyId, "familyId"), tier);
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedStart> processed = findProcessedStart(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedStart previous = processed.orElseThrow();
                    previous.requireSameRequest(playerId, familyId, tier, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
                }

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
                    insertCoinLedger(
                            connection,
                            operationId,
                            playerId,
                            feeMinor,
                            normalizedReason
                    );
                }

                UUID contractId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO bounty_contracts(
                            contract_id,
                            player_id,
                            family_id,
                            tier,
                            status,
                            eligible_kill_progress,
                            required_eligible_kills,
                            summon_authorizations_remaining,
                            fee_operation_id,
                            state_version,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, 'ACTIVE_HUNT', 0, ?, 0, ?, 0, ?, ?)
                        """)) {
                    Instant now = clock.instant();
                    statement.setObject(1, contractId);
                    statement.setObject(2, playerId);
                    statement.setString(3, familyId.value());
                    statement.setInt(4, tier);
                    statement.setInt(5, definition.requiredEligibleKills());
                    statement.setObject(6, operationId);
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.executeUpdate();
                }

                BountyContractSnapshot contract = readContract(connection, contractId, false);
                BountyContractStartResult result = new BountyContractStartResult(
                        contract,
                        nextWalletBalance,
                        nextWalletVersion
                );
                insertProcessedStart(connection, operationId, result, feeMinor, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Adds one authoritative eligible-kill event batch and transitions to SUMMON_READY exactly at the requirement. */
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
                Optional<ProcessedProgress> processed = findProcessedProgress(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedProgress previous = processed.orElseThrow();
                    previous.requireSameRequest(contractId, playerId, eligibleKills, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
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
                } catch (ArithmeticException exception) {
                    nextProgress = current.requiredEligibleKills();
                }
                boolean ready = nextProgress >= current.requiredEligibleKills();
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
                insertProcessedProgress(
                        connection,
                        operationId,
                        result,
                        eligibleKills,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Converts the one summon authorization into one durable READY summon before any runtime boss is spawned. */
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
                Optional<ProcessedPrepare> processed = findProcessedPrepare(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedPrepare previous = processed.orElseThrow();
                    previous.requireSameRequest(contractId, playerId, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
                }

                BountyContractSnapshot current = readContract(connection, contractId, true);
                requireContractOwner(current, playerId);
                if (current.status() != BountyContractStatus.SUMMON_READY
                        || current.summonAuthorizationsRemaining() != 1) {
                    throw new BountyException("Contract does not own one ready summon authorization");
                }
                BountyTierDefinition definition = catalog.require(current.familyId(), current.tier());
                long nextVersion = incrementVersion(current.stateVersion(), "bounty contract", contractId);
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
                    statement.setTimestamp(2, Timestamp.from(clock.instant()));
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
                    statement.setTimestamp(3, Timestamp.from(clock.instant()));
                    statement.executeUpdate();
                }

                BountySummonPrepareResult result = new BountySummonPrepareResult(
                        readContract(connection, contractId, false),
                        readSummon(connection, summonId, false),
                        definition.bossDefinitionId()
                );
                insertProcessedPrepare(connection, operationId, result, normalizedReason);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Claims/reclaims a READY or expired ACTIVE summon for one backend. */
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
                Optional<ProcessedLease> processed = findProcessedLease(
                        connection,
                        operationId,
                        CLAIM_SUMMON_OPERATION
                );
                if (processed.isPresent()) {
                    ProcessedLease previous = processed.orElseThrow();
                    previous.requireSameRequest(summonId, normalizedBackendId, normalizedReason, operationId);
                    connection.commit();
                    return previous.result();
                }

                BountySummonSnapshot current = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                boolean claimable = current.status() == BountySummonStatus.READY
                        || (current.status() == BountySummonStatus.ACTIVE
                        && !current.leaseExpiresAt().isAfter(now));
                if (!claimable) {
                    throw new BountyException("Bounty summon is not claimable: " + summonId);
                }
                BountyContractSnapshot contract = readContract(connection, current.contractId(), false);
                if (contract.status() != BountyContractStatus.SUMMONED) {
                    throw new BountyException("Bounty summon contract is not in SUMMONED state");
                }
                BountyTierDefinition definition = catalog.require(contract.familyId(), contract.tier());
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
                insertProcessedLease(
                        connection,
                        operationId,
                        CLAIM_SUMMON_OPERATION,
                        result,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Extends only the currently owned non-expired backend lease. */
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
                Optional<ProcessedHeartbeat> processed = findProcessedHeartbeat(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedHeartbeat previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            summonId,
                            normalizedBackendId,
                            expectedSummonStateVersion,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                BountySummonSnapshot current = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                requireActiveLease(current, normalizedBackendId, expectedSummonStateVersion, now);
                BountyContractSnapshot contract = readContract(connection, current.contractId(), false);
                BountyTierDefinition definition = catalog.require(contract.familyId(), contract.tier());
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
                insertProcessedHeartbeat(
                        connection,
                        operationId,
                        result,
                        expectedSummonStateVersion,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Defeats the leased boss exactly once and credits only configured family materials into the family pouch. */
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
                Optional<ProcessedCompletion> processed = findProcessedCompletion(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedCompletion previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            summonId,
                            normalizedBackendId,
                            expectedSummonStateVersion,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                BountySummonSnapshot summon = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                requireActiveLease(summon, normalizedBackendId, expectedSummonStateVersion, now);
                BountyContractSnapshot contract = readContract(connection, summon.contractId(), true);
                if (contract.status() != BountyContractStatus.SUMMONED) {
                    throw new BountyException("Bounty contract is not SUMMONED at boss completion");
                }
                BountyTierDefinition definition = catalog.require(contract.familyId(), contract.tier());
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
                insertProcessedCompletion(
                        connection,
                        operationId,
                        result,
                        summonId,
                        normalizedBackendId,
                        expectedSummonStateVersion,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    /** Explicit gameplay failure consumes the prepared bounty; backend crashes should instead allow lease expiry/reclaim. */
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
                Optional<ProcessedFailure> processed = findProcessedFailure(connection, operationId);
                if (processed.isPresent()) {
                    ProcessedFailure previous = processed.orElseThrow();
                    previous.requireSameRequest(
                            summonId,
                            normalizedBackendId,
                            expectedSummonStateVersion,
                            normalizedReason,
                            operationId
                    );
                    connection.commit();
                    return previous.result();
                }

                BountySummonSnapshot summon = readSummon(connection, summonId, true);
                Instant now = clock.instant();
                requireActiveLease(summon, normalizedBackendId, expectedSummonStateVersion, now);
                BountyContractSnapshot contract = readContract(connection, summon.contractId(), true);
                if (contract.status() != BountyContractStatus.SUMMONED) {
                    throw new BountyException("Bounty contract is not SUMMONED at failure");
                }

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
                insertProcessedFailure(
                        connection,
                        operationId,
                        result,
                        summonId,
                        normalizedBackendId,
                        expectedSummonStateVersion,
                        normalizedReason
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
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
                    normalized.put(normalizedId, quantity);
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

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BountyException("Failed to serialize bounty result", exception);
        }
    }

    private static Map<String, Long> readLongMap(String value) {
        try {
            return JSON.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new BountyException("Failed to parse processed bounty rewards", exception);
        }
    }

    private static String requireField(ResultSet row, String field) throws SQLException {
        String value = row.getString(field);
        if (value == null) {
            throw new BountyException("Processed bounty result is missing field: " + field);
        }
        return value;
    }

    private static void requireOperationType(String actual, String expected, UUID operationId) {
        if (!expected.equals(actual)) {
            throw new BountyException(
                    "operation_id " + operationId + " already belongs to operation type " + actual
            );
        }
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void insertProcessedStart(
            Connection connection,
            UUID operationId,
            BountyContractStartResult result,
            long feeMinor,
            String reason
    ) throws SQLException {
        BountyContractSnapshot contract = result.contract();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'contract_id', ?, 'player_id', ?, 'family_id', ?, 'tier', ?, 'status', ?,
                    'eligible_kill_progress', ?, 'required_eligible_kills', ?,
                    'summon_authorizations_remaining', ?, 'contract_state_version', ?,
                    'wallet_balance_minor', ?, 'wallet_state_version', ?, 'fee_minor', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, START_OPERATION);
            bindContract(statement, 3, contract);
            statement.setLong(12, result.walletBalanceMinor());
            statement.setLong(13, result.walletStateVersion());
            statement.setLong(14, feeMinor);
            statement.setString(15, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedStart> findProcessedStart(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(contractProcessedSelect("""
                result ->> 'wallet_balance_minor' AS wallet_balance_minor,
                result ->> 'wallet_state_version' AS wallet_state_version,
                result ->> 'fee_minor' AS fee_minor,
                result ->> 'reason' AS reason
                """))) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                requireOperationType(row.getString("operation_type"), START_OPERATION, operationId);
                BountyContractSnapshot contract = readProcessedContract(row);
                return Optional.of(new ProcessedStart(
                        new BountyContractStartResult(
                                contract,
                                Long.parseLong(requireField(row, "wallet_balance_minor")),
                                Long.parseLong(requireField(row, "wallet_state_version"))
                        ),
                        Long.parseLong(requireField(row, "fee_minor")),
                        requireField(row, "reason")
                ));
            }
        }
    }

    private static void insertProcessedProgress(
            Connection connection,
            UUID operationId,
            BountyContractSnapshot result,
            int eligibleKills,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'contract_id', ?, 'player_id', ?, 'family_id', ?, 'tier', ?, 'status', ?,
                    'eligible_kill_progress', ?, 'required_eligible_kills', ?,
                    'summon_authorizations_remaining', ?, 'contract_state_version', ?,
                    'eligible_kills', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, PROGRESS_OPERATION);
            bindContract(statement, 3, result);
            statement.setInt(12, eligibleKills);
            statement.setString(13, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedProgress> findProcessedProgress(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(contractProcessedSelect("""
                result ->> 'eligible_kills' AS eligible_kills,
                result ->> 'reason' AS reason
                """))) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                requireOperationType(row.getString("operation_type"), PROGRESS_OPERATION, operationId);
                return Optional.of(new ProcessedProgress(
                        readProcessedContract(row),
                        Integer.parseInt(requireField(row, "eligible_kills")),
                        requireField(row, "reason")
                ));
            }
        }
    }

    private static void insertProcessedPrepare(
            Connection connection,
            UUID operationId,
            BountySummonPrepareResult result,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'contract_id', ?, 'player_id', ?, 'family_id', ?, 'tier', ?, 'status', ?,
                    'eligible_kill_progress', ?, 'required_eligible_kills', ?,
                    'summon_authorizations_remaining', ?, 'contract_state_version', ?,
                    'summon_id', ?, 'boss_definition_id', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, PREPARE_SUMMON_OPERATION);
            bindContract(statement, 3, result.contract());
            statement.setString(12, result.summon().summonId().toString());
            statement.setString(13, result.bossDefinitionId());
            statement.setString(14, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedPrepare> findProcessedPrepare(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(contractProcessedSelect("""
                result ->> 'summon_id' AS summon_id,
                result ->> 'boss_definition_id' AS boss_definition_id,
                result ->> 'reason' AS reason
                """))) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                requireOperationType(row.getString("operation_type"), PREPARE_SUMMON_OPERATION, operationId);
                BountyContractSnapshot contract = readProcessedContract(row);
                UUID summonId = UUID.fromString(requireField(row, "summon_id"));
                BountySummonSnapshot summon = readSummon(connection, summonId, false);
                return Optional.of(new ProcessedPrepare(
                        new BountySummonPrepareResult(
                                contract,
                                summon,
                                requireField(row, "boss_definition_id")
                        ),
                        requireField(row, "reason")
                ));
            }
        }
    }

    private static void insertProcessedLease(
            Connection connection,
            UUID operationId,
            String operationType,
            BountySummonLeaseResult result,
            String reason
    ) throws SQLException {
        BountySummonSnapshot summon = result.summon();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'summon_id', ?, 'contract_id', ?, 'owner_backend_id', ?,
                    'lease_expires_at', ?, 'summon_state_version', ?,
                    'boss_definition_id', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, operationType);
            statement.setString(3, summon.summonId().toString());
            statement.setString(4, summon.contractId().toString());
            statement.setString(5, summon.ownerBackendId());
            statement.setString(6, summon.leaseExpiresAt().toString());
            statement.setLong(7, summon.stateVersion());
            statement.setString(8, result.bossDefinitionId());
            statement.setString(9, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedLease> findProcessedLease(
            Connection connection,
            UUID operationId,
            String expectedOperationType
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,
                       result ->> 'summon_id' AS summon_id,
                       result ->> 'contract_id' AS contract_id,
                       result ->> 'owner_backend_id' AS owner_backend_id,
                       result ->> 'lease_expires_at' AS lease_expires_at,
                       result ->> 'summon_state_version' AS summon_state_version,
                       result ->> 'boss_definition_id' AS boss_definition_id,
                       result ->> 'reason' AS reason
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                requireOperationType(row.getString("operation_type"), expectedOperationType, operationId);
                UUID summonId = UUID.fromString(requireField(row, "summon_id"));
                BountySummonSnapshot current = readSummon(connection, summonId, false);
                BountySummonLeaseResult result = new BountySummonLeaseResult(
                        new BountySummonSnapshot(
                                summonId,
                                UUID.fromString(requireField(row, "contract_id")),
                                BountySummonStatus.ACTIVE,
                                requireField(row, "owner_backend_id"),
                                Instant.parse(requireField(row, "lease_expires_at")),
                                Long.parseLong(requireField(row, "summon_state_version")),
                                current.createdAt(),
                                current.activatedAt(),
                                null
                        ),
                        requireField(row, "boss_definition_id")
                );
                return Optional.of(new ProcessedLease(result, requireField(row, "reason")));
            }
        }
    }

    private static void insertProcessedHeartbeat(
            Connection connection,
            UUID operationId,
            BountySummonLeaseResult result,
            long expectedStateVersion,
            String reason
    ) throws SQLException {
        insertProcessedLease(connection, operationId, HEARTBEAT_SUMMON_OPERATION, result, reason);
        // Bind the original expected version in the immutable result after insertion.
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE processed_operations
                SET result = result || jsonb_build_object('expected_summon_state_version', ?)
                WHERE operation_id = ?
                """)) {
            statement.setLong(1, expectedStateVersion);
            statement.setObject(2, operationId);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedHeartbeat> findProcessedHeartbeat(Connection connection, UUID operationId)
            throws SQLException {
        Optional<ProcessedLease> base = findProcessedLease(connection, operationId, HEARTBEAT_SUMMON_OPERATION);
        if (base.isEmpty()) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT result ->> 'expected_summon_state_version' AS expected_summon_state_version
                FROM processed_operations
                WHERE operation_id = ?
                """)) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return Optional.of(new ProcessedHeartbeat(
                        base.orElseThrow().result(),
                        Long.parseLong(requireField(row, "expected_summon_state_version")),
                        base.orElseThrow().reason()
                ));
            }
        }
    }

    private static void insertProcessedCompletion(
            Connection connection,
            UUID operationId,
            BountyCompletionResult result,
            UUID summonId,
            String backendId,
            long expectedSummonStateVersion,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'contract_id', ?, 'player_id', ?, 'family_id', ?, 'tier', ?, 'status', ?,
                    'eligible_kill_progress', ?, 'required_eligible_kills', ?,
                    'summon_authorizations_remaining', ?, 'contract_state_version', ?,
                    'summon_id', ?, 'backend_id', ?, 'expected_summon_state_version', ?,
                    'pouch_rewards', ?::jsonb, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, COMPLETE_OPERATION);
            bindContract(statement, 3, result.contract());
            statement.setString(12, summonId.toString());
            statement.setString(13, backendId);
            statement.setLong(14, expectedSummonStateVersion);
            statement.setString(15, writeJson(result.pouchRewards()));
            statement.setString(16, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedCompletion> findProcessedCompletion(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(contractProcessedSelect("""
                result ->> 'summon_id' AS summon_id,
                result ->> 'backend_id' AS backend_id,
                result ->> 'expected_summon_state_version' AS expected_summon_state_version,
                result -> 'pouch_rewards' AS pouch_rewards,
                result ->> 'reason' AS reason
                """))) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                requireOperationType(row.getString("operation_type"), COMPLETE_OPERATION, operationId);
                return Optional.of(new ProcessedCompletion(
                        new BountyCompletionResult(
                                readProcessedContract(row),
                                readLongMap(requireField(row, "pouch_rewards"))
                        ),
                        UUID.fromString(requireField(row, "summon_id")),
                        requireField(row, "backend_id"),
                        Long.parseLong(requireField(row, "expected_summon_state_version")),
                        requireField(row, "reason")
                ));
            }
        }
    }

    private static void insertProcessedFailure(
            Connection connection,
            UUID operationId,
            BountyContractSnapshot result,
            UUID summonId,
            String backendId,
            long expectedSummonStateVersion,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, jsonb_build_object(
                    'contract_id', ?, 'player_id', ?, 'family_id', ?, 'tier', ?, 'status', ?,
                    'eligible_kill_progress', ?, 'required_eligible_kills', ?,
                    'summon_authorizations_remaining', ?, 'contract_state_version', ?,
                    'summon_id', ?, 'backend_id', ?, 'expected_summon_state_version', ?, 'reason', ?
                ))
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, FAIL_OPERATION);
            bindContract(statement, 3, result);
            statement.setString(12, summonId.toString());
            statement.setString(13, backendId);
            statement.setLong(14, expectedSummonStateVersion);
            statement.setString(15, reason);
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedFailure> findProcessedFailure(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(contractProcessedSelect("""
                result ->> 'summon_id' AS summon_id,
                result ->> 'backend_id' AS backend_id,
                result ->> 'expected_summon_state_version' AS expected_summon_state_version,
                result ->> 'reason' AS reason
                """))) {
            statement.setObject(1, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                requireOperationType(row.getString("operation_type"), FAIL_OPERATION, operationId);
                return Optional.of(new ProcessedFailure(
                        readProcessedContract(row),
                        UUID.fromString(requireField(row, "summon_id")),
                        requireField(row, "backend_id"),
                        Long.parseLong(requireField(row, "expected_summon_state_version")),
                        requireField(row, "reason")
                ));
            }
        }
    }

    private static String contractProcessedSelect(String extras) {
        return """
                SELECT operation_type,
                       result ->> 'contract_id' AS contract_id,
                       result ->> 'player_id' AS player_id,
                       result ->> 'family_id' AS family_id,
                       result ->> 'tier' AS tier,
                       result ->> 'status' AS status,
                       result ->> 'eligible_kill_progress' AS eligible_kill_progress,
                       result ->> 'required_eligible_kills' AS required_eligible_kills,
                       result ->> 'summon_authorizations_remaining' AS summon_authorizations_remaining,
                       result ->> 'contract_state_version' AS contract_state_version,
                       %s
                FROM processed_operations
                WHERE operation_id = ?
                """.formatted(extras.strip());
    }

    private static void bindContract(PreparedStatement statement, int start, BountyContractSnapshot contract)
            throws SQLException {
        statement.setString(start, contract.contractId().toString());
        statement.setString(start + 1, contract.playerId().toString());
        statement.setString(start + 2, contract.familyId().value());
        statement.setInt(start + 3, contract.tier());
        statement.setString(start + 4, contract.status().name());
        statement.setInt(start + 5, contract.eligibleKillProgress());
        statement.setInt(start + 6, contract.requiredEligibleKills());
        statement.setInt(start + 7, contract.summonAuthorizationsRemaining());
        statement.setLong(start + 8, contract.stateVersion());
    }

    private static BountyContractSnapshot readProcessedContract(ResultSet row) throws SQLException {
        return new BountyContractSnapshot(
                UUID.fromString(requireField(row, "contract_id")),
                UUID.fromString(requireField(row, "player_id")),
                new BountyFamilyId(requireField(row, "family_id")),
                Integer.parseInt(requireField(row, "tier")),
                BountyContractStatus.valueOf(requireField(row, "status")),
                Integer.parseInt(requireField(row, "eligible_kill_progress")),
                Integer.parseInt(requireField(row, "required_eligible_kills")),
                Integer.parseInt(requireField(row, "summon_authorizations_remaining")),
                Long.parseLong(requireField(row, "contract_state_version"))
        );
    }

    private record ProcessedStart(BountyContractStartResult result, long feeMinor, String reason) {
        private void requireSameRequest(
                UUID playerId,
                BountyFamilyId familyId,
                int tier,
                String expectedReason,
                UUID operationId
        ) {
            BountyContractSnapshot contract = result.contract();
            if (!contract.playerId().equals(playerId)
                    || !contract.familyId().equals(familyId)
                    || contract.tier() != tier
                    || !reason.equals(expectedReason)) {
                throw new BountyException("operation_id reused with different bounty start request: " + operationId);
            }
        }
    }

    private record ProcessedProgress(BountyContractSnapshot result, int eligibleKills, String reason) {
        private void requireSameRequest(
                UUID contractId,
                UUID playerId,
                int expectedKills,
                String expectedReason,
                UUID operationId
        ) {
            if (!result.contractId().equals(contractId)
                    || !result.playerId().equals(playerId)
                    || eligibleKills != expectedKills
                    || !reason.equals(expectedReason)) {
                throw new BountyException("operation_id reused with different bounty progress request: " + operationId);
            }
        }
    }

    private record ProcessedPrepare(BountySummonPrepareResult result, String reason) {
        private void requireSameRequest(
                UUID contractId,
                UUID playerId,
                String expectedReason,
                UUID operationId
        ) {
            if (!result.contract().contractId().equals(contractId)
                    || !result.contract().playerId().equals(playerId)
                    || !reason.equals(expectedReason)) {
                throw new BountyException("operation_id reused with different bounty summon request: " + operationId);
            }
        }
    }

    private record ProcessedLease(BountySummonLeaseResult result, String reason) {
        private void requireSameRequest(
                UUID summonId,
                String backendId,
                String expectedReason,
                UUID operationId
        ) {
            if (!result.summon().summonId().equals(summonId)
                    || !result.summon().ownerBackendId().equals(backendId)
                    || !reason.equals(expectedReason)) {
                throw new BountyException("operation_id reused with different bounty summon claim: " + operationId);
            }
        }
    }

    private record ProcessedHeartbeat(
            BountySummonLeaseResult result,
            long expectedSummonStateVersion,
            String reason
    ) {
        private void requireSameRequest(
                UUID summonId,
                String backendId,
                long expectedVersion,
                String expectedReason,
                UUID operationId
        ) {
            if (!result.summon().summonId().equals(summonId)
                    || !result.summon().ownerBackendId().equals(backendId)
                    || expectedSummonStateVersion != expectedVersion
                    || !reason.equals(expectedReason)) {
                throw new BountyException("operation_id reused with different bounty heartbeat: " + operationId);
            }
        }
    }

    private record ProcessedCompletion(
            BountyCompletionResult result,
            UUID summonId,
            String backendId,
            long expectedSummonStateVersion,
            String reason
    ) {
        private void requireSameRequest(
                UUID expectedSummonId,
                String expectedBackendId,
                long expectedVersion,
                String expectedReason,
                UUID operationId
        ) {
            if (!summonId.equals(expectedSummonId)
                    || !backendId.equals(expectedBackendId)
                    || expectedSummonStateVersion != expectedVersion
                    || !reason.equals(expectedReason)) {
                throw new BountyException("operation_id reused with different bounty completion: " + operationId);
            }
        }
    }

    private record ProcessedFailure(
            BountyContractSnapshot result,
            UUID summonId,
            String backendId,
            long expectedSummonStateVersion,
            String reason
    ) {
        private void requireSameRequest(
                UUID expectedSummonId,
                String expectedBackendId,
                long expectedVersion,
                String expectedReason,
                UUID operationId
        ) {
            if (!summonId.equals(expectedSummonId)
                    || !backendId.equals(expectedBackendId)
                    || expectedSummonStateVersion != expectedVersion
                    || !reason.equals(expectedReason)) {
                throw new BountyException("operation_id reused with different bounty failure: " + operationId);
            }
        }
    }
}
