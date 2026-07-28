package io.github.kevinrabbe.minecraftserver.common.item;

import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalog;
import io.github.kevinrabbe.minecraftserver.common.progression.SkillProgressionCatalogLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BundledItemCatalogTest {
    @Test
    void bundledCatalogAlwaysPassesCommonValidation() {
        ItemCatalog catalog = new ItemCatalogLoader().loadResource("/content/items.json");
        assertNotNull(catalog);
    }

    @Test
    void bundledItemUseRequirementsReferenceKnownSkills() {
        ItemCatalog items = new ItemCatalogLoader().loadResource("/content/items.json");
        SkillProgressionCatalog skills = new SkillProgressionCatalogLoader().loadResource("/content/skills.json");

        assertDoesNotThrow(() -> ItemUseRequirementCatalogValidator.validate(items, skills));
    }

    @Test
    void bundledNonEquipmentDefinitionsNeverCarryGenericGearRollProfiles() {
        ItemCatalog catalog = new ItemCatalogLoader().loadResource("/content/items.json");

        catalog.definitions().stream()
                .filter(definition -> definition.category() != ItemCategory.EQUIPMENT)
                .forEach(definition -> assertFalse(
                        definition.rollProfile().rolled(),
                        () -> definition.definitionId() + " must not carry an equipment roll profile"
                ));
    }

    @Test
    void starterSwordRemainsCompatibleWithFrozenLegacyClanWarV1() {
        ItemCatalog catalog = new ItemCatalogLoader().loadResource("/content/items.json");
        ItemDefinition starterSword = catalog.require("equipment.starter_sword");

        assertEquals("IRON_SWORD", starterSword.minecraftMaterial());
        assertEquals(1, starterSword.maxStackSize());
        assertEquals(ItemCategory.EQUIPMENT, starterSword.category());
        assertEquals(ItemIdentityKind.INDIVIDUAL, starterSword.identityKind());
        assertEquals(
                new ItemRollProfile(Map.of("damage", new RollRange(10_000, 12_000))),
                starterSword.rollProfile()
        );
    }
}
