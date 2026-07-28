package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import io.github.kevinrabbe.minecraftserver.common.item.RollRange;
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
        requireEquipment(definition);
        if (!definition.definitionId().equals(snapshot.definitionId())) {
            throw new PaperItemRepresentationException(
                    "Runtime stat snapshot definition does not match " + definition.definitionId()
            );
        }
        if (!definition.rollProfile().properties().containsKey(DAMAGE_ROLL_PROPERTY)) {
            return;
        }

        applyDamageMultiplier(
                stack,
                definition,
                snapshot.requireIntrinsicMultiplierBasisPoints(DAMAGE_ROLL_PROPERTY),
                resolvePlayerBaseAttackDamage()
        );
    }

    /**
     * Projects a newly rendered damage-rolled item at the definition's lowest possible intrinsic value. The renderer
     * does not know or own the individualized roll; this lower bound only prevents the temporary serialized/live
     * representation from granting more damage than the eventual authoritative snapshot can justify.
     */
    static void applyConfiguredMinimum(
            ItemStack stack,
            ItemDefinition definition,
            double playerBaseDamage
    ) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(definition, "definition");
        RollRange damageRange = definition.rollProfile().properties().get(DAMAGE_ROLL_PROPERTY);
        if (damageRange == null) {
            return;
        }
        requireEquipment(definition);
        applyDamageMultiplier(stack, definition, damageRange.minimumBasisPoints(), playerBaseDamage);
    }

    private static void applyDamageMultiplier(
            ItemStack stack,
            ItemDefinition definition,
            int multiplierBasisPoints,
            double playerBaseDamage
    ) {
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

        ItemAttributeModifiers.Builder rebuilt = ItemAttributeModifiers.itemAttributes();
        int replaced = 0;
        for (ItemAttributeModifiers.Entry entry : defaults.modifiers()) {
            AttributeModifier modifier = entry.modifier();
            if (isMainHandAttackDamage(entry)) {
                if (!isScalableMainHandDamage(entry)) {
                    throw new PaperItemRepresentationException(
                            "Unexpected main-hand attack-damage attribute shape for " + definition.definitionId()
                    );
                }
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

    static double resolvePlayerBaseAttackDamage() {
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

    private static boolean isMainHandAttackDamage(ItemAttributeModifiers.Entry entry) {
        return entry.attribute().equals(Attribute.ATTACK_DAMAGE)
                && entry.getGroup().test(EquipmentSlot.HAND);
    }

    private static boolean isScalableMainHandDamage(ItemAttributeModifiers.Entry entry) {
        return isMainHandAttackDamage(entry)
                && entry.modifier().getOperation() == AttributeModifier.Operation.ADD_NUMBER
                && !entry.getGroup().test(EquipmentSlot.OFF_HAND);
    }

    private static void requireEquipment(ItemDefinition definition) {
        if (definition.category() != ItemCategory.EQUIPMENT) {
            throw new PaperItemRepresentationException(
                    "Intrinsic equipment attributes cannot be applied to " + definition.definitionId()
            );
        }
    }
}
