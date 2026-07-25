package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryException;
import io.github.kevinrabbe.minecraftserver.common.item.PendingUniqueDeliveryStateMutator;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemInstance;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Deterministically inserts one exact projected unique item into the first empty carried-inventory storage slot. */
final class PaperUniqueDeliveryStateMutator implements PendingUniqueDeliveryStateMutator {
    private final PaperPlayerStateCodec stateCodec = new PaperPlayerStateCodec();
    private final PaperItemIdentityCodec identityCodec;
    private final PaperItemRenderer renderer;

    PaperUniqueDeliveryStateMutator(MinecraftServerPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.identityCodec = new PaperItemIdentityCodec(plugin);
        this.renderer = new PaperItemRenderer(plugin, plugin.itemCatalog());
    }

    @Override
    public byte[] add(UUID playerId, UniqueItemInstance projectedItem, byte[] currentStatePayload) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(projectedItem, "projectedItem");
        if (!projectedItem.location().equals(ItemLocation.playerInventory(playerId))) {
            throw new PendingUniqueDeliveryException("Projected unique item is not targeted at the claiming player");
        }

        PaperPlayerStateCodec.InventoryState current = stateCodec.decodeState(currentStatePayload);
        ItemStack[] storage = current.storage();
        ItemStack[] armor = current.armor();
        ItemStack[] extra = current.extra();
        rejectExistingRepresentation(projectedItem.itemInstanceId(), storage, "storage");
        rejectExistingRepresentation(projectedItem.itemInstanceId(), armor, "armor");
        rejectExistingRepresentation(projectedItem.itemInstanceId(), extra, "extra");

        int emptySlot = -1;
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.isEmpty()) {
                emptySlot = slot;
                break;
            }
        }
        if (emptySlot < 0) {
            throw new PendingUniqueDeliveryException(
                    "Authoritative player inventory has insufficient space for unique delivery"
            );
        }

        storage[emptySlot] = renderer.renderIndividual(projectedItem);
        return stateCodec.encodeState(new PaperPlayerStateCodec.InventoryState(
                storage,
                armor,
                extra,
                current.heldItemSlot()
        ));
    }

    private void rejectExistingRepresentation(UUID itemInstanceId, ItemStack[] contents, String section) {
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<ItemRepresentationClaim> claim = identityCodec.readClaim(stack, section + "[" + slot + "]");
            if (claim.isPresent() && itemInstanceId.equals(claim.orElseThrow().itemInstanceId())) {
                throw new PendingUniqueDeliveryException(
                        "Unique item is already represented in serialized player state: " + itemInstanceId
                );
            }
        }
    }
}
