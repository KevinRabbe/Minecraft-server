package io.github.kevinrabbe.minecraftserver.common.crafting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityBatchEscrowValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.persistence.PostgresOperationLock;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import io.github.kevinrabbe.minecraftserver.common.session.PlayerStateRepository;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL authority for exactly-once personal crafting. */
public final class CraftingRepository {
    private static final String OPERATION = "CRAFT_EXECUTE";
    private static final Pattern REASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,95}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final CraftRecipeCatalog recipes;
    private final SkillProgressionCatalog skillCatalog;
    private final CommodityBatchEscrowValidator ingredientValidator;
    private final PlayerStateRepository playerStates;
    private final CraftingOutputAuthority outputs;

    public CraftingRepository(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            CraftRecipeCatalog recipes,
            SkillProgressionCatalog skillCatalog,
            CommodityBatchEscrowValidator ingredientValidator
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog");
        this.ingredientValidator = Objects.requireNonNull(ingredientValidator, "ingredientValidator");
        this.playerStates = new PlayerStateRepository(dataSource);
        this.outputs = new CraftingOutputAuthority(Objects.requireNonNull(itemCatalog, "itemCatalog"));
    }

    public CraftExecutionResult craftFromPlayerState(
            UUID operationId,
            UUID sessionId,
            String backendId,
            long expectedPlayerStateVersion,
            String recipeId,
            int recipeVersion,
            String logicalZoneId,
            String entryPoint,
            byte[] nextPlayerStatePayload,
            String reason
    ) throws SQLException {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(nextPlayerStatePayload, "nextPlayerStatePayload");
        if (expectedPlayerStateVersion < 0) {
            throw new IllegalArgumentException("expectedPlayerStateVersion must be >= 0");
        }
        String backend = requireNonBlank(backendId, "backendId");
        String zone = normalizeOptional(logicalZoneId);
        String entry = normalizeOptional(entryPoint);
        String normalizedReason = requireReason(reason);
        String payloadHash = sha256(nextPlayerStatePayload);
        CraftRecipeVersion recipe = recipes.require(recipeId, recipeVersion);
        Map<String, Long> ingredients = ingredientMap(recipe.recipe());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                PostgresOperationLock.lock(connection, operationId);
                Optional<ProcessedOperation> processed = findProcessed(connection, operationId);
                if (processed.isPresent()) {
                    Map<String, Object> data = requireType(processed.orElseThrow(), operationId);
                    requireUuid(data, "session_id", sessionId, operationId);
                    requireString(data, "backend_id", backend, operationId);
                    requireLong(data, "expected_player_state_version", expectedPlayerStateVersion, operationId);
                    requireString(data, "recipe_id", recipe.recipe().recipeId(), operationId);
                    requireInt(data, "recipe_version", recipe.version(), operationId);
                    requireNullableString(data, "logical_zone_id", zone, operationId);
                    requireNullableString(data, "entry_point", entry, operationId);
                    requireString(data, "payload_sha256", payloadHash, operationId);
                    requireString(data, "reason", normalizedReason, operationId);
                    CraftExecutionResult result = resultFrom(data.get("result"));
                    connection.commit();
                    return result;
                }

                UUID crafterPlayerId = playerIdForSession(connection, sessionId);
                requireSkill(connection, crafterPlayerId, recipe.recipe());
                long playerStateVersion = playerStates.commitWithinTransaction(
                        connection,
                        sessionId,
                        backend,
                        expectedPlayerStateVersion,
                        zone,
                        entry,
                        nextPlayerStatePayload,
                        (lockedPlayerId, currentPayload, nextPayload) -> {
                            if (!lockedPlayerId.equals(crafterPlayerId)) {
                                throw new CraftingException("session player changed during craft");
                            }
                            ingredientValidator.verifyRemoval(
                                    lockedPlayerId,
                                    ingredients,
                                    currentPayload,
                                    nextPayload
                            );
                        }
                );

                int ledgerLine = 0;
                for (Map.Entry<String, Long> ingredient : ingredients.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    insertLedgerDebit(
                            connection,
                            operationId,
                            ledgerLine++,
                            crafterPlayerId,
                            ingredient.getKey(),
                            ingredient.getValue(),
                            normalizedReason
                    );
                }

                UUID craftId = UUID.randomUUID();
                CraftingOutputAuthority.IssuedOutput issued = outputs.issue(
                        connection,
                        operationId,
                        crafterPlayerId,
                        crafterPlayerId,
                        recipe,
                        normalizedReason,
                        ledgerLine
                );
                CraftExecutionResult result = new CraftExecutionResult(
                        craftId,
                        operationId,
                        crafterPlayerId,
                        crafterPlayerId,
                        recipe.recipe().recipeId(),
                        recipe.version(),
                        recipe.recipe().outputDefinitionId(),
                        recipe.recipe().outputQuantity(),
                        issued.deliveryId(),
                        issued.itemInstanceId(),
                        issued.rollQualityBasisPoints(),
                        issued.createdAt()
                );

                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("session_id", sessionId.toString());
                data.put("backend_id", backend);
                data.put("expected_player_state_version", expectedPlayerStateVersion);
                data.put("recipe_id", recipe.recipe().recipeId());
                data.put("recipe_version", recipe.version());
                data.put("logical_zone_id", zone);
                data.put("entry_point", entry);
                data.put("payload_sha256", payloadHash);
                data.put("reason", normalizedReason);
                data.put("player_state_version", playerStateVersion);
                data.put("ingredients", ingredients);
                data.put("result", resultMap(result));

                insertCraftRecord(connection, result, data);
                insertProcessed(connection, operationId, data);
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
                SELECT active_skill_cap
                FROM progression_state
                WHERE singleton = TRUE
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
                SELECT experience
                FROM player_skills
                WHERE player_id = ? AND skill_id = ?
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
            throw new CraftingException(
                    "crafting skill requirement not met: " + recipe.requiredSkillId()
                            + " requires " + recipe.requiredSkillLevel() + " but player has " + level
            );
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

    private static UUID playerIdForSession(Connection connection, UUID sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id
                FROM player_sessions
                WHERE network_session_id = ?
                """)) {
            statement.setObject(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new CraftingException("Unknown player session: " + sessionId);
                }
                return row.getObject("player_id", UUID.class);
            }
        }
    }

    private static void insertLedgerDebit(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            String definitionId,
            long quantity,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economic_ledger(
                    operation_id, line_no, player_id, asset_type, asset_id, amount, direction, reason
                ) VALUES (?, ?, ?, 'COMMODITY', ?, ?, 'DEBIT', ?)
                """)) {
            statement.setObject(1, operationId);
            statement.setInt(2, lineNo);
            statement.setObject(3, playerId);
            statement.setString(4, definitionId);
            statement.setLong(5, quantity);
            statement.setString(6, reason);
            statement.executeUpdate();
        }
    }

    private static void insertCraftRecord(
            Connection connection,
            CraftExecutionResult result,
            Map<String, Object> resultData
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO craft_records(
                    craft_id, operation_id, player_id, recipe_id, recipe_version, result_data
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """)) {
            statement.setObject(1, result.craftId());
            statement.setObject(2, result.operationId());
            statement.setObject(3, result.crafterPlayerId());
            statement.setString(4, result.recipeId());
            statement.setInt(5, result.recipeVersion());
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

    static Map<String, Object> resultMap(CraftExecutionResult result) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("craft_id", result.craftId().toString());
        value.put("operation_id", result.operationId().toString());
        value.put("crafter_player_id", result.crafterPlayerId().toString());
        value.put("recipient_player_id", result.recipientPlayerId().toString());
        value.put("recipe_id", result.recipeId());
        value.put("recipe_version", result.recipeVersion());
        value.put("output_definition_id", result.outputDefinitionId());
        value.put("output_quantity", result.outputQuantity());
        value.put("delivery_id", result.deliveryId().toString());
        value.put("item_instance_id", result.itemInstanceId() == null ? null : result.itemInstanceId().toString());
        value.put("roll_quality_basis_points", result.rollQualityBasisPoints());
        value.put("created_at", result.createdAt().toString());
        return value;
    }

    static CraftExecutionResult resultFrom(Object raw) {
        Map<String, Object> value = objectMap(raw, "result");
        String itemId = nullableString(value, "item_instance_id");
        Map<String, Integer> rolls = new LinkedHashMap<>();
        objectMap(value.get("roll_quality_basis_points"), "roll_quality_basis_points")
                .forEach((key, rawValue) -> {
                    if (!(rawValue instanceof Number number)) {
                        throw new CraftingException("roll quality is not numeric: " + key);
                    }
                    rolls.put(key, number.intValue());
                });
        return new CraftExecutionResult(
                uuidValue(value, "craft_id"),
                uuidValue(value, "operation_id"),
                uuidValue(value, "crafter_player_id"),
                uuidValue(value, "recipient_player_id"),
                stringValue(value, "recipe_id"),
                intValue(value, "recipe_version"),
                stringValue(value, "output_definition_id"),
                longValue(value, "output_quantity"),
                uuidValue(value, "delivery_id"),
                itemId == null ? null : UUID.fromString(itemId),
                rolls,
                java.time.Instant.parse(stringValue(value, "created_at"))
        );
    }

    private static Map<String, Object> readJsonMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new CraftingException("Could not parse craft idempotency result", exception);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CraftingException("Could not serialize craft result", exception);
        }
    }

    private static Map<String, Object> objectMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new CraftingException("craft field is not an object: " + field);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(Objects.toString(key), value));
        return result;
    }

    private static String stringValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (raw == null) {
            throw new CraftingException("craft result is missing field: " + field);
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
            throw new CraftingException("craft field is not numeric: " + field);
        }
        return number.intValue();
    }

    private static long longValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        if (!(raw instanceof Number number)) {
            throw new CraftingException("craft field is not numeric: " + field);
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

    private static void requireNullableString(
            Map<String, Object> data,
            String field,
            String expected,
            UUID operationId
    ) {
        if (!Objects.equals(nullableString(data, field), expected)) {
            throw reused(operationId);
        }
    }

    private static void requireInt(Map<String, Object> data, String field, int expected, UUID operationId) {
        if (intValue(data, field) != expected) {
            throw reused(operationId);
        }
    }

    private static void requireLong(Map<String, Object> data, String field, long expected, UUID operationId) {
        if (longValue(data, field) != expected) {
            throw reused(operationId);
        }
    }

    private static CraftingException reused(UUID operationId) {
        return new CraftingException("operation_id reused with a different craft request: " + operationId);
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

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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
