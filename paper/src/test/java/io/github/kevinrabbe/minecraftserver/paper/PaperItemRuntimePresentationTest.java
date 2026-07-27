package io.github.kevinrabbe.minecraftserver.paper;

import io.github.kevinrabbe.minecraftserver.common.item.ItemCategory;
import io.github.kevinrabbe.minecraftserver.common.item.ItemDefinition;
import io.github.kevinrabbe.minecraftserver.common.item.ItemIdentityKind;
import io.github.kevinrabbe.minecraftserver.common.item.ItemLocation;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRollProfile;
import io.github.kevinrabbe.minecraftserver.common.item.ItemRuntimeStatSnapshot;
import io.github.kevinrabbe.minecraftserver.common.item.RollRange;
import io.github.kevinrabbe.minecraftserver.common.item.UpgradeState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperItemRuntimePresentationTest {
    @Test
    void equipmentShowsExactQualityCurrentIntrinsicMultiplierAndUpgradeSeparately() {
        ItemDefinition definition = new ItemDefinition(
                "equipment.test_sword",
                "IRON_SWORD",
                "Test Sword",
                1,
                ItemCategory.EQUIPMENT,
                ItemIdentityKind.INDIVIDUAL,
                new ItemRollProfile(Map.of("damage", new RollRange(10_000, 12_000)))
        );
        ItemRuntimeStatSnapshot snapshot = snapshot(
                definition.definitionId(),
                Map.of("damage", 5_000),
                Map.of("damage", 11_000),
                3
        );

        assertEquals(
                List.of(
                        "damage roll: 50.00% quality (110.00% base)",
                        "Upgrade: +3"
                ),
                PaperItemRuntimePresentation.describe(definition, snapshot)
        );
    }

    @Test
    void nonEquipmentUniqueObjectDoesNotAdvertiseMeaninglessZeroUpgrade() {
        ItemDefinition definition = mapDefinition();

        assertEquals(
                List.of(),
                PaperItemRuntimePresentation.describe(
                        definition,
                        snapshot(definition.definitionId(), Map.of(), Map.of(), 0)
                )
        );
    }

    @Test
    void nonEquipmentGenericUpgradeStateFailsClosed() {
        ItemDefinition definition = mapDefinition();

        assertThrows(IllegalArgumentException.class, () -> PaperItemRuntimePresentation.describe(
                definition,
                snapshot(definition.definitionId(), Map.of(), Map.of(), 1)
        ));
    }

    private static ItemDefinition mapDefinition() {
        return new ItemDefinition(
                "map.test",
                "MAP",
                "Test Map",
                1,
                ItemCategory.PROGRESSION,
                ItemIdentityKind.INDIVIDUAL
        );
    }

    private static ItemRuntimeStatSnapshot snapshot(
            String definitionId,
            Map<String, Integer> qualities,
            Map<String, Integer> multipliers,
            int upgradeLevel
    ) {
        UUID playerId = UUID.randomUUID();
        return new ItemRuntimeStatSnapshot(
                UUID.randomUUID(),
                definitionId,
                ItemLocation.playerInventory(playerId),
                7,
                qualities,
                multipliers,
                new UpgradeState(upgradeLevel)
        );
    }
}
