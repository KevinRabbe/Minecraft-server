package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseRequirementCatalogValidatorTest {
    @Test
    void knownUseSkillIsAccepted() {
        ItemCatalog items = new ItemCatalog(List.of(itemWithRequirement("combat", 10)));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(skill("combat")));

        assertDoesNotThrow(() -> ItemUseRequirementCatalogValidator.validate(items, skills));
    }

    @Test
    void unknownUseSkillFailsCatalogValidation() {
        ItemCatalog items = new ItemCatalog(List.of(itemWithRequirement("combat", 10)));
        SkillProgressionCatalog skills = new SkillProgressionCatalog(List.of(skill("mining")));

        ItemCatalogException exception = assertThrows(
                ItemCatalogException.class,
                () -> ItemUseRequirementCatalogValidator.validate(items, skills)
        );
        assertTrue(exception.getMessage().contains("references unknown use skill combat"));
    }

    private static ItemDefinition itemWithRequirement(String skillId, int level) {
        return new ItemDefinition(
                "equipment.test",
                "IRON_SWORD",
                "Test Equipment",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL,
                ItemRollProfile.NONE,
                new ItemUseRequirements(List.of(
                        new ItemSkillRequirement(new SkillId(skillId), level)
                ))
        );
    }

    private static SkillProgressionDefinition skill(String skillId) {
        ArrayList<Long> thresholds = new ArrayList<>(101);
        for (int level = 0; level <= 100; level++) {
            thresholds.add((long) level);
        }
        return new SkillProgressionDefinition(new SkillId(skillId), thresholds);
    }
}
