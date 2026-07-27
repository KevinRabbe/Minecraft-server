package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Rebuilds gameplay-relevant intrinsic item attributes from trusted runtime authority snapshots. */
final class PaperIntrinsicItemAttributes {
    static final String DAMAGE_ROLL_PROPERTY = "damage";
    private static final int BASIS_POINTS = 10_000;

    private PaperIntrinsicItemAttributes() {
    }

    /**
     * Applies definition-owned intrinsic roll stats without trusting any attribute component already carried by the
     * ItemStack. Upgrade, player-skill, enchantment, and temporary-effect stages are intentionally not handled here.
     */
    static void apply(ItemStack stack, ItemDefinition definition, ItemRuntimeStatSnapshot snapshot) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(snapshot, "snapshot");
        if (definition.category() != ItemCategory.EQUIPMENT) {
            throw new PaperItemRepresentationException(
                    "Intrinsic equipment attributes cannot be applied to " + definition.definitionId()
            );
        }
        if (!definition.definitionId().equals(snapshot.definitionId())) {
            throw new PaperItemRepresentationException(
                    "Runtime stat snapshot definition does not match " + definition.definitionId()
            );
        }
        if (!definition.rollProfile().properties().containsKey(DAMAGE_ROLL_PROPERTY)) {
            return;
        }

        Material material = stack.getType();
        if (!material.name().equals(definition.minecraftMaterial())) {
            throw new PaperItemRepresentationException(
                    "Live material does not match definition " + definition.definitionId()
            );
        }
        ItemAttributeModifiers defaults = material.getDefaultData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (defaults == null) {
            throw new PaperItemRepresentationException(
                    "Paper material has no default attribute component for " + definition.definitionId()
            );
        }

        double playerBaseDamage = defaultPlayerAttackDamage();
        int multiplierBasisPoints = snapshot.requireIntrinsicMultiplierBasisPoints(DAMAGE_ROLL_PROPERTY);
        ItemAttributeModifiers.Builder rebuilt = ItemAttributeModifiers.itemAttributes();
        int replaced = 0;
        for (ItemAttributeModifiers.Entry entry : defaults.modifiers()) {
            AttributeModifier modifier = entry.modifier();
            if (isScalableMainHandDamage(entry)) {
                replaced++;
                double rolledAmount = rolledItemAttackContribution(
                        playerBaseDamage,
                        modifier.getAmount(),
                        multiplierBasisPoints
                );
                AttributeModifier replacement = new AttributeModifier(
                        modifier.getKey(),
                        rolledAmount,
                        modifier.getOperation(),
                        modifier.getSlotGroup()
                );
                rebuilt.addModifier(entry.attribute(), replacement, entry.getGroup(), entry.display());
            } else {
                rebuilt.addModifier(entry.attribute(), modifier, entry.getGroup(), entry.display());
            }
        }
        if (replaced != 1) {
            throw new PaperItemRepresentationException(
                    "Expected exactly one scalable main-hand damage attribute for " + definition.definitionId()
                            + " but found " + replaced
            );
        }

        stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, rebuilt.build());
    }

    /**
     * Converts a multiplier of the vanilla weapon's complete pre-skill base damage back into the additive amount the
     * item must contribute. This preserves the player's vanilla baseline instead of multiplying later player bonuses.
     */
    static double rolledItemAttackContribution(
            double playerBaseDamage,
            double defaultItemContribution,
            int multiplierBasisPoints
    ) {
        if (!Double.isFinite(playerBaseDamage) || !Double.isFinite(defaultItemContribution)) {
            throw new IllegalArgumentException("attack damage inputs must be finite");
        }
        if (playerBaseDamage < 0.0 || multiplierBasisPoints < 0) {
            throw new IllegalArgumentException("attack damage inputs must be nonnegative");
        }
        double vanillaWeaponDamage = playerBaseDamage + defaultItemContribution;
        double rolledWeaponDamage = vanillaWeaponDamage * multiplierBasisPoints / BASIS_POINTS;
        double rolledContribution = rolledWeaponDamage - playerBaseDamage;
        if (!Double.isFinite(rolledContribution)) {
            throw new IllegalArgumentException("derived attack damage must be finite");
        }
        return rolledContribution;
    }

    private static boolean isScalableMainHandDamage(ItemAttributeModifiers.Entry entry) {
        return entry.attribute().equals(Attribute.ATTACK_DAMAGE)
                && entry.modifier().getOperation() == AttributeModifier.Operation.ADD_NUMBER
                && entry.getGroup().test(EquipmentSlot.HAND)
                && !entry.getGroup().test(EquipmentSlot.OFF_HAND);
    }

    private static double defaultPlayerAttackDamage() {
        AttributeInstance attackDamage = EntityType.PLAYER.getDefaultAttributes().getAttribute(Attribute.ATTACK_DAMAGE);
        if (attackDamage == null) {
            throw new PaperItemRepresentationException("Paper player defaults do not expose attack damage");
        }
        double value = attackDamage.getBaseValue();
        if (!Double.isFinite(value) || value < 0.0) {
            throw new PaperItemRepresentationException("Paper player default attack damage is invalid: " + value);
        }
        return value;
    }
}
