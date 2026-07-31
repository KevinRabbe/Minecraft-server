package io.github.kevinrabbe.minecraftserver.common.item;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.UUID;

/** Startup compatibility gate for every live individualized item and stored player inventory representation. */
public final class ItemLiveContentCompatibilityValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> ROLL_STATE_TYPE = new TypeReference<>() { };

    private ItemLiveContentCompatibilityValidator() { }

    public static void validate(DataSource dataSource, ItemCatalog catalog) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(catalog, "catalog");

        validateIndividualAuthorityHeads(dataSource, catalog);
        validateStoredPlayerInventoryWhenSupported(dataSource, catalog);
    }

    private static void validateIndividualAuthorityHeads(DataSource dataSource, ItemCatalog catalog) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT item_instance_id,
                            definition_id,
                            location_kind,
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
                String locationKind = rows.getString("location_kind");
                ItemDefinition definition = catalog.find(definitionId).orElseThrow(() -> new ItemCatalogException(
                        "Loaded item catalog is missing live definition_id " + definitionId
                                + " required by item_instance_id " + itemInstanceId
                                + " in " + locationKind
                ));

                if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
                    throw new ItemCatalogException(
                            "Live item_instance_id " + itemInstanceId
                                    + " requires definition_id " + definitionId
                                    + " to remain INDIVIDUAL, but loaded identity kind is "
                                    + definition.identityKind()
                    );
                }

                int upgradeLevel = rows.getInt("upgrade_level");
                if (definition.category() != ItemCategory.EQUIPMENT && upgradeLevel != 0) {
                    throw new ItemCatalogException(
                            "Live item_instance_id " + itemInstanceId
                                    + " carries upgrade_level " + upgradeLevel
                                    + " but loaded definition_id " + definitionId
                                    + " is not EQUIPMENT"
                    );
                }

                try {
                    Map<String, Integer> rollState = parseRollState(rows.getString("roll_state_json"));
                    IntrinsicRollResolver.resolveMultipliers(definition.rollProfile(), rollState);
                    new UpgradeState(upgradeLevel);
                } catch (JsonProcessingException | RuntimeException exception) {
                    throw new ItemCatalogException(
                            "Loaded definition_id " + definitionId
                                    + " cannot represent frozen stat state for live item_instance_id "
                                    + itemInstanceId + ": " + exception.getMessage(),
                            exception
                    );
                }
            }
        }
    }

    private static void validateStoredPlayerInventoryWhenSupported(
            DataSource dataSource,
            ItemCatalog catalog
    ) throws SQLException {
        final Iterator<StoredPlayerItemClaimReader> readers;
        try {
            readers = ServiceLoader.load(
                    StoredPlayerItemClaimReader.class,
                    ItemLiveContentCompatibilityValidator.class.getClassLoader()
            ).iterator();
        } catch (ServiceConfigurationError error) {
            throw new ItemCatalogException("Could not discover stored player-item claim reader", error);
        }

        final StoredPlayerItemClaimReader reader;
        try {
            if (!readers.hasNext()) {
                return;
            }
            reader = readers.next();
            if (readers.hasNext()) {
                throw new ItemCatalogException("Multiple stored player-item claim readers are installed");
            }
        } catch (ServiceConfigurationError error) {
            throw new ItemCatalogException("Could not initialize stored player-item claim reader", error);
        }

        StoredPlayerItemLiveContentCompatibilityValidator.validate(dataSource, catalog, reader);
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
}
