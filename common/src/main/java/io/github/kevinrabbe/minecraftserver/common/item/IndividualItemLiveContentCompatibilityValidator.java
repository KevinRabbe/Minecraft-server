package io.github.kevinrabbe.minecraftserver.common.item;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Startup compatibility gate for every non-destroyed individualized item authority head. */
public final class IndividualItemLiveContentCompatibilityValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> ROLL_STATE_TYPE = new TypeReference<>() { };

    private IndividualItemLiveContentCompatibilityValidator() { }

    /**
     * Verifies that current item content can still interpret every live item instance.
     *
     * <p>Material, display and roll ranges may be retuned behind the same stable definition ID. Identity kind, frozen
     * roll-property keys and the equipment category required by non-zero upgrade state may not drift. Destroyed history
     * does not retain obsolete content.</p>
     */
    public static void validate(DataSource dataSource, ItemCatalog catalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT item_instance_id,
                            definition_id,
                            roll_state::text AS roll_state_json,
                            upgrade_level
                     FROM item_instances
                     WHERE location_kind <> 'DESTROYED'
                     ORDER BY item_instance_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID itemInstanceId = rows.getObject("item_instance_id", UUID.class);
                String definitionId = rows.getString("definition_id");
                ItemDefinition definition;
                try {
                    definition = catalog.require(definitionId);
                } catch (ItemCatalogException exception) {
                    throw incompatible(
                            itemInstanceId,
                            definitionId,
                            "definition is absent from the loaded item catalog",
                            exception
                    );
                }
                if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                    throw incompatible(
                            itemInstanceId,
                            definitionId,
                            "live item definition is no longer INDIVIDUAL",
                            null
                    );
                }

                int upgradeLevel = rows.getInt("upgrade_level");
                if (upgradeLevel != 0 && definition.category() != ItemCategory.EQUIPMENT) {
                    throw incompatible(
                            itemInstanceId,
                            definitionId,
                            "upgrade_level " + upgradeLevel + " requires an EQUIPMENT definition",
                            null
                    );
                }

                try {
                    Map<String, Integer> rollState = parseRollState(rows.getString("roll_state_json"));
                    IntrinsicRollResolver.resolveMultipliers(definition.rollProfile(), rollState);
                    new UpgradeState(upgradeLevel);
                } catch (JsonProcessingException | IllegalArgumentException exception) {
                    throw incompatible(
                            itemInstanceId,
                            definitionId,
                            "frozen roll/upgrade state is incompatible with loaded content: " + exception.getMessage(),
                            exception
                    );
                }
            }
        }
    }

    private static Map<String, Integer> parseRollState(String json) throws JsonProcessingException {
        if (json == null) {
            throw new IllegalArgumentException("roll_state is null");
        }
        Map<String, Integer> parsed = JSON.readValue(json, ROLL_STATE_TYPE);
        if (parsed == null) {
            throw new IllegalArgumentException("roll_state is null");
        }
        return Map.copyOf(parsed);
    }

    private static ItemCatalogException incompatible(
            UUID itemInstanceId,
            String definitionId,
            String detail,
            Throwable cause
    ) {
        String message = "Live item " + itemInstanceId + " for definition " + definitionId + ": " + detail;
        return cause == null ? new ItemCatalogException(message) : new ItemCatalogException(message, cause);
    }
}
