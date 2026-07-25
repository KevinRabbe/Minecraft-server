package io.github.kevinrabbe.minecraftserver.common.economy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Funded crafting-commission authority.
 *
 * <p>This repository deliberately stops at ACCEPTED. Craft completion will be owned by the crafting authority so
 * commission settlement cannot become an arbitrary item-minting API.</p>
 */
public final class CraftingCommissionRepository {
    private static final String CREATE_OPERATION = "CRAFTING_COMMISSION_CREATE";
    private static final String ACCEPT_OPERATION = "CRAFTING_COMMISSION_ACCEPT";
    private static final String CANCEL_OPERATION = "CRAFTING_COMMISSION_CANCEL";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final ItemCatalog itemCatalog;
    private final CommodityBatchEscrowValidator materialValidator;
    private final PlayerStateRepository playerStates;

    public CraftingCommissionRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            CommodityBatchEscrowValidator materialValidator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.materialValidator = Objects.requireNonNull(materialValidator, "materialValidator");
        this.playerStates = new PlayerStateRepository(dataSource);
    }

    public CraftingCommissionSnapshot load(UUID commissionId) throws SQLException {
        Objects.requireNonNull(commissionId, "commissionId");
        try (Connection connection = dataSource.getConnection()) {
            return readCommission(connection, commissionId, false);
        }
    }

    public CraftingCommissionCreateResult createFunded(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            CraftingCommissionRequest request,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(nextPlayerStatePayload, "nextPlayerStatePayload");
        if (expectedPlayerStateVersion < 0) {
            throw new IllegalArgumentException("expectedPlayerStateVersion must be >= 0");
        }
        String backend = requireNonBlank(backendId, "backendId");
        String zone = normalizeOptional(logicalZoneId);
        String entry = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadHash = sha256(nextPlayerStatePayload);
        Map<String, Long> materials = validateMaterials(request.materialQuantities());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), CREATE_OPERATION, operationId);
                    requireUuid(data, "session_id", sessionId, operationId);
                    requireString(data, "backend_id", backend, operationId);
                    requireLong(data, "expected_player_state_version", expectedPlayerStateVersion, operationId);
                    requireString(data, "recipe_id", request.recipeId(), operationId);
                    requireInt(data, "recipe_version", request.recipeVersion(), operationId);
                    requireLong(data, "payment_minor", request.paymentMinor(), operationId);
                    requireStringLongMap(data, "materials", materials, operationId);
                    requireNullableString(data, "logical_zone_id", zone, operationId);
                    requireNullableString(data, "entry_point", entry, operationId);
                    requireString(data, "payload_sha256", payloadHash, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    CraftingCommissionCreateResult result = createResultFrom(data);
                    connection.commit();
                    return result;
                }

                UUID requesterPlayerId = playerIdForSession(connection, sessionId);
                CoinWalletSnapshot wallet = readWallet(connection, requesterPlayerId, true);
                if (wallet.balanceMinor() < request.paymentMinor()) {
                    throw new CraftingCommissionException("Insufficient Coin balance for commission payment escrow");
                }

                long nextWalletBalance = wallet.balanceMinor() - request.paymentMinor();
                long nextWalletVersion = wallet.stateVersion();
                int ledgerLine = 0;
                if (request.paymentMinor() > 0) {
                    nextWalletVersion = increment(wallet.stateVersion(), "wallet", requesterPlayerId);
                    updateWallet(
                            connection,
                            requesterPlayerId,
                            wallet.stateVersion(),
                            nextWalletBalance,
                            nextWalletVersion
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            requesterPlayerId,
                            CoinCurrency.LEDGER_ASSET_TYPE,
                            CoinCurrency.LEDGER_ASSET_ID,
                            request.paymentMinor(),
                            "DEBIT",
                            normalizedReason
                    );
                }

                long nextPlayerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        zone,
                        entry,
                        nextPlayerStatePayload,
                        (lockedPlayerId, currentPayload, nextPayload) -> {
                            if (!lockedPlayerId.equals(requesterPlayerId)) {
                                throw new CraftingCommissionException(
                                        "session player changed during commission material escrow"
                                );
                            }
                            materialValidator.verifyRemoval(
                                    lockedPlayerId,
                                    materials,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                UUID commissionId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO crafting_commissions(
                            commission_id,
                            requester_player_id,
                            recipe_id,
                            recipe_version,
                            status,
                            payment_minor,
                            state_version,
                            create_operation_id
                        ) VALUES (?, ?, ?, ?, 'OPEN', ?, 0, ?)
                        """)) {
                    statement.setObject(1, commissionId);
                    statement.setObject(2, requesterPlayerId);
                    statement.setString(3, request.recipeId());
                    statement.setInt(4, request.recipeVersion());
                    statement.setLong(5, request.paymentMinor());
                    statement.setObject(6, operationId);
                    statement.executeUpdate();
                }

                for (Map.Entry<String, Long> material : materials.entrySet()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO crafting_commission_materials(
                                commission_id, commodity_definition_id, quantity
                            ) VALUES (?, ?, ?)
                            """)) {
                        statement.setObject(1, commissionId);
                        statement.setString(2, material.getKey());
                        statement.setLong(3, material.getValue());
                        statement.executeUpdate();
                    }
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            requesterPlayerId,
                            "COMMODITY",
                            material.getKey(),
                            material.getValue(),
                            "DEBIT",
                            normalizedReason
                    );
                }

                CraftingCommissionSnapshot commission = readCommission(connection, commissionId, false);
                CraftingCommissionCreateResult result = new CraftingCommissionCreateResult(
                        commission,
                        nextWalletBalance,
                        nextWalletVersion,
                        nextPlayerStateVersion
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("session_id", sessionId.toString());
                data.put("backend_id", backend);
                data.put("expected_player_state_version", expectedPlayerStateVersion);
                data.put("recipe_id", request.recipeId());
                data.put("recipe_version", request.recipeVersion());
                data.put("materials", materials);
                data.put("payment_minor", request.paymentMinor());
                data.put("logical_zone_id", zone);
                data.put("entry_point", entry);
                data.put("payload_sha256", payloadHash);
                data.put("reason", normalizedReason);
                data.put("commission", commissionMap(commission));
                data.put("wallet_balance_minor", nextWalletBalance);
                data.put("wallet_state_version", nextWalletVersion);
                data.put("player_state_version", nextPlayerStateVersion);
                insertProcessed(connection, operationId, CREATE_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public CraftingCommissionSnapshot accept(
            UUID operationId,
            UUID commissionId,
            UUID workerPlayerId
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(commissionId, "commissionId");
        Objects.requireNonNull(workerPlayerId, "workerPlayerId");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), ACCEPT_OPERATION, operationId);
                    requireUuid(data, "commission_id", commissionId, operationId);
                    requireUuid(data, "worker_player_id", workerPlayerId, operationId);
                    CraftingCommissionSnapshot result = commissionFrom(data.get("commission"));
                    connection.commit();
                    return result;
                }

                CraftingCommissionSnapshot current = readCommission(connection, commissionId, true);
                if (current.status() != CraftingCommissionStatus.OPEN) {
                    throw new CraftingCommissionException("only OPEN commissions may be accepted");
                }
                if (current.requesterPlayerId().equals(workerPlayerId)) {
                    throw new CraftingCommissionException("requester cannot accept their own commission");
                }
                requirePlayer(connection, workerPlayerId);
                long nextVersion = increment(current.stateVersion(), "commission", commissionId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE crafting_commissions
                        SET status = 'ACCEPTED',
                            worker_player_id = ?,
                            accept_operation_id = ?,
                            state_version = ?,
                            accepted_at = NOW()
                        WHERE commission_id = ? AND status = 'OPEN' AND state_version = ?
                        """)) {
                    statement.setObject(1, workerPlayerId);
                    statement.setObject(2, operationId);
                    statement.setLong(3, nextVersion);
                    statement.setObject(4, commissionId);
                    statement.setLong(5, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new CraftingCommissionException("commission changed concurrently while accepting");
                    }
                }

                CraftingCommissionSnapshot result = readCommission(connection, commissionId, false);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("commission_id", commissionId.toString());
                data.put("worker_player_id", workerPlayerId.toString());
                data.put("commission", commissionMap(result));
                insertProcessed(connection, operationId, ACCEPT_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    public CraftingCommissionCancelResult cancelOpen(
            UUID operationId,
            UUID commissionId,
            UUID requesterPlayerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(commissionId, "commissionId");
        Objects.requireNonNull(requesterPlayerId, "requesterPlayerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), CANCEL_OPERATION, operationId);
                    requireUuid(data, "commission_id", commissionId, operationId);
                    requireUuid(data, "requester_player_id", requesterPlayerId, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    CraftingCommissionCancelResult result = cancelResultFrom(data);
                    connection.commit();
                    return result;
                }

                CraftingCommissionSnapshot current = readCommission(connection, commissionId, true);
                if (!current.requesterPlayerId().equals(requesterPlayerId)) {
                    throw new CraftingCommissionException("only the requester may cancel a commission");
                }
                if (current.status() != CraftingCommissionStatus.OPEN) {
                    throw new CraftingCommissionException("only OPEN commissions may be cancelled");
                }

                CoinWalletSnapshot wallet = readWallet(connection, requesterPlayerId, true);
                long walletBalance = wallet.balanceMinor();
                long walletVersion = wallet.stateVersion();
                int ledgerLine = 0;
                if (current.paymentMinor() > 0) {
                    walletBalance = addExact(
                            walletBalance,
                            current.paymentMinor(),
                            "wallet overflow while cancelling commission"
                    );
                    walletVersion = increment(walletVersion, "wallet", requesterPlayerId);
                    updateWallet(
                            connection,
                            requesterPlayerId,
                            wallet.stateVersion(),
                            walletBalance,
                            walletVersion
                    );
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            requesterPlayerId,
                            CoinCurrency.LEDGER_ASSET_TYPE,
                            CoinCurrency.LEDGER_ASSET_ID,
                            current.paymentMinor(),
                            "CREDIT",
                            normalizedReason
                    );
                }

                List<CraftingCommissionReturn> returns = new ArrayList<>();
                int ordinal = 0;
                for (Map.Entry<String, Long> material : current.materialQuantities().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .toList()) {
                    UUID deliveryId = deterministicUuid(operationId, "material-delivery", ordinal);
                    UUID sourceOperationId = deterministicUuid(operationId, "material-source", ordinal);
                    insertPendingCommodityDelivery(
                            connection,
                            deliveryId,
                            requesterPlayerId,
                            material.getKey(),
                            material.getValue(),
                            sourceOperationId
                    );
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO crafting_commission_returns(
                                commission_id,
                                delivery_id,
                                commodity_definition_id,
                                quantity
                            ) VALUES (?, ?, ?, ?)
                            """)) {
                        statement.setObject(1, commissionId);
                        statement.setObject(2, deliveryId);
                        statement.setString(3, material.getKey());
                        statement.setLong(4, material.getValue());
                        statement.executeUpdate();
                    }
                    insertLedger(
                            connection,
                            operationId,
                            ledgerLine++,
                            requesterPlayerId,
                            "COMMODITY",
                            material.getKey(),
                            material.getValue(),
                            "CREDIT",
                            normalizedReason
                    );
                    returns.add(new CraftingCommissionReturn(
                            deliveryId,
                            material.getKey(),
                            material.getValue()
                    ));
                    ordinal++;
                }

                long nextVersion = increment(current.stateVersion(), "commission", commissionId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE crafting_commissions
                        SET status = 'CANCELLED',
                            cancel_operation_id = ?,
                            state_version = ?,
                            settled_at = NOW()
                        WHERE commission_id = ? AND status = 'OPEN' AND state_version = ?
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setLong(2, nextVersion);
                    statement.setObject(3, commissionId);
                    statement.setLong(4, current.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new CraftingCommissionException("commission changed concurrently while cancelling");
                    }
                }

                CraftingCommissionSnapshot cancelled = readCommission(connection, commissionId, false);
                CraftingCommissionCancelResult result = new CraftingCommissionCancelResult(
                        cancelled,
                        walletBalance,
                        walletVersion,
                        returns
                );
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("commission_id", commissionId.toString());
                data.put("requester_player_id", requesterPlayerId.toString());
                data.put("reason", normalizedReason);
                data.put("commission", commissionMap(cancelled));
                data.put("wallet_balance_minor", walletBalance);
                data.put("wallet_state_version", walletVersion);
                data.put("material_returns", returns.stream().map(CraftingCommissionRepository::returnMap).toList());
                insertProcessed(connection, operationId, CANCEL_OPERATION, data);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private Map<String, Long> validateMaterials(Map<String, Long> raw) {
        LinkedHashMap<String, Long> validated = new LinkedHashMap<>();
        raw.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ItemDefinition definition = itemCatalog.require(entry.getKey());
            if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                throw new CraftingCommissionException(
                        "commission material must be a commodity definition: " + definition.definitionId()
                );
            }
            validated.put(definition.definitionId(), entry.getValue());
        });
        return Map.copyOf(validated);
    }

    private static CraftingCommissionSnapshot readCommission(
            Connection connection,
            UUID commissionId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT requester_player_id,
                       worker_player_id,
                       recipe_id,
                       recipe_version,
                       status,
                       payment_minor,
                       state_version,
                       created_at,
                       accepted_at,
                       settled_at
                FROM crafting_commissions
                WHERE commission_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, commissionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CraftingCommissionException("Unknown crafting commission: " + commissionId);
                }
                Timestamp acceptedAt = row.getTimestamp("accepted_at");
                Timestamp settledAt = row.getTimestamp("settled_at");
                return new CraftingCommissionSnapshot(
                        commissionId,
                        row.getObject("requester_player_id", UUID.class),
                        row.getObject("worker_player_id", UUID.class),
                        row.getString("recipe_id"),
                        row.getInt("recipe_version"),
                        CraftingCommissionStatus.valueOf(row.getString("status")),
                        readMaterials(connection, commissionId),
                        row.getLong("payment_minor"),
                        row.getLong("state_version"),
                        row.getTimestamp("created_at").toInstant(),
                        acceptedAt == null ? null : acceptedAt.toInstant(),
                        settledAt == null ? null : settledAt.toInstant()
                );
            }
        }
    }

    private static Map<String, Long> readMaterials(Connection connection, UUID commissionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT commodity_definition_id, quantity
                FROM crafting_commission_materials
                WHERE commission_id = ?
                ORDER BY commodity_definition_id ASC
                """)) {
            statement.setObject(1, commissionId);
            try (ResultSet rows = statement.executeQuery()) {
                LinkedHashMap<String, Long> result = new LinkedHashMap<>();
                while (rows.next()) {
                    result.put(rows.getString("commodity_definition_id"), rows.getLong("quantity"));
                }
                return Map.copyOf(result);
            }
        }
    }

    private static UUID playerIdForSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM player_sessions
                WHERE network_session_id = ?
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CraftingCommissionException("Unknown player session: " + sessionId);
                }
                return row.getObject("player_id", UUID.class);
            }
        }
    }

    private static void requirePlayer(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE player_id = ?")) {
            statement.setObject(1, playerId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CraftingCommissionException("Unknown worker player_id: " + playerId);
                }
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
                    throw new CraftingCommissionException("Wallet does not exist for player_id " + playerId);
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
                throw new CraftingCommissionException("Wallet authority changed concurrently for " + playerId);
            }
        }
    }

    private static void insertPendingCommodityDelivery(
            Connection connection,
            UUID deliveryId,
            UUID playerId,
            String definitionId,
            long quantity,
            UUID sourceOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_deliveries(
                    delivery_id,
                    player_id,
                    commodity_definition_id,
                    quantity,
                    source_operation_id,
                    status
                ) VALUES (?, ?, ?, ?, ?, 'PENDING')
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, playerId);
            statement.setString(3, definitionId);
            statement.setLong(4, quantity);
            statement.setObject(5, sourceOperationId);
            statement.executeUpdate();
        }
    }

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            String assetType,
            String assetId,
            long amount,
            String direction,
            String reason
    ) throws SQLException {
        if (amount <= 0) {
            return;
        }
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, assetType);
            statement.setString(5, assetId);
            statement.setLong(6, amount);
            statement.setString(7, direction);
            statement.setString(8, reason);
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
            throw new CraftingCommissionException(
                    "operation_id " + operationId + " already belongs to operation type " + operation.operationType()
            );
        }
        return operation.result();
    }

    private static Map<String, Object> commissionMap(CraftingCommissionSnapshot commission) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("commission_id", commission.commissionId().toString());
        value.put("requester_player_id", commission.requesterPlayerId().toString());
        value.put("worker_player_id", commission.workerPlayerId() == null ? null : commission.workerPlayerId().toString());
        value.put("recipe_id", commission.recipeId());
        value.put("recipe_version", commission.recipeVersion());
        value.put("status", commission.status().name());
        value.put("materials", commission.materialQuantities());
        value.put("payment_minor", commission.paymentMinor());
        value.put("state_version", commission.stateVersion());
        value.put("created_at", commission.createdAt().toString());
        value.put("accepted_at", commission.acceptedAt() == null ? null : commission.acceptedAt().toString());
        value.put("settled_at", commission.settledAt() == null ? null : commission.settledAt().toString());
        return value;
    }

    private static CraftingCommissionSnapshot commissionFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "commission");
        String workerId = nullableString(value, "worker_player_id");
        return new CraftingCommissionSnapshot(
                uuidValue(value, "commission_id"),
                uuidValue(value, "requester_player_id"),
                workerId == null ? null : UUID.fromString(workerId),
                stringValue(value, "recipe_id"),
                intValue(value, "recipe_version"),
                CraftingCommissionStatus.valueOf(stringValue(value, "status")),
                stringLongMap(value.get("materials"), "materials"),
                longValue(value, "payment_minor"),
                longValue(value, "state_version"),
                Instant.parse(stringValue(value, "created_at")),
                nullableInstant(value, "accepted_at"),
                nullableInstant(value, "settled_at")
        );
    }

    private static CraftingCommissionCreateResult createResultFrom(Map<String, Object> data) {
        return new CraftingCommissionCreateResult(
                commissionFrom(data.get("commission")),
                longValue(data, "wallet_balance_minor"),
                longValue(data, "wallet_state_version"),
                longValue(data, "player_state_version")
        );
    }

    private static Map<String, Object> returnMap(CraftingCommissionReturn value) {
        return Map.of(
                "delivery_id", value.deliveryId().toString(),
                "commodity_definition_id", value.commodityDefinitionId(),
                "quantity", value.quantity()
        );
    }

    private static CraftingCommissionCancelResult cancelResultFrom(Map<String, Object> data) {
        Object rawReturns = data.get("material_returns");
        if (!(rawReturns instanceof List<?> list)) {
            throw new CraftingCommissionException("commission material_returns is not a list");
        }
        List<CraftingCommissionReturn> returns = new ArrayList<>();
        for (Object raw : list) {
            Map<String, Object> value = objectMap(raw, "material_return");
            returns.add(new CraftingCommissionReturn(
                    uuidValue(value, "delivery_id"),
                    stringValue(value, "commodity_definition_id"),
                    longValue(value, "quantity")
            ));
        }
        return new CraftingCommissionCancelResult(
                commissionFrom(data.get("commission")),
                longValue(data, "wallet_balance_minor"),
                longValue(data, "wallet_state_version"),
                returns
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new CraftingCommissionException("Could not parse commission idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CraftingCommissionException("Could not serialize commission idempotency result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new CraftingCommissionException("commission idempotency field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new CraftingCommissionException("commission idempotency result is missing field: " + field);
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
            throw new CraftingCommissionException("commission idempotency field is not numeric: " + field);
        }
        return number.intValue();
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new CraftingCommissionException("commission idempotency field is not numeric: " + field);
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

    private static Map<String, Long> stringLongMap(Object raw, String field) {
        Map<String, Object> map = objectMap(raw, field);
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!(entry.getValue() instanceof Number number)) {
                throw new CraftingCommissionException("commission map value is not numeric: " + entry.getKey());
            }
            result.put(entry.getKey(), number.longValue());
        });
        return Map.copyOf(result);
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw new CraftingCommissionException("operation_id reused with a different commission request: " + operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw new CraftingCommissionException("operation_id reused with a different commission request: " + operationId);
        }
    }

    private static void requireNullableString(
            Map<String, Object> data,
            String field,
            String expected,
            UUID operationId
    ) {
        if (!Objects.equals(nullableString(data, field), expected)) {
            throw new CraftingCommissionException("operation_id reused with a different commission request: " + operationId);
        }
    }

    private static void requireInt(Map<String, Object> data, String field, int expected, UUID operationId) {
        if (intValue(data, field) != expected) {
            throw new CraftingCommissionException("operation_id reused with a different commission request: " + operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw new CraftingCommissionException("operation_id reused with a different commission request: " + operationId);
        }
    }

    private static void requireStringLongMap(
            Map<String, Object> data,
            String field,
            Map<String, Long> expected,
            UUID operationId
    ) {
        if (!stringLongMap(data.get(field), field).equals(expected)) {
            throw new CraftingCommissionException("operation_id reused with a different commission request: " + operationId);
        }
    }

    private static long increment(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new CraftingCommissionException(target + " state_version overflow for " + id, exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CraftingCommissionException(message, exception);
        }
    }

    private static UUID deterministicUuid(UUID operationId, String purpose, int ordinal) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:commission:" + operationId + ":" + purpose + ":" + ordinal)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
