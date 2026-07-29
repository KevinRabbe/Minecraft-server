package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationAuthorityValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationIssue;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationValidationResult;
import org.bukkit.entity.Player;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Extracts managed identity claims from loaded player inventory and validates them against PostgreSQL authority. */
final class PaperPlayerItemRepresentationValidator {
    private final DataSource dataSource;
    private final PaperManagedItemScanner managedItems;
    private final ItemRepresentationAuthorityValidator authorityValidator;

    PaperPlayerItemRepresentationValidator(
            MinecraftServerPlugin plugin,
            DataSource dataSource,
            ItemCatalog itemCatalog
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        managedItems = new PaperManagedItemScanner(Objects.requireNonNull(plugin, "plugin"));
        authorityValidator = new ItemRepresentationAuthorityValidator(
                dataSource,
                Objects.requireNonNull(itemCatalog, "itemCatalog")
        );
    }

    List<ItemRepresentationIssue> validate(Player player) throws SQLException {
        return validateAndSnapshot(player).issues();
    }

    ItemRepresentationValidationResult validateAndSnapshot(Player player) throws SQLException {
        Objects.requireNonNull(player, "player");
        return validateAndSnapshot(player.getUniqueId(), collectClaims(player));
    }

    List<ItemRepresentationClaim> collectClaims(Player player) {
        Objects.requireNonNull(player, "player");
        return managedItems.collectPlayerInventoryClaims(player.getInventory());
    }

    ItemRepresentationValidationResult validateAndSnapshot(
            UUID minecraftUuid,
            List<ItemRepresentationClaim> claims
    ) throws SQLException {
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        Objects.requireNonNull(claims, "claims");
        if (claims.isEmpty()) {
            return new ItemRepresentationValidationResult(List.of(), java.util.Map.of());
        }
        UUID playerId = requirePlayerId(minecraftUuid);
        return authorityValidator.validateAndSnapshot(playerId, claims);
    }

    private UUID requirePlayerId(UUID minecraftUuid) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_id
                     FROM players
                     WHERE minecraft_uuid = ?
                     """)) {
            statement.setObject(1, minecraftUuid);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new PaperItemRepresentationException(
                            "Authenticated Minecraft UUID has no stable internal player identity"
                    );
                }
                return results.getObject("player_id", UUID.class);
            }
        }
    }
}
