package io.github.kevinrabbe.minecraftserver.common.crafting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinCurrency;
import io.github.kevinrabbe.minecraftserver.common.economy.CoinWalletSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionSnapshot;
import io.github.kevinrabbe.minecraftserver.common.economy.CraftingCommissionStatus;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;

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

/** Atomic ACCEPTED -> COMPLETED commission settlement through the real crafting output authority. */
public final class CraftingCommissionCompletionRepository {
    private static final String OPERATION = "CRAFTING_COMMISSION_COMPLETE";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final CraftRecipeCatalog recipes;
    private final SkillProgressionCatalog skillCatalog;
    private final CraftingOutputAuthority outputs;

    public CraftingCommissionCompletionRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            CraftRecipeCatalog recipes,
            SkillProgressionCatalog skillCatalog
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
        this.outputs = new CraftingOutputAuthority(Objects.requireNonNull(itemCatalog, "itemCatalog"));
    }

    public CraftingCommissionCompletionResult complete(
            UUID operationId,
            UUID commissionId,
            UUID workerPlayerId,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(commissionId, "commissionId");
        Objects.requireNonNull(workerPlayerId, "workerPlayerId");
        String normalizedReason = requireReason(reason);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), operationId);
                    requireUuid(data, "commission_id", commissionId, operationId);
                    requireUuid(data, "worker_player_id", workerPlayerId, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    CraftingCommissionSnapshot commission = readCommission(connection, commissionId, false);
                    CraftExecutionResult craft = CraftingRepository.resultFrom(data.get("craft"));
                    CraftingCommissionCompletionResult result = new CraftingCommissionCompletionResult(
                            commission,
                            craft,
                            longValue(data, "worker_wallet_balance_minor"),
                            longValue(data, "worker_wallet_state_version")
                    );
                    connection.commit();
                    return result;
                }

                CraftingCommissionSnapshot commission = readCommission(connection, commissionId, true);
                if (commission.status() != CraftingCommissionStatus.ACCEPTED) {
                    throw new CraftingException("only ACCEPTED commissions may be completed");
                }
                if (!workerPlayerId.equals(commission.workerPlayerId())) {
                    throw new CraftingException("only the accepted commission worker may complete it");
                }

                CraftRecipeVersion recipe = recipes.require(commission.recipeId(), commission.recipeVersion());
                Map<String, Long> requiredMaterials = ingredientMap(recipe.recipe());
                if (!requiredMaterials.equals(commission.materialQuantities())) {
                    throw new CraftingException("commission material escrow does not exactly match recipe requirements");
                }
                requireSkill(connection, workerPlayerId, recipe.recipe());

                CoinWalletSnapshot wallet = readWallet(connection, workerPlayerId, true);
                long walletBalance = wallet.balanceMinor();
                long walletVersion = wallet.stateVersion();
                int ledgerLine = 0;
                if (commission.paymentMinor() > 0) {
                    walletBalance = addExact(walletBalance, commission.paymentMinor(), "commission payment wallet overflow");
                    walletVersion = increment(walletVersion, "wallet", workerPlayerId);
                    updateWallet(connection, workerPlayerId, wallet.stateVersion(), walletBalance, walletVersion);
                    insertCoinCredit(
                            connection,
                            operationId,
                            ledgerLine++,
                            workerPlayerId,
                            commission.paymentMinor(),
                            normalizedReason
                    );
                }

                UUID craftId = UUID.randomUUID();
                CraftingOutputAuthority.IssuedOutput issued = outputs.issue(
                        connection,
                        operationId,
                        workerPlayerId,
                        commission.requesterPlayerId(),
                        recipe,
                        normalizedReason,
                        ledgerLine
                );
                CraftExecutionResult craft = new CraftExecutionResult(
                        craftId,
                        operationId,
                        workerPlayerId,
                        commission.requesterPlayerId(),
                        recipe.recipe().recipeId(),
                        recipe.version(),
                        recipe.recipe().outputDefinitionId(),
                        recipe.recipe().outputQuantity(),
                        issued.deliveryId(),
                        issued.itemInstanceId(),
                        issued.rollQualityBasisPoints(),
                        issued.createdAt()
                );

                LinkedHashMap<String, Object> craftData = new LinkedHashMap<>();
                craftData.put("source_kind", "CRAFTING_COMMISSION");
                craftData.put("source_id", commissionId.toString());
                craftData.put("materials", commission.materialQuantities());
                craftData.put("result", CraftingRepository.resultMap(craft));
                insertCraftRecord(connection, craft, craftData);

                long nextCommissionVersion = increment(commission.stateVersion(), "commission", commissionId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE crafting_commissions
                        SET status = 'COMPLETED',
                            settle_operation_id = ?,
                            completion_craft_id = ?,
                            state_version = ?,
                            settled_at = NOW()
                        WHERE commission_id = ?
                          AND status = 'ACCEPTED'
                          AND worker_player_id = ?
                          AND state_version = ?
                        """)) {
                    statement.setObject(1, operationId);
                    statement.setObject(2, craftId);
                    statement.setLong(3, nextCommissionVersion);
                    statement.setObject(4, commissionId);
                    statement.setObject(5, workerPlayerId);
                    statement.setLong(6, commission.stateVersion());
                    if (statement.executeUpdate() != 1) {
                        throw new CraftingException("commission changed concurrently during completion");
                    }
                }

                CraftingCommissionSnapshot completed = readCommission(connection, commissionId, false);
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("commission_id", commissionId.toString());
                data.put("worker_player_id", workerPlayerId.toString());
                data.put("reason", normalizedReason);
                data.put("craft", CraftingRepository.resultMap(craft));
                data.put("worker_wallet_balance_minor", walletBalance);
                data.put("worker_wallet_state_version", walletVersion);
                insertProcessed(connection, operationId, data);

                CraftingCommissionCompletionResult result = new CraftingCommissionCompletionResult(
                        completed,
                        craft,
                        walletBalance,
                        walletVersion
                );
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollbackQuietly(connection, exception);
                throw exception;
            }
        }
    }

    private void requireSkill(Connection connection, UUID playerId, CraftRecipeDefinition recipe) throws SQLException {
        if (recipe.requiredSkillId() == null) {
            return;
        }
        int activeCap;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT active_skill_cap FROM progression_state WHERE singleton = TRUE
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CraftingException("global progression_state is missing");
                }
                activeCap = row.getInt("active_skill_cap");
            }
        }
        if (recipe.requiredSkillLevel() > activeCap) {
            throw new CraftingException("recipe skill requirement exceeds the currently active cap");
        }
        long experience = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT experience FROM player_skills WHERE player_id = ? AND skill_id = ?
                """)) {
            statement.setObject(1, playerId);
            statement.setString(2, recipe.requiredSkillId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    experience = row.getLong("experience");
                }
            }
        }
        SkillProgressionDefinition progression = skillCatalog.require(recipe.requiredSkillId());
        int level = progression.levelForExperience(experience, activeCap);
        if (level < recipe.requiredSkillLevel()) {
            throw new CraftingException("accepted worker no longer satisfies recipe skill requirement");
        }
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
                    throw new CraftingException("Unknown crafting commission: " + commissionId);
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

    private static Map<String, Long> ingredientMap(CraftRecipeDefinition recipe) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (RecipeIngredient ingredient : recipe.ingredients()) {
            result.merge(ingredient.definitionId(), ingredient.quantity(), (left, right) -> {
                try {
                    return Math.addExact(left, right);
                } catch (ArithmeticException exception) {
                    throw new CraftingException("recipe ingredient quantity overflow", exception);
                }
            });
        }
        return Map.copyOf(result);
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
                    throw new CraftingException("worker wallet does not exist: " + playerId);
                }
                return new CoinWalletSnapshot(playerId, row.getLong("balance_minor"), row.getLong("state_version"));
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
                throw new CraftingException("worker wallet changed concurrently");
            }
        }
    }

    private static void insertCoinCredit(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            long amountMinor,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, ?, ?, ?, ?, ?, 'CREDIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, CoinCurrency.LEDGER_ASSET_TYPE);
            statement.setString(5, CoinCurrency.LEDGER_ASSET_ID);
            statement.setLong(6, amountMinor);
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static void insertCraftRecord(
            Connection connection,
            CraftExecutionResult craft,
            Map<String, Object> resultData
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO craft_records(
                    craft_id, operation_id, player_id, recipe_id, recipe_version, result_data
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, craft.craftId());
            statement.setObject(2, craft.operationId());
            statement.setObject(3, craft.crafterPlayerId());
            statement.setString(4, craft.recipeId());
            statement.setInt(5, craft.recipeVersion());
            statement.setString(6, writeJson(resultData));
            statement.executeUpdate();
        }
    }

    private static Optional<ProcessedOperation> findProcessed(Connection connection, UUID operationId) throws SQLException {
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

    private static void insertProcessed(Connection connection, UUID operationId, Map<String, Object> data)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_operations(operation_id, operation_type, result)
                VALUES (?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, operationId);
            statement.setString(2, OPERATION);
            statement.setString(3, writeJson(data));
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> requireType(ProcessedOperation processed, UUID operationId) {
        if (!OPERATION.equals(processed.operationType())) {
            throw new CraftingException(
                    "operation_id " + operationId + " already belongs to " + processed.operationType()
            );
        }
        return processed.result();
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new CraftingException("Could not parse commission completion idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CraftingException("Could not serialize commission completion result", exception);
        }
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new CraftingException("commission completion result is missing field: " + field);
        }
        return Objects.toString(raw);
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new CraftingException("commission completion field is not numeric: " + field);
        }
        return number.longValue();
    }

    private static UUID uuidValue(Map<String, Object> value, String field) {
        return UUID.fromString(stringValue(value, field));
    }

    private static void requireUuid(Map<String, Object> data, String field, UUID expected, UUID operationId) {
        if (!uuidValue(data, field).equals(expected)) {
            throw reused(operationId);
        }
    }

    private static void requireString(Map<String, Object> data, String field, String expected, UUID operationId) {
        if (!stringValue(data, field).equals(expected)) {
            throw reused(operationId);
        }
    }

    private static CraftingException reused(UUID operationId) {
        return new CraftingException("operation_id reused with a different commission completion: " + operationId);
    }

    private static long increment(long current, String target, UUID id) {
        try {
            return Math.addExact(current, 1L);
        } catch (ArithmeticException exception) {
            throw new CraftingException(target + " state_version overflow for " + id, exception);
        }
    }

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new CraftingException(message, exception);
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
