package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationAuthorityValidator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationIssue;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Extracts custom identity claims from one loaded player inventory and validates them against PostgreSQL authority. */
final class PaperPlayerItemRepresentationValidator {
    private final PaperItemIdentityCodec identityCodec;
    private final ItemRepresentationAuthorityValidator authorityValidator;

    PaperPlayerItemRepresentationValidator(
            MinecraftServerPlugin plugin,
            DataSource dataSource,
            ItemCatalog itemCatalog
    ) {
        identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
        authorityValidator = new ItemRepresentationAuthorityValidator(
                Objects.requireNonNull(dataSource, "dataSource"),
                Objects.requireNonNull(itemCatalog, "itemCatalog")
        );
    }

    List<ItemRepresentationIssue> validate(Player player, UUID playerId) throws SQLException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(playerId, "playerId");

        ArrayList<ItemRepresentationClaim> claims = new ArrayList<>();
        collect(player.getInventory().getStorageContents(), "storage", claims);
        collect(player.getInventory().getArmorContents(), "armor", claims);
        collect(player.getInventory().getExtraContents(), "extra", claims);
        return authorityValidator.validate(playerId, claims);
    }

    private void collect(
            ItemStack[] contents,
            String section,
            List<ItemRepresentationClaim> claims
    ) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String source = section + "[" + slot + "]";
            identityCodec.readClaim(stack, source).ifPresent(claims::add);
        }
    }
}
