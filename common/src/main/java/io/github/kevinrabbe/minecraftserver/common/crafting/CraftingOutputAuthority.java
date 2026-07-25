package io.github.kevinrabbe.minecraftserver.common.crafting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Internal output issuer. Callers must prove recipe, ingredient and skill authority first. */
final class CraftingOutputAuthority {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ItemCatalog itemCatalog;

    CraftingOutputAuthority(ItemCatalog itemCatalog) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
    }

    IssuedOutput issue(
            Connection connection,
            UUID operationId,
            UUID crafterPlayerId,
            UUID recipientPlayerId,
            CraftRecipeVersion recipeVersion,
            String reason,
            int ledgerLine
    ) throws SQLException {
        ItemDefinition output = itemCatalog.require(recipeVersion.recipe().outputDefinitionId());
        UUID deliveryId = deterministicUuid(operationId, "output-delivery");
        if (output.identityKind() == ItemIdentityKind.COMMODITY) {
            return issueCommodity(
                    connection, operationId, recipientPlayerId, output,
                    recipeVersion.recipe().outputQuantity(), deliveryId, reason, ledgerLine
            );
        }
        return issueIndividual(
                connection, operationId, crafterPlayerId, recipientPlayerId,
                output, recipeVersion, deliveryId, reason, ledgerLine
        );
    }

    private static IssuedOutput issueCommodity(
            Connection connection,
            UUID operationId,
            UUID recipientPlayerId,
            ItemDefinition output,
            long quantity,
            UUID deliveryId,
            String reason,
            int ledgerLine
    ) throws SQLException {
        Instant createdAt;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_commodity_deliveries(
                    delivery_id, player_id, commodity_definition_id, quantity, source_operation_id, status
                ) VALUES (?, ?, ?, ?, ?, 'PENDING')
                RETURNING created_at
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, recipientPlayerId);
            statement.setString(3, output.definitionId());
            statement.setLong(4, quantity);
            statement.setObject(5, operationId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                createdAt = row.getTimestamp("created_at").toInstant();
            }
        }
        insertLedger(connection, operationId, ledgerLine, recipientPlayerId,
                "COMMODITY", output.definitionId(), quantity, reason);
        return new IssuedOutput(deliveryId, null, Map.of(), createdAt);
    }

    private static IssuedOutput issueIndividual(
            Connection connection,
            UUID operationId,
            UUID crafterPlayerId,
            UUID recipientPlayerId,
            ItemDefinition output,
            CraftRecipeVersion recipeVersion,
            UUID deliveryId,
            String reason,
            int ledgerLine
    ) throws SQLException {
        UUID itemInstanceId = UUID.randomUUID();
        Map<String, Integer> rollState = roll(recipeVersion);
        Instant createdAt;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pending_unique_deliveries(
                    delivery_id, recipient_player_id, item_instance_id, status, issue_operation_id, issue_reason
                ) VALUES (?, ?, ?, 'PENDING', ?, ?)
                RETURNING created_at
                """)) {
            statement.setObject(1, deliveryId);
            statement.setObject(2, recipientPlayerId);
            statement.setObject(3, itemInstanceId);
            statement.setObject(4, operationId);
            statement.setString(5, reason);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                createdAt = row.getTimestamp("created_at").toInstant();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_instances(
                    item_instance_id, definition_id, location_kind, location_id, state_version,
                    original_owner_player_id, created_by_operation_id, created_reason, roll_state, upgrade_level
                ) VALUES (?, ?, 'PENDING_DELIVERY', ?, 0, ?, ?, ?, ?::jsonb, 0)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setString(2, output.definitionId());
            statement.setObject(3, deliveryId);
            statement.setObject(4, recipientPlayerId);
            statement.setObject(5, operationId);
            statement.setString(6, reason);
            statement.setString(7, writeJson(rollState));
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO item_provenance(
                    item_instance_id, sequence_no, operation_id, event_type,
                    from_location_kind, from_location_id, to_location_kind, to_location_id,
                    reason, actor_player_id
                ) VALUES (?, 0, ?, 'CREATED', NULL, NULL, 'PENDING_DELIVERY', ?, ?, ?)
                """)) {
            statement.setObject(1, itemInstanceId);
            statement.setObject(2, operationId);
            statement.setObject(3, deliveryId);
            statement.setString(4, reason);
            statement.setObject(5, crafterPlayerId);
            statement.executeUpdate();
        }
        insertLedger(connection, operationId, ledgerLine, recipientPlayerId,
                "ITEM_INSTANCE", itemInstanceId.toString(), 1, reason);
        return new IssuedOutput(deliveryId, itemInstanceId, rollState, createdAt);
    }

    private static Map<String, Integer> roll(CraftRecipeVersion recipeVersion) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        recipeVersion.outputRollProfile().properties().keySet().stream().sorted()
                .forEach(property -> result.put(property, ThreadLocalRandom.current().nextInt(10_001)));
        return Map.copyOf(result);
    }

    private static void insertLedger(
            Connection connection,
            UUID operationId,
            int lineNo,
            UUID playerId,
            String assetType,
            String assetId,
            long amount,
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
            statement.setString(4, assetType);
            statement.setString(5, assetId);
            statement.setLong(6, amount);
            statement.setString(7, reason);
            statement.executeUpdate();
        }
    }

    private static UUID deterministicUuid(UUID operationId, String purpose) {
        return UUID.nameUUIDFromBytes(
                ("minecraft-server:craft:" + operationId + ":" + purpose).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new CraftingException("Could not serialize intrinsic roll state", exception);
        }
    }

    record IssuedOutput(UUID deliveryId, UUID itemInstanceId, Map<String, Integer> rollQualityBasisPoints, Instant createdAt) {
        IssuedOutput {
            deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
            rollQualityBasisPoints = Map.copyOf(Objects.requireNonNull(rollQualityBasisPoints, "rollQualityBasisPoints"));
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
