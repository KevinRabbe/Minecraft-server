package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalog;
import io.github.kevinrabbe.minecraftserver.common.item.ItemCatalogException;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;

/** Paper-specific validation that cannot live in the platform-neutral common module. */
final class PaperItemCatalogValidator {
    private static final String DAMAGE_ROLL_PROPERTY = "damage";

    private PaperItemCatalogValidator() {
    }

    static void validate(ItemCatalog catalog) {
        for (ItemDefinition definition : catalog.definitions()) {
            Material material = Material.getMaterial(definition.minecraftMaterial());
            if (material == null) {
                throw new ItemCatalogException(
                        "Unknown Paper Material '" + definition.minecraftMaterial()
                                + "' for item " + definition.definitionId()
                );
            }
            if (!material.isItem()) {
                throw new ItemCatalogException(
                        "Paper Material '" + definition.minecraftMaterial()
                                + "' cannot exist as an ItemStack for item " + definition.definitionId()
                );
            }
            if (definition.maxStackSize() > material.getMaxStackSize()) {
                throw new ItemCatalogException(
                        "Configured max_stack_size " + definition.maxStackSize()
                                + " exceeds Minecraft material limit " + material.getMaxStackSize()
                                + " for item " + definition.definitionId()
                );
            }
            if (definition.rollProfile().properties().containsKey(DAMAGE_ROLL_PROPERTY)) {
                validateDamageRollMaterial(definition, material);
            }
        }
    }

    private static void validateDamageRollMaterial(ItemDefinition definition, Material material) {
        ItemAttributeModifiers defaults = material.getDefaultData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (defaults == null) {
            throw unsupportedDamageRoll(definition, material, "material has no default attribute component");
        }

        List<ItemAttributeModifiers.Entry> mainHandDamageEntries = defaults.modifiers().stream()
                .filter(entry -> entry.attribute().equals(Attribute.ATTACK_DAMAGE))
                .filter(entry -> entry.getGroup().test(EquipmentSlot.HAND))
                .toList();
        if (mainHandDamageEntries.size() != 1) {
            throw unsupportedDamageRoll(
                    definition,
                    material,
                    "expected exactly one main-hand attack-damage modifier but found "
                            + mainHandDamageEntries.size()
            );
        }

        ItemAttributeModifiers.Entry damageEntry = mainHandDamageEntries.getFirst();
        if (damageEntry.getGroup().test(EquipmentSlot.OFF_HAND)) {
            throw unsupportedDamageRoll(
                    definition,
                    material,
                    "attack-damage modifier also applies to the off hand"
            );
        }
        if (damageEntry.modifier().getOperation() != AttributeModifier.Operation.ADD_NUMBER) {
            throw unsupportedDamageRoll(
                    definition,
                    material,
                    "main-hand attack-damage modifier is not ADD_NUMBER"
            );
        }
    }

    private static ItemCatalogException unsupportedDamageRoll(
            ItemDefinition definition,
            Material material,
            String detail
    ) {
        return new ItemCatalogException(
                "Item " + definition.definitionId() + " declares a damage roll but Paper material "
                        + material.name() + " cannot represent it safely: " + detail
        );
    }
}
