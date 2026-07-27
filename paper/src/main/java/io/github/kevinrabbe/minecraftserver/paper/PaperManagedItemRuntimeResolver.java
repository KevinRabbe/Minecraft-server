package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocationKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/**
 * Main-thread hot-path resolver from a managed ItemStack representation to its already validated runtime snapshot.
 *
 * <p>This never queries persistent storage. PDC is a claim, the local cache is disposable, and a version mismatch fails
 * closed rather than trusting stale lore/PDC values.</p>
 */
final class PaperManagedItemRuntimeResolver {
    private final ItemCatalog itemCatalog;
    private final PaperItemIdentityCodec identityCodec;

    PaperManagedItemRuntimeResolver(MinecraftServerPlugin plugin, ItemCatalog itemCatalog) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
    }

    Optional<ItemRuntimeStatSnapshot> find(Player player, ItemStack stack, String source) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(stack, "stack");
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (stack.isEmpty()) return Optional.empty();

        ItemRepresentationClaim claim = identityCodec.readClaim(stack, source).orElse(null);
        if (claim == null) return Optional.empty();

        ItemDefinition definition = itemCatalog.require(claim.definitionId());
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            return Optional.empty();
        }
        if (!claim.individualClaim()
                || claim.itemInstanceId() == null
                || claim.authorityVersion() == null
                || claim.amount() != 1
                || !definition.minecraftMaterial().equals(claim.minecraftMaterial())) {
            throw new PaperItemRepresentationException(
                    "Managed individual item representation is structurally invalid at " + source
            );
        }

        ItemRuntimeStatSnapshot snapshot = PaperItemRuntimeStatCache.find(
                player.getUniqueId(),
                claim.itemInstanceId(),
                claim.definitionId(),
                claim.authorityVersion()
        ).orElseThrow(() -> new PaperItemRepresentationException(
                "Managed individual item has no current validated runtime snapshot at " + source
        ));

        if (snapshot.location().kind() != ItemLocationKind.PLAYER_INVENTORY) {
            throw new PaperItemRepresentationException(
                    "Managed individual runtime snapshot is not player-inventory custody at " + source
            );
        }
        return Optional.of(snapshot);
    }
}
