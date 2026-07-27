package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.PlayerItemUpgradeStateValidator;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic Paper projection/proof for one carried item's authority-version upgrade.
 *
 * <p>The expected next payload is reconstructed from the locked current payload. Therefore slot movement, stack/count
 * changes, unrelated item/PDC/lore edits, or any second inventory mutation make validation fail closed.</p>
 */
final class PaperPlayerItemUpgradeStateTransition implements PlayerItemUpgradeStateValidator {
    private final PaperPlayerStateCodec stateCodec = new PaperPlayerStateCodec();
    private final PaperItemIdentityCodec identityCodec;
    private final ItemCatalog itemCatalog;

    PaperPlayerItemUpgradeStateTransition(MinecraftServerPlugin plugin, ItemCatalog itemCatalog) {
        this.identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
    }

    byte[] project(
            UUID itemInstanceId,
            String definitionId,
            long fromAuthorityVersion,
            long toAuthorityVersion,
            byte[] currentStatePayload
    ) {
        Objects.requireNonNull(itemInstanceId, "itemInstanceId");
        ItemDefinition definition = itemCatalog.require(definitionId);
        PaperPlayerStateCodec.InventoryState current = stateCodec.decodeState(currentStatePayload);
        ItemStack[] storage = current.storage();
        ItemStack[] armor = current.armor();
        ItemStack[] extra = current.extra();

        int replacements = 0;
        replacements += advanceInSection(
                storage,
                "storage",
                definition,
                itemInstanceId,
                fromAuthorityVersion,
                toAuthorityVersion
        );
        replacements += advanceInSection(
                armor,
                "armor",
                definition,
                itemInstanceId,
                fromAuthorityVersion,
                toAuthorityVersion
        );
        replacements += advanceInSection(
                extra,
                "extra",
                definition,
                itemInstanceId,
                fromAuthorityVersion,
                toAuthorityVersion
        );
        if (replacements != 1) {
            throw new PaperItemRepresentationException(
                    "Expected exactly one carried representation for item upgrade " + itemInstanceId
                            + " but found " + replacements
            );
        }

        return stateCodec.encodeState(new PaperPlayerStateCodec.InventoryState(
                storage,
                armor,
                extra,
                current.heldItemSlot()
        ));
    }

    @Override
    public void verifyUpgrade(
            UUID playerId,
            UUID itemInstanceId,
            String definitionId,
            long fromAuthorityVersion,
            long toAuthorityVersion,
            int fromUpgradeLevel,
            int toUpgradeLevel,
            byte[] currentStatePayload,
            byte[] nextStatePayload
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(nextStatePayload, "nextStatePayload");
        if (toUpgradeLevel != fromUpgradeLevel + 1) {
            throw new PaperItemRepresentationException("Item upgrade level did not advance exactly once");
        }
        byte[] expected = project(
                itemInstanceId,
                definitionId,
                fromAuthorityVersion,
                toAuthorityVersion,
                currentStatePayload
        );
        if (!Arrays.equals(expected, nextStatePayload)) {
            throw new PaperItemRepresentationException(
                    "Serialized player state changed beyond the exact upgraded item authority version"
            );
        }
    }

    private int advanceInSection(
            ItemStack[] contents,
            String section,
            ItemDefinition definition,
            UUID itemInstanceId,
            long fromAuthorityVersion,
            long toAuthorityVersion
    ) {
        int replacements = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) continue;
            String source = section + "[" + slot + "]";
            ItemRepresentationClaim claim = identityCodec.readClaim(stack, source).orElse(null);
            if (claim == null || !itemInstanceId.equals(claim.itemInstanceId())) continue;
            if (++replacements > 1) {
                continue;
            }

            ItemStack upgraded = stack.clone();
            identityCodec.advanceIndividualAuthorityVersion(
                    upgraded,
                    definition,
                    itemInstanceId,
                    fromAuthorityVersion,
                    toAuthorityVersion,
                    source
            );
            contents[slot] = upgraded;
        }
        return replacements;
    }
}
