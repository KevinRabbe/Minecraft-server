package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionException;

import java.util.Objects;

/** Cross-catalog validation for item skill use/equip requirements. */
public final class ItemUseRequirementCatalogValidator {
    private ItemUseRequirementCatalogValidator() { }

    public static void validate(ItemCatalog itemCatalog, SkillProgressionCatalog skillCatalog) {
        Objects.requireNonNull(itemCatalog, "itemCatalog");
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        for (ItemDefinition definition : itemCatalog.definitions()) {
            for (ItemSkillRequirement requirement : definition.useRequirements().skillRequirements()) {
                try {
                    skillCatalog.require(requirement.skillId());
                } catch (SkillProgressionException exception) {
                    throw new ItemCatalogException(
                            "Item " + definition.definitionId()
                                    + " references unknown use skill " + requirement.skillId(),
                            exception
                    );
                }
            }
        }
    }
}
