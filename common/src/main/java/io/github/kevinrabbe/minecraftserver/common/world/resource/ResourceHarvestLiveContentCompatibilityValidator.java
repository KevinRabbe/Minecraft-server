package io.github.kevinrabbe.minecraftserver.common.world.resource;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogException;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Startup compatibility gate for immutable resource-harvest entitlements awaiting fulfillment. */
public final class ResourceHarvestLiveContentCompatibilityValidator {
    private ResourceHarvestLiveContentCompatibilityValidator() { }

    /**
     * Verifies that every unfulfilled harvest can still issue its frozen optional commodity and optional skill XP.
     * Rewardless managed-combat cycles intentionally pin neither catalog. Fulfilled history no longer pins content.
     */
    public static void validate(
            DataSource dataSource,
            ItemCatalog itemCatalog,
            SkillProgressionCatalog skillCatalog
    ) throws SQLException {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(skillCatalog, "skillCatalog");

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT h.harvest_id,
                            h.source_id,
                            h.source_cycle_no,
                            h.player_id,
                            h.commodity_definition_id,
                            h.skill_id
                     FROM resource_harvests h
                     LEFT JOIN resource_harvest_fulfillments f ON f.harvest_id = h.harvest_id
                     WHERE f.harvest_id IS NULL
                     ORDER BY h.created_at, h.harvest_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID harvestId = rows.getObject("harvest_id", UUID.class);
                UUID sourceId = rows.getObject("source_id", UUID.class);
                long sourceCycleNo = rows.getLong("source_cycle_no");
                UUID playerId = rows.getObject("player_id", UUID.class);
                String definitionId = rows.getString("commodity_definition_id");
                String skillId = rows.getString("skill_id");

                if (definitionId != null) {
                    ItemDefinition definition = itemCatalog.find(definitionId).orElseThrow(() -> new ItemCatalogException(
                            "Loaded item catalog is missing commodity definition_id " + definitionId
                                    + " required by unfulfilled resource harvest " + harvestId
                                    + " for player_id " + playerId
                                    + " at source " + sourceId + " cycle " + sourceCycleNo
                    ));
                    if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
                        throw new ItemCatalogException(
                                "Unfulfilled resource harvest " + harvestId
                                        + " requires definition_id " + definitionId
                                        + " to remain COMMODITY, but loaded identity kind is "
                                        + definition.identityKind()
                        );
                    }
                }

                if (skillId != null) {
                    try {
                        skillCatalog.require(new SkillId(skillId));
                    } catch (SkillProgressionException exception) {
                        throw new ResourceSourceException(
                                "Loaded skill content is missing skill_id " + skillId
                                        + " required by unfulfilled resource harvest " + harvestId
                                        + " for player_id " + playerId
                                        + " at source " + sourceId + " cycle " + sourceCycleNo,
                                exception
                        );
                    }
                }
            }
        }
    }
}
