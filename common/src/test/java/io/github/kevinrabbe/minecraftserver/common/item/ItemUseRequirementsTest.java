package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseRequirementsTest {
    private static final SkillId COMBAT = new SkillId("combat");
    private static final SkillId SPIDER = new SkillId("bounty.spider");

    @Test
    void unrestrictedItemsAllowMissingPlayerSkillState() {
        assertTrue(ItemUseRequirements.NONE.allows(Map.of()));
        assertTrue(ItemUseRequirements.NONE.unmet(Map.of()).isEmpty());
    }

    @Test
    void allConfiguredRequirementsMustBeMet() {
        ItemUseRequirements requirements = new ItemUseRequirements(List.of(
                new ItemSkillRequirement(SPIDER, 5),
                new ItemSkillRequirement(COMBAT, 10)
        ));

        assertTrue(requirements.allows(Map.of(COMBAT, 10, SPIDER, 5)));
        assertFalse(requirements.allows(Map.of(COMBAT, 10, SPIDER, 4)));
        assertEquals(
                List.of(new ItemSkillRequirement(SPIDER, 5)),
                requirements.unmet(Map.of(COMBAT, 10, SPIDER, 4))
        );
    }

    @Test
    void missingSkillRowsCountAsLevelZero() {
        ItemUseRequirements requirements = new ItemUseRequirements(List.of(
                new ItemSkillRequirement(COMBAT, 1)
        ));

        assertFalse(requirements.allows(Map.of()));
    }

    @Test
    void duplicateSkillRequirementsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ItemUseRequirements(List.of(
                new ItemSkillRequirement(COMBAT, 5),
                new ItemSkillRequirement(COMBAT, 10)
        )));
    }

    @Test
    void requirementLevelUsesLongTermSkillDomain() {
        assertThrows(IllegalArgumentException.class, () -> new ItemSkillRequirement(COMBAT, 0));
        assertThrows(IllegalArgumentException.class, () -> new ItemSkillRequirement(COMBAT, 101));
    }
}
