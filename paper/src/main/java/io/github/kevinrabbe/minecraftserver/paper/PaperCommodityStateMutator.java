package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.economy.BazaarException;
import io.github.kevinrabbe.minecraftserver.common.economy.CommodityStateMutator;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRepresentationClaim;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic Paper implementation of the serialized commodity mutation trust boundary.
 *
 * <p>Only PDC-tagged COMMODITY stacks with the exact definition identity count. A vanilla ItemStack that merely uses
 * the same Minecraft material is unrelated value and is never merged, removed, or accepted as authoritative stock.</p>
 */
final class PaperCommodityStateMutator implements CommodityStateMutator {
    private final ItemCatalog itemCatalog;
    private final PaperPlayerStateCodec stateCodec;
    private final PaperItemIdentityCodec identityCodec;
    private final PaperItemRenderer itemRenderer;

    PaperCommodityStateMutator(MinecraftServerPlugin plugin, ItemCatalog itemCatalog) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.stateCodec = new PaperPlayerStateCodec();
        this.identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
        this.itemRenderer = new PaperItemRenderer(plugin, itemCatalog);
    }

    @Override
    public byte[] remove(
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            byte[] currentStatePayload
    ) {
        Objects.requireNonNull(playerId, "playerId");
        ItemDefinition definition = requireCommodity(commodityDefinitionId);
        requirePositiveQuantity(quantity);

        PaperPlayerStateCodec.InventoryState current = stateCodec.decodeState(currentStatePayload);
        ItemStack[] storage = current.storage();
        long remaining = quantity;

        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack stack = storage[slot];
            if (!matchingCommodity(stack, definition, slot)) {
                continue;
            }
            int remove = (int) Math.min(remaining, stack.getAmount());
            int nextAmount = stack.getAmount() - remove;
            if (nextAmount == 0) {
                storage[slot] = null;
            } else {
                ItemStack changed = stack.clone();
                changed.setAmount(nextAmount);
                storage[slot] = changed;
            }
            remaining -= remove;
        }

        if (remaining != 0) {
            throw new BazaarException(
                    "Insufficient authoritative commodity quantity for " + definition.definitionId()
            );
        }
        return stateCodec.encodeState(new PaperPlayerStateCodec.InventoryState(
                storage,
                current.armor(),
                current.extra(),
                current.heldItemSlot()
        ));
    }

    @Override
    public byte[] add(
            UUID playerId,
            String commodityDefinitionId,
            long quantity,
            byte[] currentStatePayload
    ) {
        Objects.requireNonNull(playerId, "playerId");
        ItemDefinition definition = requireCommodity(commodityDefinitionId);
        requirePositiveQuantity(quantity);

        PaperPlayerStateCodec.InventoryState current = stateCodec.decodeState(currentStatePayload);
        ItemStack[] storage = current.storage();
        long remaining = quantity;

        // Fill existing authoritative stacks first in stable slot order.
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack stack = storage[slot];
            if (!matchingCommodity(stack, definition, slot)) {
                continue;
            }
            int capacity = definition.maxStackSize() - stack.getAmount();
            if (capacity <= 0) {
                continue;
            }
            int add = (int) Math.min(remaining, capacity);
            ItemStack changed = stack.clone();
            changed.setAmount(stack.getAmount() + add);
            storage[slot] = changed;
            remaining -= add;
        }

        // Then allocate new authoritative stacks into empty storage slots, also in stable slot order.
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack stack = storage[slot];
            if (stack != null && !stack.isEmpty()) {
                continue;
            }
            int add = (int) Math.min(remaining, definition.maxStackSize());
            storage[slot] = itemRenderer.renderCommodity(definition.definitionId(), add);
            remaining -= add;
        }

        if (remaining != 0) {
            throw new BazaarException(
                    "Authoritative player inventory has insufficient space for " + definition.definitionId()
            );
        }
        return stateCodec.encodeState(new PaperPlayerStateCodec.InventoryState(
                storage,
                current.armor(),
                current.extra(),
                current.heldItemSlot()
        ));
    }

    private ItemDefinition requireCommodity(String definitionId) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new BazaarException("Definition is not a commodity: " + definition.definitionId());
        }
        return definition;
    }

    private boolean matchingCommodity(ItemStack stack, ItemDefinition definition, int slot) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Optional<ItemRepresentationClaim> claim = identityCodec.readClaim(stack, "storage[" + slot + "]");
        if (claim.isEmpty()) {
            return false;
        }
        ItemRepresentationClaim value = claim.orElseThrow();
        if (!definition.definitionId().equals(value.definitionId())) {
            return false;
        }
        if (value.individualClaim()) {
            throw new BazaarException(
                    "Commodity representation unexpectedly contains individual identity: " + definition.definitionId()
            );
        }
        if (!definition.minecraftMaterial().equals(value.minecraftMaterial())) {
            throw new BazaarException(
                    "Commodity representation material does not match definition: " + definition.definitionId()
            );
        }
        if (value.amount() > definition.maxStackSize()) {
            throw new BazaarException(
                    "Commodity representation exceeds configured stack size: " + definition.definitionId()
            );
        }
        return true;
    }

    private static void requirePositiveQuantity(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }
}
