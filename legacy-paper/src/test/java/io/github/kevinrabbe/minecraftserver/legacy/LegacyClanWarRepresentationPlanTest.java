package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClanWarRepresentationPlanTest {
    @Test
    void preservesEveryFrozenSnapshotRowWithoutPersistentIdentity() {
        LegacyClanWarExecution war = supportedWar();
        LegacyClanWarLoadout loadout = LegacyClanWarLoadout.requireValid(
                war,
                Arrays.asList(
                        item(0, 0, "equipment.starter_sword", "{}", 0),
                        item(1, 0, "equipment.starter_sword", " { } ", 0)
                )
        );
        LinkedHashMap<String, String> configured = new LinkedHashMap<String, String>();
        configured.put("equipment.starter_sword", "IRON_SWORD");

        LegacyClanWarRepresentationPlan plan = LegacyClanWarRepresentationPlan.build(
                war,
                loadout,
                new LegacyClanWarRepresentationCatalog(configured)
        );

        assertEquals(2, plan.getItems().size());
        assertEquals(0, plan.getItems().get(0).getParticipantIndex());
        assertEquals(0, plan.getItems().get(0).getLoadoutItemIndex());
        assertEquals("equipment.starter_sword", plan.getItems().get(0).getDefinitionId());
        assertEquals("IRON_SWORD", plan.getItems().get(0).getMaterialId());
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.getItems().add(plan.getItems().get(0))
        );
    }

    @Test
    void explicitlyEmptyFinalSelectionRemainsRepresentable() {
        LegacyClanWarExecution war = supportedWar();
        LegacyClanWarLoadout loadout = LegacyClanWarLoadout.requireValid(
                war,
                Collections.<LegacyLoadoutItem>emptyList()
        );

        LegacyClanWarRepresentationPlan plan = LegacyClanWarRepresentationPlan.build(
                war,
                loadout,
                new LegacyClanWarRepresentationCatalog(Collections.<String, String>emptyMap())
        );

        assertTrue(plan.getItems().isEmpty());
    }

    @Test
    void unknownRolledOrUpgradedItemsFailClosedInsteadOfFlatteningCombatValue() {
        LegacyClanWarExecution war = supportedWar();
        LinkedHashMap<String, String> configured = new LinkedHashMap<String, String>();
        configured.put("equipment.starter_sword", "IRON_SWORD");
        LegacyClanWarRepresentationCatalog catalog = new LegacyClanWarRepresentationCatalog(configured);

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarRepresentationPlan.build(
                        war,
                        LegacyClanWarLoadout.requireValid(
                                war,
                                Collections.singletonList(item(0, 0, "equipment.unknown", "{}", 0))
                        ),
                        catalog
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarRepresentationPlan.build(
                        war,
                        LegacyClanWarLoadout.requireValid(
                                war,
                                Collections.singletonList(item(
                                        0, 0, "equipment.starter_sword", "{\"damage\":5000}", 0
                                ))
                        ),
                        catalog
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarRepresentationPlan.build(
                        war,
                        LegacyClanWarLoadout.requireValid(
                                war,
                                Collections.singletonList(item(0, 0, "equipment.starter_sword", "{}", 1))
                        ),
                        catalog
                )
        );
    }

    private static LegacyClanWarExecution supportedWar() {
        UUID challengerClan = UUID.randomUUID();
        UUID defenderClan = UUID.randomUUID();
        LegacyExecution execution = new LegacyExecution(
                UUID.randomUUID(),
                "CLAN_WAR",
                UUID.randomUUID(),
                1,
                Instant.parse("2026-08-01T18:00:00Z"),
                "war.legacy_1_8_9",
                1,
                1,
                Arrays.asList(
                        participant(0, "CHALLENGER", challengerClan),
                        participant(1, "DEFENDER", defenderClan)
                )
        );
        return LegacyClanWarExecution.requireSupported(execution);
    }

    private static LegacyParticipant participant(int index, String side, UUID clanId) {
        return new LegacyParticipant(index, side, clanId, UUID.randomUUID(), UUID.randomUUID(), "WarRep" + index);
    }

    private static LegacyLoadoutItem item(
            int participantIndex,
            int itemIndex,
            String definitionId,
            String rollState,
            int upgradeLevel
    ) {
        return new LegacyLoadoutItem(participantIndex, itemIndex, definitionId, rollState, upgradeLevel);
    }
}
