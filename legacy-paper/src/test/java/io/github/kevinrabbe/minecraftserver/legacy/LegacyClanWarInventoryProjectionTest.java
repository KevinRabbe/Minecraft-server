package io.github.kevinrabbe.minecraftserver.legacy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClanWarInventoryProjectionTest {
    @Test
    void preservesSeparateRowsAndUsesFrozenItemIndexAsInventorySlot() {
        LegacyClanWarExecution war = war();
        LegacyClanWarRepresentationPlan plan = representationPlan(
                war,
                List.of(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 0),
                        new LegacyLoadoutItem(0, 1, "equipment.starter_sword", "{}", 0),
                        new LegacyLoadoutItem(1, 0, "equipment.starter_sword", "{}", 0)
                )
        );

        LegacyClanWarInventoryProjection projection = LegacyClanWarInventoryProjection.build(plan);
        UUID challenger = war.getExecution().getParticipants().get(0).getMinecraftUuid();
        UUID defender = war.getExecution().getParticipants().get(1).getMinecraftUuid();

        assertEquals(2, projection.getItemsByMinecraftUuid().get(challenger).size());
        assertEquals(0, projection.getItemsByMinecraftUuid().get(challenger).get(0).getInventorySlot());
        assertEquals(1, projection.getItemsByMinecraftUuid().get(challenger).get(1).getInventorySlot());
        assertEquals(1, projection.getItemsByMinecraftUuid().get(defender).size());
        assertEquals(0, projection.getItemsByMinecraftUuid().get(defender).get(0).getInventorySlot());
        assertThrows(
                UnsupportedOperationException.class,
                () -> projection.getItemsByMinecraftUuid().get(challenger).add(
                        projection.getItemsByMinecraftUuid().get(challenger).get(0)
                )
        );
    }

    @Test
    void includesParticipantsWhoseFrozenSelectionIsEmpty() {
        LegacyClanWarExecution war = war();
        LegacyClanWarRepresentationPlan plan = representationPlan(
                war,
                Collections.singletonList(
                        new LegacyLoadoutItem(0, 0, "equipment.starter_sword", "{}", 0)
                )
        );

        LegacyClanWarInventoryProjection projection = LegacyClanWarInventoryProjection.build(plan);
        UUID defender = war.getExecution().getParticipants().get(1).getMinecraftUuid();
        assertTrue(projection.getItemsByMinecraftUuid().get(defender).isEmpty());
    }

    @Test
    void refusesThirtySeventhRowInsteadOfTruncatingOrMerging() {
        LegacyClanWarExecution war = war();
        ArrayList<LegacyLoadoutItem> rows = new ArrayList<LegacyLoadoutItem>();
        for (int index = 0; index < 37; index++) {
            rows.add(new LegacyLoadoutItem(0, index, "equipment.starter_sword", "{}", 0));
        }
        LegacyClanWarRepresentationPlan plan = representationPlan(war, rows);

        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyClanWarInventoryProjection.build(plan)
        );
    }

    private static LegacyClanWarRepresentationPlan representationPlan(
            LegacyClanWarExecution war,
            List<LegacyLoadoutItem> rows
    ) {
        LegacyClanWarLoadout loadout = LegacyClanWarLoadout.requireValid(war, rows);
        Map<String, String> configured = new LinkedHashMap<String, String>();
        configured.put("equipment.starter_sword", "IRON_SWORD");
        return LegacyClanWarRepresentationPlan.build(
                war,
                loadout,
                new LegacyClanWarRepresentationCatalog(configured)
        );
    }

    private static LegacyClanWarExecution war() {
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
                List.of(
                        new LegacyParticipant(
                                0, "CHALLENGER", challengerClan,
                                UUID.randomUUID(), UUID.randomUUID(), "ProjectionA"
                        ),
                        new LegacyParticipant(
                                1, "DEFENDER", defenderClan,
                                UUID.randomUUID(), UUID.randomUUID(), "ProjectionB"
                        )
                )
        );
        return LegacyClanWarExecution.requireSupported(execution);
    }
}
