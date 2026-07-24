package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.UniqueItemInstance;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Renders catalog/authority state into Minecraft ItemStacks without creating economic value. */
final class PaperItemRenderer {
    private final ItemCatalog itemCatalog;
    private final PaperItemIdentityCodec identityCodec;

    PaperItemRenderer(MinecraftServerPlugin plugin, ItemCatalog itemCatalog) {
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        identityCodec = new PaperItemIdentityCodec(Objects.requireNonNull(plugin, "plugin"));
    }

    ItemStack renderCommodity(String definitionId, int amount) {
        ItemDefinition definition = itemCatalog.require(definitionId);
        if (definition.identityKind() != ItemIdentityKind.COMMODITY) {
            throw new IllegalArgumentException("Definition is not COMMODITY: " + definitionId);
        }
        if (amount < 1 || amount > definition.maxStackSize()) {
            throw new IllegalArgumentException(
                    "Commodity amount must be between 1 and " + definition.maxStackSize()
            );
        }

        ItemStack stack = newBaseStack(definition, amount);
        identityCodec.writeCommodity(stack, definition);
        return stack;
    }

    ItemStack renderIndividual(UniqueItemInstance instance) {
        Objects.requireNonNull(instance, "instance");
        ItemDefinition definition = itemCatalog.require(instance.definitionId());
        if (definition.identityKind() != ItemIdentityKind.INDIVIDUAL) {
            throw new IllegalArgumentException("Authority row references non-individual definition");
        }

        ItemStack stack = newBaseStack(definition, 1);
        identityCodec.writeIndividual(stack, definition, instance);
        return stack;
    }

    private static ItemStack newBaseStack(ItemDefinition definition, int amount) {
        Material material = Material.getMaterial(definition.minecraftMaterial());
        if (material == null || !material.isItem()) {
            throw new PaperItemRepresentationException(
                    "Catalog material is no longer renderable: " + definition.minecraftMaterial()
            );
        }

        ItemStack stack = ItemStack.of(material, amount);
        boolean edited = stack.editMeta(meta -> meta.itemName(Component.text(definition.displayName())));
        if (!edited) {
            throw new PaperItemRepresentationException(
                    "Could not apply presentation metadata for " + definition.definitionId()
            );
        }
        return stack;
    }
}
